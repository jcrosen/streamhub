(ns streamhub.authenticate
  (:require [slingshot.slingshot :refer [throw+]]
            [clojure.set :refer [intersection]]
            [buddy.sign.jws :as jws]
            [streamhub.util :refer [contains-many? gen-exception]]))

(defn unsign-token! [token secret & [opts]]
  "Decode and validate the token claims"
  (let [args (merge {:alg :hs256} (select-keys opts [:iss :nbf :aud]))
        token-data (jws/unsign token secret args)
        require-keys (distinct (into [:exp] (opts :require)))]
    (if (contains-many? token-data require-keys)
        token-data
        (throw+ (gen-exception "Token missing required claims"
                               :validation
                               :claims-missing)))))

(defn sign-token [data secret & [alg]]
  (jws/sign data secret {:alg (or alg :hs256)}))
