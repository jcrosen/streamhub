(ns streamhub.core
  (:require [environ.core]
            [clojure.tools.reader.edn :as edn]
            [streamhub.app :refer [App start-app! start-dev!]]
            [streamhub.serve :refer [gen-handler start-server stop-server]]
            [streamhub.stream :refer [gen-streams-state gen-publisher gen-stream
                                      add-stream! start-stream! publish-to-stream!]])
  (:gen-class))

(defn make-context [env]
  ; TODO - don't use the whole env for config...
  {:config (merge
             env
             (->> env
               (keys)
               (filter #(-> % (str) (.startsWith ":streamhub")))
               (filter #(-> % (str) (.endsWith "-edn")))
               (map #(vector (keyword (apply str (drop-last 4 (subs (str %) 1))))
                             (edn/read-string (env %))))
               (into {})))
   :!streams (gen-streams-state)})

(defn make-stream-from-config [!streams stream]
  (let [md (stream :metadata)
        ref-id (stream :ref-id)
        st (gen-stream :metadata md :ref-id ref-id)
        pub-data (stream :publisher)
        publisher (gen-publisher (pub-data 0) (pub-data 1))]
    (add-stream! !streams st)
    (start-stream! !streams (st :uuid))
    (publish-to-stream! !streams (st :uuid) publisher)))

(defn setup-context! [context]
  (let [!streams (context :!streams)
        config (context :config)
        redis-conn (config :streamhub-redis-pub-sub-conn-opts)
        streams (config :streamhub-default-streams)]
    (dorun (map #(make-stream-from-config !streams %) streams))))

(defn make-app []
  (reify App
    (start! [_ env]
      (let [context (make-context env)
            handler (gen-handler context)
            port (edn/read-string (or (env :serve-port) "19424"))]
        (println (str "Starting app at " (java.util.Date.) " on port " port "."))
        (setup-context! context)
        {:context context
         :handler handler
         :serve-port port
         :server (start-server handler :port port)
         :app _}))
    (stop! [_ system]
      (println (str "Shutting down http storage app at " (java.util.Date.)))
      (when-let [server (system :server)]
        (stop-server server)))))

(defn -main [& [command & args]]
  (let [!app (ref nil)]
    (case (or command "main")
      "dev" (start-dev! !app 'environ.core/env 'streamhub.core/make-app)
      "main" (start-app! !app 'environ.core/env 'streamhub.core/make-app))))
