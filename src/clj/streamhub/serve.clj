(ns streamhub.serve
  (:require [ring.util.response :refer [response not-found content-type]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [compojure.core :refer [routes GET PUT DELETE]]
            [compojure.route :as cmpr]
            [compojure.response :refer [render]]
            [org.httpkit.server :as s]
            [clojure.core.async :as async :refer [<! >! go-loop]]
            [streamhub.stream :refer [subscribe-to-stream! close-subscription!]]))

(defn gen-stream-handler [context stream-id]
  (let [!streams (context :!streams)
        sub-chan (async/chan)
        sub-id (subscribe-to-stream! !streams stream-id sub-chan)]
    (println (str "New client subscriber: " sub-id))
    (fn [request]
      (s/with-channel request web-chan
        (let [sub-go-chan (go-loop []
                            (when-let [stream-data (<! sub-chan)]
                              (s/send! web-chan stream-data)
                              (recur)))]
          (s/on-close web-chan (fn [status]
                                (close-subscription! !streams stream-id sub-id)
                                (async/close! sub-go-chan)
                                (println "web-chan closed: " status)))
          (s/on-receive web-chan (fn [data]
                                  (s/send! web-chan data))))))))

(defn gen-routes [context]
  (routes
    (GET "/" req (str req))
    (GET "/streams" req (str "<html><head><script type='text/javascript' src='/js/main.js'></script></head><body>Streams:\n" @(context :!streams) "</body></html>"))
    (GET "/stream/:stream-id" [stream-id] (gen-stream-handler context stream-id))
    (cmpr/not-found "404 - Not Found")))

(defn gen-handler [context]
  (-> (gen-routes context)
      (wrap-defaults site-defaults)))

(defn start-server [handler & args]
  (s/run-server handler (or args {})))

(defn stop-server [server]
  (server :timeout 500))
