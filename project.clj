(defproject streamhub "0.1.0-SNAPSHOT"
  :description "Protocol agnostic streaming server"
  :url "http://github.com/jcrosen/streamhub"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2985"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.namespace "0.2.4"]
                 [org.clojure/tools.reader "0.8.13"]
                 [ring/ring-core "1.3.2"]
                 [ring/ring-defaults "0.1.4"]
                 [ring/ring-json "0.3.1"]
                 [buddy/buddy-auth "0.6.0"]
                 [slingshot "0.12.2"]
                 [compojure "1.3.2"]
                 [environ "1.0.0"]
                 [http-kit "2.1.18"]]

  :main ^:skip-aot streamhub.core

  :source-paths ["src/clj"]
  :resource-paths ["resources"]

  :target-path "target/%s"

  :plugins [[lein-cljsbuild "1.0.5"]]

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
