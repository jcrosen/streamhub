(ns streamhub.serve
  (:require [ring.util.response :refer [response redirect]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [compojure.core :refer [routes GET POST]]
            [compojure.route :as cmpr]
            [compojure.response :refer [render]]
            [org.httpkit.server :as s]
            [clojure.core.async :as async :refer [<! >! go-loop]]
            [streamhub.stream :refer [start-stream! publish-stream! close-stream!
                                      subscribe-to-stream! close-subscription! gen-stream]]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]))

(defn gen-stream-subscription [context stream-id]
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
                                (println "Subscription closed: " status)))
          (s/on-receive web-chan (fn [data]
                                  (s/send! web-chan data))))))))

(defn gen-stream-publication [context stream-data]
  (let [!streams (context :!streams)
        stream (gen-stream :metadata stream-data)
        pub-chan (stream :chan)
        stream-id (stream :id)]
    (println (str "New client publisher: " (stream :id)))
    (fn [request]
      (start-stream! !streams stream-id)
      (publish-stream! !streams stream)
      (s/with-channel request web-chan
        (s/on-close web-chan (fn [status]
                              (close-stream! !streams stream-id)
                              (println "Publication closed: " status)))
        (s/on-receive web-chan (fn [data]
                                  (async/put! pub-chan data)))))))

(defn login [request]
  "<html><body><form action='/login' method='POST'>
      <input type='text' name='token'>
      <input type='submit'>
    </form></body></html>")

(defn logout
  [request]
  (-> (redirect "/login")
      (assoc :session {})))

(defn login-auth [request]
  (if (= ((request :params) :token) "asdffdsa")
    (let [next-url (get-in request [:params :next] "/")
          session (request :session)
          username "blergh"
          updated-session (assoc session :identity (keyword username))]
      (-> (redirect next-url)
          (assoc :session updated-session)))
    (str "INVALID AUTHENTICATION")))

(defn secrets [request]
  (if-not (authenticated? request)
    (throw-unauthorized)
    "THE MONK FLIES DELTA"))

(defn gen-routes [context]
  (routes
    (GET "/" req (str req))
    (GET "/login" [] login)
    (POST "/login" [] login-auth)
    (GET "/logout" [] login)
    (GET "/secrets" [] secrets)
    (GET "/streams" req (str "<html><head><script type='text/javascript' src='/js/main.js'></script></head><body>Streams:\n" @(context :!streams) "</body></html>"))
    (GET "/stream/subscribe/:stream-id" [stream-id] (gen-stream-subscription context stream-id))
    (GET "/stream/publish" [stream-data] (gen-stream-publication context stream-data))
    (cmpr/not-found "404 - Not Found")))

(defn unauthorized-handler
  [request metadata]
  {:status 403})

(defn gen-handler [context]
  (let [auth-backend (session-backend)]
    (-> (gen-routes context)
        (wrap-authorization auth-backend)
        (wrap-authentication auth-backend)
        (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false)))))

(defn start-server [handler & args]
  (s/run-server handler (or args {})))

(defn stop-server [server]
  (server :timeout 500))
