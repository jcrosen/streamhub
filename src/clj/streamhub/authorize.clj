(ns streamhub.authorize
  (:require [slingshot.slingshot :refer [try+ throw+]]
            [buddy.auth.accessrules :refer [restrict]]
            [streamhub.util :refer [gen-exception]]))

(defn authorize! [validator & args]
  "Accepts an authorization and resource function and throws a standard exception if not authorized"
  (when-let [result (try+ (apply validator args)
                      (catch [] e (throw+ (gen-exception "Not Authorized"
                                                         :authorization
                                                         :reason "Unknown exception"))))]
    (if (result :ok)
      true
      (throw+ (gen-exception "Not Authorized"
                             :authorization
                             (result :reason))))))

(defn gen-authorize [{:keys [validator ]} args]
  #(if (pred-fn (resource-fn))
    {:ok true}
    {:ok false :reason msg}))

(defn wrap-authorization [handler validator]
  (fn [& args]
    (if (apply authorize-fn args)
      (apply handler args))))

(defn gen-map-matcher [ks]
  #(= (select-keys %1 ks)
      (select-keys %2 ks)))

