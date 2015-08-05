(ns streamhub.stream
  (:require [clojure.core.async :as async :refer [<! >! go-loop go]]
            [streamhub.util :refer [select-values gen-uuid]]
            [taoensso.carmine :as car]))

;; Publishers
(defprotocol StreamPublisher
  "A generic stream publisher protocol"
  (help [this] "A map of config keys and value descriptions")
  (start! [this chan] "Start the stream publisher")
  (stop! [this start-val] "Stop the stream publisher"))

(deftype AsyncPublisher [config]
  StreamPublisher
  (help [this] {})
  (start! [this chan]
    (go-loop []
      (when-let [data (<! (config :chan))]
        (>! chan)
        (recur))))
  (stop! [this start-val]
    (async/close! (config :chan))))

(deftype RedisSubPublisher [config]
  StreamPublisher
  (help [this] {:conn-opts "Carmine connection options map like {:spec {:host ...}}"
                :pattern "Listener pattern for redis pub/sub like 'foo:bar' or 'foo*'"})
  (start! [this chan]
    (let [spec (get-in config [:conn-opts :spec])
          pattern (config :pattern)]
      (car/with-new-pubsub-listener spec
        {pattern #(async/put! chan (str (% 2)))}
        (car/subscribe pattern))))
  (stop! [this start-val]
    (car/close-listener start-val)))

(deftype TimePublisher [config]
  StreamPublisher
  (help [this] {:interval "Time in miliseconds to wait between sending time events"
                :event-fn "Function to execute when time event is initiated"})
  (start! [this chan]
    (let [interval (get config :interval 1000)]
      (go-loop []
        (when (>! chan (str (java.util.Date.)))
          (Thread/sleep interval)
          (recur)))))
  (stop! [this start-val]
    (async/close! start-val)))

(defn gen-publisher [adapter-sym config]
  (let [sym (if (symbol? adapter-sym) adapter-sym (symbol adapter-sym))]
    ; Using eval here is a bit dangerous and slow but required given the
    ; constraints of clojure's interop with Java classes
    ; consider revising...
    (eval `(new ~sym ~config))))

;; Streams
(defn gen-streams-state [] (atom {}))

(defn gen-stream [& {metadata :metadata write-ch :write-ch ref-id :ref-id}]
  "Create a stream with an embedded write channel, metadata, and subscribers map"
  (let [chan (or write-ch (async/chan (async/sliding-buffer 1024)))
        uuid (gen-uuid "stream")]
    {:chan chan
     :metadata (or metadata {})
     :uuid uuid
     :ref-id (or ref-id uuid)
     :subscribers {}
     :publishers {}}))

(defn add-stream! [!streams stream]
  "Add a stream to the streams atom by stream ID"
  ; TO-DO don't allow overwriting of stream ids...
  (swap! !streams assoc-in [(stream :uuid)] stream))

(defn subscribe-to-stream! [!streams uuid chan]
  "Add a channel to the stream's subscribers map and return the subscription id"
  (when (contains? @!streams uuid)
    (let [sub-id (gen-uuid "sub")]
      (swap! !streams assoc-in [uuid :subscribers sub-id] chan)
      sub-id)))

(defn write-to-stream! [!streams uuid data]
  (async/put! (get-in @!streams [uuid :chan]) data))

(defn publish-to-stream! [!streams uuid publisher]
  (when (contains? @!streams uuid)
    (let [pub-id (gen-uuid "pub")
          chan (async/chan)
          go-ch (go-loop []
                  (when-let [data (<! chan)]
                    (write-to-stream! !streams uuid data)
                    (recur)))]
      (swap! !streams assoc-in [uuid :publishers pub-id] {:publisher publisher
                                                          :start-val (start! publisher chan)
                                                          :chan chan})
      pub-id)))

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

(defn close-subscriber! [!streams uuid sub-id]
  "Remove a subscription channel from the stream and close it"
  (let [stream (@!streams uuid)
        subs-key-chain [uuid :subscribers]]
    (swap! !streams update-in subs-key-chain dissoc (get-in !streams subs-key-chain) sub-id)
    (async/close! (get-in stream [:subscribers sub-id]))))

(defn close-publisher! [!streams uuid pub-id]
  "Remove a publisher from the stream and close it"
  (let [stream (@!streams uuid)
        pubs-key-chain [uuid :publishers]]
    (swap! !streams update-in pubs-key-chain dissoc (get-in !streams pubs-key-chain) pub-id)
    (let [pub (get-in stream [:publishers pub-id])]
      (stop! (pub :publisher) (pub :start-val))
      (async/close! (pub :chan)))))

(defn close-stream! [!streams uuid]
  "Remove a stream from the streams atom remove it's subscriptions and close it's channels"
  (let [stream (@!streams uuid)]
    (doseq [sub (stream :subscribers)] (close-subscriber! !streams uuid (first sub)))
    (doseq [pub (stream :publishers)] (close-publisher! !streams uuid (first pub)))
    (swap! !streams dissoc uuid)
    (doseq [chan (select-values stream [:chan :go-ch])] (async/close! chan))))
