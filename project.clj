(defproject rinha-de-backend-2006-exemplo "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [ring/ring-jetty-adapter "1.15.3"]
                 [ring/ring-json "0.5.1"]
                 [metosin/reitit-ring "0.10.1"]
                 [org.postgresql/postgresql "42.7.10"]
                 [com.github.seancorfield/next.jdbc "1.3.1093"]
                 [ring/ring-devel "1.15.3"]
                 [hikari-cp "4.0.0"]
                 [compojure "1.7.2"]
                 [envvar "1.1.2"]
                 [commons-net "3.12.0"]
                 [org.locationtech.jts/jts-core "1.20.0"]
                 [org.clojure/data.csv "1.1.1"]
                 [com.taoensso/telemere "1.2.1"]
                 [org.clojure/data.json "2.5.2"]]
  :main ^:skip-aot rinha-de-backend-2006-exemplo.endpoints
  :target-path "target/%s"
  :profiles {:test    {:dependencies [[org.testcontainers/testcontainers "1.20.4"]
                                      [org.testcontainers/postgresql "1.20.4"]]}
             :uberjar {:aot      :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
