(defproject streamhub "0.1.0-SNAPSHOT"
  :description "Protocol agnostic streaming server"
  :url "http://github.com/jcrosen/streamhub"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.namespace "0.2.4"]
                 [org.clojure/tools.reader "0.8.13"]
                 [environ "1.0.0"]
                 [ring/ring-core "1.3.2"]
                 [ring/ring-defaults "0.1.4"]
                 [compojure "1.3.2"]]
  :main ^:skip-aot streamhub.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[org.clojure/tools.nrepl "0.2.3"]
                                  [javax.servlet/servlet-api "2.5"]]}
             :test {:dependencies [[midje "1.6.3"]]}})
