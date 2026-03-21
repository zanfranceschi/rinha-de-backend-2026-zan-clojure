(defproject rinha-de-backend-2006-exemplo "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [ring/ring-jetty-adapter "1.15.3"]
                 [ring/ring-json "0.5.1"]
                 [metosin/reitit-ring "0.10.1"]
                 [ring/ring-devel "1.15.3"]
                 [compojure "1.7.2"]
                 [envvar "1.1.2"]
                 [org.locationtech.jts/jts-core "1.20.0"]
                 [com.taoensso/telemere "1.2.1"]
                 [org.clojure/data.json "2.5.2"]]
  :main ^:skip-aot rinha-de-backend-2006-exemplo.endpoints
  :target-path "target/%s"
  :profiles {:uberjar {:aot      :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
