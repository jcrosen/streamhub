(ns streamhub.serve
  (:require [ring.util.response :refer [response redirect]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-params]]
            [compojure.core :refer [routes GET POST]]
            [compojure.route :as cmpr]
            [org.httpkit.server :as s]
            [clojure.core.async :as async :refer [<! >! go-loop]]
            [streamhub.stream :refer [start-stream! publish-stream! close-stream!
                                      subscribe-to-stream! close-subscription! gen-stream
                                      gen-publisher]]
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
          (if-let [user-id (token-data :identity)]
            (merge {:identity user-id} {:user-data (get-session-data token-data)})
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

(defn gen-stream-publication [context stream-data & [ref-id]]
  (let [!streams (context :!streams)]
    (fn [request]
      (when-not (authenticated? request)
        (try+
          (require-auth! context request)
          (catch [:type :validation] e (throw-unauthorized e))))
      (let [stream (gen-stream :metadata stream-data :ref-id ref-id)
            pub-chan (stream :chan)
            uuid (stream :uuid)]
        (publish-stream! !streams stream)
        (start-stream! !streams uuid)
        (s/with-channel request web-chan
          (s/on-close web-chan (fn [status]
                                (close-stream! !streams uuid)
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
  (-> (response {:ok "ok" :identity nil})
      (assoc :session {})))

(defn gen-login-auth [context]
  (fn [request]
    (try+
      (let [updated-session (require-auth! context request)]
        (-> (response {:ok "ok" :identity (updated-session :identity)})
            (assoc :session updated-session)))
      (catch [:type :validation] e
        (let [err-resp #(into {} [[:status %1] [:body {:error %2}]])]
          (case (e :cause)
            :identity-missing (err-resp 401 "Identity not supplied")
            :token-missing (err-resp 401 "Credentials not supplied")
            :config-missing (err-resp 500 "Configuration error, contact administrator")
            (err-resp 401 (e :message))))))))

(defn get-stream-data [stream]
  (select-keys stream [:uuid :metadata :ref-id]))

(defn gen-get-streams [context]
  (fn [request]
    (let [streams @(context :!streams)]
      (response (into {} (map #(vector (% 0) (get-stream-data (% 1))) streams))))))

(defn gen-routes [context]
  (routes
    (GET "/" req (response (select-keys req [:identity :session :cookies :headers])))
    (GET "/login" [] login)
    (POST "/login" [] (gen-login-auth context))
    (GET "/logout" [] logout)
    (GET "/streams" [] (gen-get-streams context))
    (GET "/stream/subscribe/:uuid" [uuid] (gen-stream-subscription context uuid))
    (GET "/stream/publish" req
      (let [ref-id (get-in req [:params :ref-id])
            stream-data (into {} (filter #(.startsWith (str (% 0)) ":stream") (req :params)))]
        (gen-stream-publication context stream-data ref-id)))
    (cmpr/not-found "404 - Not Found")))

(defn unauthorized-handler
  [context request metadata]
  {:status 403 :body (or (metadata :message) "Nope") :headers {"Content-Type" "text/html"}})

(defn wrap-header [handler header value]
  #(assoc-in (handler %) [:headers header] value))

(defn gen-handler [context]
  (let [auth-backend (session-backend {:unauthorized-handler #(unauthorized-handler context %1 %2)})
        config (context :config)]
    (-> (gen-routes context)
        (wrap-authorization auth-backend)
        (wrap-authentication auth-backend)
        (wrap-json-response)
        (wrap-json-params)
        (wrap-header "Access-Control-Allow-Origin" (or (config :streamhub-cors-allow-origin) "*"))
        (wrap-header "Access-Control-Allow-Credentials" (or (config :streamhub-cors-allow-credentials) "true"))
        (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false)))))

(defn start-server [handler & args]
  (s/run-server handler (or args {})))

(defn stop-server [server]
  (server :timeout 500))
