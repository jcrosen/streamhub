(ns streamhub.serve
  (:require [ring.util.response :refer [response redirect]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-params]]
            [compojure.core :refer [routes GET POST]]
            [compojure.route :as cmpr]
            [org.httpkit.server :as s]
            [clojure.core.async :as async :refer [<! >! go-loop]]
            [streamhub.stream :refer [start-stream! publish-stream! close-stream!
                                      subscribe-to-stream! close-subscription! gen-stream]]
            [streamhub.auth :refer [unsign-token]]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn get-session-data [token-data]
  "Decompose the token into a map of relevant data"
  (select-keys (or token-data {})
               [:username :role :profile-url :avatar-url]))

(defn require-auth! [context request]
  "Return an authenticated session map if credentials are valid, throw+ if not"
  (if-let [token (get-in request [:params :token])]
    (let [secret (get-in context [:config :streamhub-token-secret])
          token-alg (keyword (get-in context [:config :streamhub-token-alg]))
          next-url (get-in request [:params :next-url] "/")]
      (if (and secret token-alg)
        (let [iss (get-in context [:config :streamhub-token-iss])
              token-data (unsign-token token secret {:alg token-alg :iss iss})]
          (if-let [username (token-data :username)]
            (merge {:identity username} (get-session-data token-data))
            (throw+ {:type :validation :cause :identity-missing})))
        (throw+ {:type :validation :cause :config-missing})))
    (throw+ {:type :validation :cause :token-missing})))

(defn gen-stream-subscription [context stream-id]
  (let [!streams (context :!streams)]
    (fn [request]
      (when-not (authenticated? request)
        (try+
          (require-auth! context request)
          (catch [:type :validation] e (throw-unauthorized e))))
      (let [sub-chan (async/chan)
            sub-id (subscribe-to-stream! !streams stream-id sub-chan)]
        (if sub-id
          (s/with-channel request web-chan
            (let [sub-go-chan (go-loop []
                                (when-let [stream-data (<! sub-chan)]
                                  (s/send! web-chan stream-data)
                                  (recur)))]
              (println (str "New client subscriber: " sub-id))
              (s/on-close web-chan (fn [status]
                                    (close-subscription! !streams stream-id sub-id)
                                    (async/close! sub-go-chan)
                                    (println "Subscription closed: " status)))
              (s/on-receive web-chan (fn [data]
                                      (s/send! web-chan data)))))
          {:status 404 :body "Stream not found"})))))

(defn gen-stream-publication [context stream-data]
  (let [!streams (context :!streams)]
    (fn [request]
      (when-not (authenticated? request)
        (try+
          (require-auth! context request)
          (catch [:type :validation] e (throw-unauthorized e))))
      (let [stream (gen-stream :metadata stream-data)
            pub-chan (stream :chan)
            stream-id (stream :id)]
        (publish-stream! !streams stream)
        (start-stream! !streams stream-id)
        (s/with-channel request web-chan
          (s/on-close web-chan (fn [status]
                                (close-stream! !streams stream-id)
                                (println "Publication closed: " status)))
          (s/on-receive web-chan (fn [data]
                                    (async/put! pub-chan data))))))))

(defn login [request]
  (str "<html>"
        "<body>"
          "<form action='/login' method='POST'>"
            "<input type='text' name='token'>"
            "<input type='submit'>"
          "</form>"
        "</body>"
      "</html>"))

(defn logout
  [request]
  (-> (redirect "/login")
      (assoc :session {})))

(defn gen-login-auth [context]
  (fn [request]
    (try+
      (let [next-url (get-in request [:params :next-url] "/")
            updated-session (require-auth! context request)]
        (-> (redirect next-url)
            (assoc :session updated-session)))
      (catch [:type :validation] e
        (case (e :cause)
              :identity-missing {:status 401 :body "Credentials not supplied"}
              :token-missing {:status 401 :body "Credentials not supplied"}
              :config-missing {:status 500 :body "Configuration error, contact administrator"}
              {:status 401 :body (e :message)})))))

(defn gen-routes [context]
  (routes
    (GET "/" req (str req))
    (GET "/login" [] login)
    (POST "/login" [] (gen-login-auth context))
    (GET "/logout" [] logout)
    (GET "/streams" req (str "<html><head><script type='text/javascript' src='/js/main.js'></script></head><body>Streams:\n" @(context :!streams) "</body></html>"))
    (GET "/stream/subscribe/:stream-id" req (let [stream-id (get-in req [:params :stream-id])] (gen-stream-subscription context stream-id)))
    (GET "/stream/publish" [stream-data] (gen-stream-publication context stream-data))
    (cmpr/not-found "404 - Not Found")))

(defn unauthorized-handler
  [context request metadata]
  (if (and (contains? (request :params) :token)
           (not (= "POST" (request :method))))
    ((gen-login-auth context) (assoc-in request [:params :next-url] (request :uri)))
    {:status 403 :body "Nope" :headers {"Content-Type" "text/html"}}))

(defn gen-handler [context]
  (let [auth-backend (session-backend {:unauthorized-handler #(unauthorized-handler context %1 %2)})]
    (-> (gen-routes context)
        (wrap-authorization auth-backend)
        (wrap-authentication auth-backend)
        (wrap-json-response)
        (wrap-json-params)
        (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false)))))

(defn start-server [handler & args]
  (s/run-server handler (or args {})))

(defn stop-server [server]
  (server :timeout 500))
