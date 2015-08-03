(ns streamhub.stream
  (:require [clojure.core.async :as async :refer [<! >! go-loop go]]
            [streamhub.util :refer [select-values gen-uuid]]
            [taoensso.carmine :as car]))

(defn gen-streams-state [] (atom {}))

(defn gen-stream [& {metadata :metadata write-ch :write-ch ref-id :ref-id}]
  "Create a stream with an embedded write channel, metadata, and subscribers map"
  (let [chan (or write-ch (async/chan))
        uuid (gen-uuid "stream")
        subscribers {}]
    {:chan chan
     :metadata (or metadata {})
     :uuid uuid
     :ref-id (or ref-id uuid)
     :subscribers subscribers}))

(defn publish-stream! [!streams stream]
  "Add a stream to the streams atom by stream ID"
  ; TO-DO don't allow overwriting of stream ids...
  (swap! !streams assoc-in [(stream :uuid)] stream))

(defn subscribe-to-stream! [!streams uuid chan]
  "Add a channel to the stream's subscribers map and return the subscription id"
  (when (contains? @!streams uuid)
    (let [sub-id (gen-uuid "sub")]
      (swap! !streams assoc-in [uuid :subscribers sub-id] chan)
      sub-id)))

(defn start-stream! [!streams uuid]
  "Starts a go-loop that pushes any data written to the stream to subscribers"
  (let [write-ch (get-in @!streams [uuid :chan])]
    (swap! !streams assoc-in [uuid :go-ch]
      (go-loop []
        (when-let [data (<! write-ch)]
          (loop [subscribers (get-in @!streams [uuid :subscribers])]
            ; subscribers is a set of id/channel key/val pairs
            (when-let [sub (first subscribers)]
              (async/put! (sub 1) data)
              (recur (rest subscribers))))
          (recur))))))

(defn write-to-stream! [!streams uuid data]
  (async/put! (get-in @!streams [uuid :chan]) data))

(defn close-subscription! [!streams uuid sub-id]
  "Remove a subscription channel from the stream and close it"
  (let [stream (@!streams uuid)
        subs-key-chain [uuid :subscribers]]
    (swap! !streams update-in subs-key-chain dissoc (get-in !streams subs-key-chain) sub-id)
    (async/close! (get-in stream [:subscribers sub-id]))))

(defn close-stream! [!streams uuid]
  "Remove a stream from the streams atom remove it's subscriptions and close it's channels"
  (let [stream (@!streams uuid)]
    (doseq [sub (stream :subscribers)] (close-subscription! !streams uuid (first sub)))
    (swap! !streams dissoc uuid)
    (doseq [chan (select-values stream [:chan :go-ch])] (async/close! chan))))

;; Publishers
(defprotocol StreamPublisher
  "A generic stream publisher protocol"
  (help [this] "A map of config keys and value descriptions")
  (start! [this] "Start the stream publisher")
  (stop! [this start-val] "Stop the stream publisher"))

(deftype RedisSubPublisher [config]
  StreamPublisher
  (help [this] {:conn-opts "Carmine connection options map like {:spec {:host ...}}"
                :pattern "Listener pattern for redis pub/sub like 'foo:bar' or 'foo*'"
                :event-fn "Function to execute when sub data is received"})
  (start! [this]
    (println "Starting RedisSubPublisher!")
    (let [spec (get-in config [:conn-opts :spec])
          pattern (config :pattern)
          event-fn (get config :event-fn #(println %))]
      (car/with-new-pubsub-listener spec
        {pattern event-fn}
        (car/subscribe pattern))))
  (stop! [this start-val]
    (println "Stopping RedisSubPublisher")
    (car/close-listener start-val)))

(deftype TimePublisher [config]
  StreamPublisher
  (help [this] {:interval "Time in miliseconds to wait between sending time events"
                :event-fn "Function to execute when time event is initiated"})
  (start! [this]
    (let [interval (get config :interval 1000)
          event-fn (get config :event-fn #(println %))
          control-ch (async/chan (async/dropping-buffer 1))
          loop-ch (go-loop []
                    (event-fn (str (java.util.Date.)))
                    (Thread/sleep interval)
                    (when (>! control-ch 1)
                      (recur)))]
      {:loop-ch loop-ch :control-ch control-ch}))
  (stop! [this start-val]
    (async/close! (start-val :control-ch))
    (async/close! (start-val :loop-ch))))

(defn gen-publisher [adapter-sym config]
  (let [sym (if (symbol? adapter-sym) adapter-sym (symbol adapter-sym))]
    ; Using eval here is a bit dangerous and slow but required given the
    ; constraints of clojure's interop with Java classes
    ; consider revising...
    (eval `(new ~sym ~config))))
