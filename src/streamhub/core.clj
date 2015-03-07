(ns streamhub.core
  (:require [environ.core]
            [streamhub.app :refer [App start-app! start-dev!]])
  (:gen-class))

(defn make-app []
  (reify App
    (start! [_ env]
      (println (str "Starting app at " (java.util.Date.) ".")))
    (stop! [_ system]
      (println (str "Stopping app at " (java.util.Date.) ", system:" system)))))

(defn -main [& [command & args]]
  (let [!app (ref nil)]
    (case (or command "main")
      "dev" (start-dev! !app 'environ.core/env 'streamhub.core/make-app)
      "main" (start-app! !app 'environ.core/env 'streamhub.core/make-app))))
