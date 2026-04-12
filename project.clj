(defproject rinha-de-backend-2026-exemplo "0.1.0-SNAPSHOT"
  :description "Rinha de Backend 2026 - Fraud Detection Engine - Example Submission"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [ring/ring-jetty-adapter "1.15.3"]
                 [ring/ring-json "0.5.1"]
                 [compojure "1.7.2"]
                 [envvar "1.1.2"]
                 [com.taoensso/telemere "1.2.1"]
                 [org.clojure/data.json "2.5.2"]
                 [com.github.haifengl/smile-core "4.3.0"
                  :exclusions [org.bytedeco/openblas
                               org.bytedeco/arpack-ng
                               org.bytedeco/javacpp
                               org.duckdb/duckdb_jdbc
                               com.epam/parso
                               org.apache.commons/commons-csv]]]
  :main ^:skip-aot rinha-de-backend-2026-exemplo.endpoints
  :target-path "target/%s"
  :profiles {:uberjar {:aot      [rinha-de-backend-2026-exemplo.endpoints]
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
