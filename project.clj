(defproject streamhub "0.1.0-SNAPSHOT"
  :description "Protocol agnostic streaming server"
  :url "http://github.com/jcrosen/streamhub"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.48"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [org.clojure/tools.reader "0.10.0-alpha1"]
                 [ring/ring-core "1.4.0"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-json "0.4.0"]
                 [buddy/buddy-auth "0.6.2"]
                 [slingshot "0.12.2"]
                 [compojure "1.4.0"]
                 [environ "1.0.0"]
                 [http-kit "2.1.19"]
                 [com.taoensso/carmine "2.11.1"]]

  :main ^:skip-aot streamhub.core

  :source-paths ["src/clj"]
  :resource-paths ["resources"]

  :target-path "target/%s"

  :plugins [[lein-cljsbuild "1.1.0"]]

  :cljsbuild { 
    :builds {:dev { :source-paths ["src/cljs"]
                    :compiler {
                      :output-dir "resources/public/js/out"
                      :output-to "resources/public/js/main.js"
                      :main "streamhub.main"
                      :asset-path "js/out"
                      :optimizations :none}}}}

  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[org.clojure/tools.nrepl "0.2.3"]
                                  [javax.servlet/servlet-api "2.5"]]}
             :test {:dependencies [[midje "1.6.3"]]}})
