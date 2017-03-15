(ns streamhub.authorize
  (:require [slingshot.slingshot :refer [try+ throw+]]
            [buddy.auth.accessrules :refer [restrict]]
            [streamhub.util :refer [gen-exception]]))

; Authorization involves:
;  * Resource/Event
;  * User context
;  * Validator function
;    * Should accept a map with :user-context
;    * Default is a basic ACL with the standard owner/group/world and
;      read/write/execute permissions
;      * read == can subscribe, is visible
;      * write == can publish, can edit metadata
;      * execute == 

(defn get-acl [resource]
  (get-in resource [:authorize :acl]))

(defn gen-acl [acl-set]
  (let [acl (or acl-set ())])
  {:authorize {:acl (or acl-set {:owner [:r :w :x]})}})

(defn validate-acl [])

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

