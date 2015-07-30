(ns streamhub.auth
  (:require [slingshot.slingshot :refer [throw+]]
            [clojure.set :refer [intersection]]
            [buddy.sign.jws :as jws]
            [streamhub.util :refer [contains-many?]]))

(defn unsign-token [token secret & [opts]]
  "Decode and validate the token claims"
  (let [args (merge {:alg :hs256} (select-keys opts [:iss :nbf :aud]))
        token-data (jws/unsign token secret args)
        require-keys (distinct (into [:exp] (opts :require)))]
    (if (contains-many? token-data require-keys)
        token-data
        (throw+ (let []
                  {:message "Token missing required claims"
                   :type :validation
                   :cause :claims-missing})))))

(defn sign-token [data secret & [alg]]
  (jws/sign data secret {:alg (or alg :hs256)}))


