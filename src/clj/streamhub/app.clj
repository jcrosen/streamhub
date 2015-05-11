(ns ^{:clojure.tools.namespace.repl/load false}
  streamhub.app
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.tools.nrepl.server :refer [start-server] :rename {start-server start-nepl-server}]
            [clojure.tools.reader.edn :as edn]))

(defprotocol App
  (start! [_ env])
  (stop! [_ system]))

(defn resolve-sym [sym]
  (when-let [sym-ns (namespace sym)]
    (require (symbol sym-ns)))
  (ns-resolve *ns* sym))

(defn get-sym [sym]
  (let [resolved-sym (resolve-sym sym)]
    (deref resolved-sym)))

(defn start-app! [!instance env-sym app-sym]
  (let [make-app (get-sym app-sym)
        app (make-app)
        env (get-sym env-sym)
        instance (start! app env)]
    (dosync
      (ref-set !instance instance))
    instance))

(defn stop-app! [!instance]
  (let [instance @!instance
        app (instance :app)]
    (stop! app instance)
    (dosync
      (ref-set !instance nil))
    nil))

(defn- bind-user-tools! [!instance env-sym app-sym]
  (let [stop-app-fn (get-sym 'streamhub.app/stop-app!)
        reload-app-fn (get-sym 'streamhub.app/reload-dev-app!)]
    (intern 'user '!app !instance)
    (intern 'user 'stop-app! #(stop-app-fn !instance))
    (intern 'user 'reload-app! #(reload-app-fn !instance env-sym app-sym))))

(defn start-nrepl! [& port]
  (let [nrepl-port (or (first port) 7888)]
    (start-nepl-server :port nrepl-port)
    (println (str "Started nREPL server, port " nrepl-port))))

(defn- reload-dev-app! [!instance env-sym app-sym]
  (when @!instance
    (stop-app! !instance))
  (refresh)
  (let [start-fn-sym 'streamhub.app/start-app!
        bind-tools-fn-sym 'streamhub.app/bind-user-tools!]
    ((get-sym bind-tools-fn-sym) !instance env-sym app-sym)
    ((get-sym start-fn-sym) !instance env-sym app-sym))
  (println "App reloaded!"))

(defn start-dev! [!instance env-sym app-sym]
  (start-nrepl! (edn/read-string (or ((get-sym env-sym) :nrepl-port) "7888")))
  (reload-dev-app! !instance env-sym app-sym))


