(ns streamhub.serve
  (:require [ring.util.response :refer [response not-found content-type]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response]]
            [compojure.core :refer [routes GET PUT DELETE]]
            [compojure.route :as cmpr]
            [compojure.response :refer [render]]))

(defn gen-routes [context]
  (routes
    (GET "/" request (str request))
    (cmpr/not-found "404 - Not Found")))

(defn gen-handler [context]
  (-> (gen-routes context)
      (wrap-defaults site-defaults)))
