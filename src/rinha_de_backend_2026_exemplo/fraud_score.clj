(ns rinha-de-backend-2026-exemplo.fraud-score
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [rinha-de-backend-2026-exemplo.knn :as knn]
   [rinha-de-backend-2026-exemplo.normalization :as norm])
  (:import [java.io DataInputStream BufferedInputStream]))

;; ---------------------------------------------------------------------------
;; Load resource files at startup
;; ---------------------------------------------------------------------------

(def normalization-config
  (json/read-str (slurp (io/resource "normalization.json")) :key-fn keyword))

(def mcc-risk
  (json/read-str (slurp (io/resource "mcc_risk.json"))))

(def references
  (with-open [dis (DataInputStream. (BufferedInputStream. (.openStream (io/resource "references.bin"))))]
    (let [count (.readInt dis)
          dim   (.readInt dis)]
      (mapv (fn [_]
              (let [label (if (== 1 (.readUnsignedByte dis)) "fraud" "legit")
                    vec   (double-array dim)]
                (dotimes [i dim]
                  (aset vec i (.readDouble dis)))
                {:vector vec :label label}))
            (range count)))))

(def cosine-search-index (knn/build-search references knn/cosine-dist))
(def euclidean-search-index (knn/build-search references knn/euclidean-dist))

;; ---------------------------------------------------------------------------
;; KNN parameters
;; ---------------------------------------------------------------------------

(def k 5)
(def threshold 0.6)

;; ---------------------------------------------------------------------------
;; Score
;; ---------------------------------------------------------------------------

(defn score
  "Score a transaction for fraud using cosine KNN detection.
   Returns {:approved bool :fraud_score float}."
  [request]
  (let [vector (norm/normalize request normalization-config mcc-risk)]
    (knn/classify vector cosine-search-index k threshold)))

(defn score-alt
  "Score a transaction for fraud using euclidean KNN detection.
   Returns {:approved bool :fraud_score float}."
  [request]
  (let [vector (norm/normalize request normalization-config mcc-risk)]
    (knn/classify vector euclidean-search-index k threshold)))
