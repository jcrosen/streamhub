(ns streamhub.core
  (:require [environ.core]
            [clojure.tools.reader.edn :as edn]
            [streamhub.app :refer [App start-app! start-dev!]]
            [streamhub.serve :refer [gen-handler start-server stop-server]]
            [streamhub.stream :refer [gen-streams-state]])
  (:gen-class))

(defn make-context [env]
  ; TODO - don't use the whole env for config...
  {:config env
   :!streams (gen-streams-state)})

(defn make-app []
  (reify App
    (start! [_ env]
      (let [context (make-context env)
            handler (gen-handler context)
            port (edn/read-string (or (env :serve-port) "19424"))]
        (println (str "Starting app at " (java.util.Date.) " on port " port "."))
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
