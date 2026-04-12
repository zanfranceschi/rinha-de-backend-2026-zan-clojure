(ns rinha-de-backend-2026-exemplo.convert-references
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.io DataOutputStream BufferedOutputStream FileOutputStream]))

(defn -main [& _args]
  (let [input  (io/resource "references.json")
        output (io/file "resources/references.bin")
        data   (json/read-str (slurp input) :key-fn keyword)
        n      (count data)
        dim    (count (:vector (first data)))]
    (with-open [dos (DataOutputStream. (BufferedOutputStream. (FileOutputStream. output)))]
      (.writeInt dos n)
      (.writeInt dos dim)
      (doseq [entry data]
        (.writeByte dos (if (= "fraud" (:label entry)) 1 0))
        (doseq [v (:vector entry)]
          (.writeDouble dos (double v)))))
    (println (format "Wrote %d entries (%dd) to %s (%.0f KB)"
                     n dim (.getPath output) (/ (.length output) 1024.0)))))
