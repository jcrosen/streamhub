(ns streamhub.authorize
  (:require [slingshot.slingshot :refer [throw+]]
            [buddy.auth.accessrules :refer [restrict]]
            [streamhub.util :refer [gen-exception]]))

(defn authorize! [resource validator]
  "Accepts a resource and validator function"
  (let [result (validator resource)]
    (if (result :ok)
      true
      (throw+ (gen-exception "Not Authorized"
                             :authorization
                             (result :reason))))))

(defn gen-validator [pred-fn resource-fn & {msg :msg}]
  #(if (pred-fn (resource-fn))
    {:ok true}
    {:ok false :reason msg}))

(defn gen-map-matcher [ks]
  #(= (select-keys %1 ks)
      (select-keys %2 ks)))
