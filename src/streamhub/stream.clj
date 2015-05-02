(ns streamhub.stream
  (:require [clojure.core.async :as async :refer [<! >! go-loop go]]
            [streamhub.util :refer [select-values gen-uuid]]))

(defn gen-streams-state [] (atom {}))

(defn gen-stream [& {metadata :metadata write-ch :write-ch}]
  "Create a stream with an embedded write channel, metadata, and subscribers map"
  (let [chan (or write-ch (async/chan))
        stream-id (gen-uuid "stream")
        subscribers {}]
    {:chan chan
     :metadata (or metadata {})
     :id stream-id
     :subscribers subscribers}))

(defn publish-stream! [!streams stream]
  "Add a stream to the streams atom by stream ID"
  (swap! !streams assoc-in [(stream :id)] stream))

(defn subscribe-to-stream! [!streams stream-id chan]
  "Add a channel to the stream's subscribers map and return the subscription id"
  (let [sub-id (gen-uuid "sub")]
    (swap! !streams assoc-in [stream-id :subscribers sub-id] chan)
    sub-id))

(defn start-stream! [!streams stream-id]
  "Starts a go-loop that pushes any data written to the stream to subscribers"
  (let [write-ch (get-in @!streams [stream-id :chan])]
    (swap! !streams assoc-in [stream-id :go-ch]
      (async/go-loop []
        (when-let [data (<! write-ch)]
          (loop [subscribers (get-in @!streams [stream-id :subscribers])]
            ; subscribers is a set of id/channel key/val pairs
            (when-let [sub (first subscribers)]
              (async/put! (sub 1) data)
              (recur (rest subscribers))))
          (recur))))))

(defn write-to-stream [!streams stream-id data]
  (async/put! (get-in @!streams [stream-id :chan]) data))

(defn close-subscription! [!streams stream-id sub-id]
  "Remove a subscription channel from the stream and close it"
  (let [stream (@!streams stream-id)
        subs-key-chain [stream-id :subscribers]]
    (swap! !streams update-in subs-key-chain dissoc (get-in !streams subs-key-chain) sub-id)
    (async/close! (get-in stream [:subscribers sub-id]))))

(defn close-stream! [!streams stream-id]
  "Remove a stream from the streams atom remove it's subscriptions and close it's channels"
  (let [stream (@!streams stream-id)]
    (doseq [sub (stream :subscribers)] (close-subscription! !streams stream-id (first sub)))
    (swap! !streams dissoc stream-id)
    (doseq [chan (select-values stream [:chan :go-ch])] (async/close! chan))))
