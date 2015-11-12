(ns streamhub.serve
  (:require [ring.util.response :refer [response redirect]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-params]]
            [cognitect.transit :as transit]
            [compojure.core :refer [routes GET POST]]
            [compojure.route :as cmpr]
            [org.httpkit.server :as s]
            [clojure.core.async :as async :refer [<! >! go-loop]]
            [streamhub.stream :refer [gen-stream add-stream!
                                      start-stream! close-stream!
                                      publish-to-stream! close-publisher!
                                      subscribe-to-stream! close-subscriber!]]
            [streamhub.auth :refer [unsign-token]]
            [streamhub.util :refer [gen-uuid select-values]]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import [streamhub.stream AsyncPublisher StreamPublisher]
           [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn get-token-session-data [token-data]
  "Decompose the token into a map of relevant data"
  (select-keys (or token-data {})
               [:name :role :url :avatar-url]))

(defn require-auth! [context request]
  "Return an authenticated session map if credentials are valid, throw+ if not"
  (if-let [token (get-in request [:params :token])]
    (let [secret (get-in context [:config :streamhub-token-secret])
          token-alg (keyword (get-in context [:config :streamhub-token-alg]))
          next-url (get-in request [:params :next-url] "/")]
      (if (and secret token-alg)
        (let [iss (get-in context [:config :streamhub-token-iss])
              token-data (unsign-token token secret {:alg token-alg :iss iss})]
          (if-let [client-id (token-data :identity)]
            (merge {:identity client-id} {:client-data (get-token-session-data token-data)})
            (throw+ {:type :validation :cause :identity-missing})))
        (throw+ {:type :validation :cause :config-missing})))
    (throw+ {:type :validation :cause :token-missing})))

(defn gen-clients-state []
  (atom {}))

(defn deserialize-client-message [payload]
  (let [input (ByteArrayInputStream. (.getBytes payload))
        reader (transit/reader input :json)
        message (transit/read reader)
        type (message :type)]
    {:type type
     :data (message :data)}))

(defn serialize-client-message [type data]
  (let [message {:type type
                 :data data}
        output (ByteArrayOutputStream. 4096)
        writer (transit/writer output :json)]
    (transit/write writer message)
    (.toString output)))

(defn subscribe-client-to-stream! [context client-id stream-id]
  (let [{:keys [!streams !clients]} context
        client (@!clients client-id)
        sub-chan (async/chan (async/sliding-buffer 1024))
        sub-id (subscribe-to-stream! !streams stream-id sub-chan)
        publisher (new AsyncPublisher {:chan sub-chan})
        send-stream-id (client :send-stream-id)
        send-pub-id (publish-to-stream! !streams send-stream-id publisher)
        error (if (nil? sub-id)
                "Error: unable to subscribe "
                (if (nil? send-pub-id)
                  "Error: unable to publish subscription to client"
                  false))]
    (if error (do
                (close-subscriber! !streams stream-id sub-id)
                (close-publisher! !streams send-stream-id send-pub-id))
              (swap! !clients assoc-in [client-id :subscriptions stream-id]
                                       {:sub-id sub-id :send-pub-id send-pub-id}))
    {:error (or error "")
     :status (if error :error :ok)
     :stream-id stream-id
     :sub-id sub-id
     :send-pub-id send-pub-id}))

(defn unsubscribe-client-from-stream! [context client-id stream-id]
  (let [{:keys [!streams !clients]} context
        client (@!clients client-id)
        {:keys [sub-id send-pub-id]} (get-in client [:subscriptions stream-id])
        send-stream-id (client :send-stream-id)]
    (close-subscriber! !streams stream-id sub-id)
    (close-publisher! !streams send-stream-id send-pub-id)
    {:error ""
     :command :unsubscribe-from-stream
     :status :ok
     :stream-id stream-id
     :sub-id sub-id
     :send-pub-id send-pub-id}))

(defn handle-client-command [context client-id data]
  (let [command (data :command)
        result (case command
                    :subscribe-to-stream
                      (let [stream-id (data :stream-id)]
                        (subscribe-client-to-stream! context client-id stream-id))
                    :unsubscribe-from-stream
                      (let [stream-id (data :stream-id)]
                        (unsubscribe-client-from-stream! context client-id stream-id))
                    {:status :error
                     :error "Command not recognized"})]
    (assoc result :command command)))

(defn handle-client-query [context client-id data]
  (let [query (data :query)]
    (assoc {} :query query)))

(defn handle-client-request [context client-id data]
  (println (str "Handling request for client " client-id " with data: " data))
  (let [type (data :type)
        handle-fn (case type
                    :command #(handle-client-command context client-id data)
                    :query   #(handle-client-query context client-id data))
        control-chan (get-in @(context :!clients) [client-id :control-chan])
        result (handle-fn)]
    (async/put! control-chan result)))

(defn gen-client! [!streams client-id]
  "Creates a two-way communication client via dedicated send/receive streams and a dedicated control channel"
  (let [send-stream (gen-stream :ref-id (str "client-send-" client-id))
        receive-stream (gen-stream :ref-id (str "client-receive-" client-id))]
    (add-stream! !streams send-stream)
    (add-stream! !streams receive-stream)
    (start-stream! !streams (send-stream :uuid))
    (start-stream! !streams (receive-stream :uuid))
    (let [send-chan (async/chan (async/sliding-buffer 1024))
          send-sub-id (subscribe-to-stream! !streams (send-stream :uuid) send-chan)
          receive-chan (async/chan (async/sliding-buffer 1024))
          receive-pub-id (publish-to-stream! !streams (receive-stream :uuid) (new AsyncPublisher {:chan receive-chan}))]
      {:uuid (gen-uuid (str "client-" client-id))
       :client-id client-id
       :control-chan (async/chan (async/dropping-buffer 1024))
       :send-chan send-chan
       :send-stream-id (send-stream :uuid)
       :send-sub-id send-sub-id
       :receive-chan receive-chan
       :receive-stream-id (receive-stream :uuid)
       :receive-pub-id receive-pub-id})))

(defn close-client! [context client-id]
  (let [{:keys [!streams !clients]} context
        client (@!clients client-id)]
  (println (str "Closing client " client-id "!"))
  (doseq [stream-id (select-values client [:send-stream-id :receive-stream-id])]
    (close-stream! !streams stream-id))
  (doseq [chan (select-values client [:send-chan :receive-chan :control-chan])]
    (async/close! chan))))

(defn gen-client-handler [context]
  (let [{:keys [!streams !clients]} context]
    (fn [request]
      ; Get client identity from the session or throw an unauthorized error
      (let [client-id (if (authenticated? request)
                        (request :identity)
                        (try+
                          (require-auth! context request)
                          (catch [:type :validation] e (throw-unauthorized e))))]
        (s/with-channel request client-chan
          (let [client (gen-client! !streams client-id)
                {:keys [receive-chan send-chan control-chan]} client
                send-go-chan    (go-loop []
                                  (when-let [data (<! send-chan)]
                                    (s/send! client-chan (serialize-client-message :stream data))
                                    (recur)))
                contrl-go-chan  (go-loop []
                                  (when-let [data (<! control-chan)]
                                    (s/send! client-chan (serialize-client-message :control data))
                                    (recur)))]
            (swap! !clients assoc client-id client)
            (s/on-close client-chan
              (fn [status]
                (println (str "Closing client " client-id "; status: " status))
                (close-client! context client-id)
                (swap! !clients dissoc client-id)))
            (s/on-receive client-chan
              (fn [message] 
                (let [{:keys [type data]} (deserialize-client-message message)]
                  (if (= type :control)
                    (handle-client-request context client-id data)
                    (async/put! receive-chan data)))))))))))

(defn login [request]
  (str "<html>"
        "<body>"
          "<script type='text/javascript' src='js/main.js'></script>"
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
    (GET "/ws" [] (gen-client-handler context))
    (GET "/stream/subscribe/:uuid" [uuid] (gen-stream-subscription context uuid))
    (GET "/stream/publish" req
      (let [ref-id (get-in req [:params :ref-id])
            stream-data (into {} (filter #(.startsWith (str (% 0)) ":stream") (req :params)))]
        (gen-stream-publication context stream-data ref-id)))
    (cmpr/resources "/")
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
