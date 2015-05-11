(ns streamhub.util)

(defn select-values [select-map keys-seq]
  "Given a map and a seq of keys return a vector of the corresponding values"
  (remove nil? (reduce #(conj %1 (select-map %2)) [] keys-seq)))

(defn gen-uuid [type] (str type "-" (java.util.UUID/randomUUID)))
