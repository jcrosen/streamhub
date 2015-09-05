(ns streamhub.util)

(defn select-values [select-map keys-seq]
  "Given a map and a seq of keys return a vector of the corresponding values"
  (remove nil? (reduce #(conj %1 (select-map %2)) [] keys-seq)))

(defn gen-uuid [type]
  (str type "-" (java.util.UUID/randomUUID)))

(defn contains-many? [m ks]
  "True if all values are present in sequence"
  (every? #(contains? m %) ks))

(defn gen-exception [message type cause [& custom]]
  (merge custom {:message message
                 :type type
                 :cause cause}))

(defn match-maps [m1 m2 [& ks]]
  (let [_ks (or ks (concat (keys m1) (keys m2)))]
    (= (select-keys m1 _ks)
       (select-keys m2 _ks))))
