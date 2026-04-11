(ns rinha-de-backend-2026-exemplo.authorization
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [rinha-de-backend-2026-exemplo.knn :as knn]
   [rinha-de-backend-2026-exemplo.normalization :as norm]))

;; ---------------------------------------------------------------------------
;; Load resource files at startup
;; ---------------------------------------------------------------------------

(def normalization-config
  (json/read-str (slurp (io/resource "normalization.json")) :key-fn keyword))

(def mcc-risk
  (json/read-str (slurp (io/resource "mcc_risk.json"))))

(def references
  (let [raw (json/read-str (slurp (io/resource "references.json")) :key-fn keyword)]
    (mapv (fn [ref]
            {:vector (mapv double (:vector ref))
             :label  (:label ref)})
          raw)))

;; ---------------------------------------------------------------------------
;; KNN parameters
;; ---------------------------------------------------------------------------

(def k 5)
(def threshold 0.6)

;; ---------------------------------------------------------------------------
;; Authorize
;; ---------------------------------------------------------------------------

(defn authorize
  "Authorize a transaction using KNN fraud detection.
   Returns {:approved bool :fraud_score float}."
  [request]
  (let [vector (norm/normalize request normalization-config mcc-risk)]
    (knn/classify vector references k threshold)))
