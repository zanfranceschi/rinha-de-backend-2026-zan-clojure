(ns fraud.core
  (:require [org.httpkit.server :as hk]
            [charred.api :as json]
            [fraud.handler :as handler])
  (:import [java.io InputStreamReader BufferedReader FileInputStream]
           [java.util.zip GZIPInputStream])
  (:gen-class))

(defn load-json [path]
  (json/read-json (slurp path)))

(defn load-references [path]
  (with-open [is  (FileInputStream. ^String path)
              gis (GZIPInputStream. is)
              rdr (BufferedReader. (InputStreamReader. gis "UTF-8"))]
    (json/read-json rdr)))

(defn parse-references [refs-raw]
  (let [n        (count refs-raw)
        ref-vecs (object-array n)
        ref-lbls (object-array n)]
    (doseq [[i entry] (map-indexed vector refs-raw)]
      (let [v   (get entry "vector")
            arr (double-array 14)]
        (dotimes [j 14]
          (aset arr j (double (nth v j))))
        (aset ref-vecs (int i) arr)
        (aset ref-lbls (int i) (get entry "label"))))
    [ref-vecs ref-lbls n]))

(defn -main [& _args]
  (let [port                  (Integer/parseInt (or (System/getenv "PORT") "8080"))
        data-dir              (or (System/getenv "DATA_DIR") "/data")
        norm                  (load-json (str data-dir "/normalization.json"))
        mcc-risk              (load-json (str data-dir "/mcc_risk.json"))
        refs-raw              (load-references (str data-dir "/references.json.gz"))
        [ref-vecs ref-lbls n] (parse-references refs-raw)]
    (println (str "Loaded " n " reference vectors"))
    (println (str "Starting server on port " port))
    (hk/run-server (handler/make-handler ref-vecs ref-lbls norm mcc-risk)
                   {:port port})
    @(promise)))
