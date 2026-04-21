(ns rinha-de-backend-2026.fraud-score
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [rinha-de-backend-2026.knn :as knn]
   [rinha-de-backend-2026.normalization :as norm])
  (:import [java.io DataInputStream BufferedInputStream]))

;; ---------------------------------------------------------------------------
;; Load resource files at startup
;; ---------------------------------------------------------------------------

(def normalization-config
  (json/read-str (slurp (io/resource "normalization.json")) :key-fn keyword))

(def mcc-risk
  (json/read-str (slurp (io/resource "mcc_risk.json"))))

(defn- read-ivf-bin
  "Read an IVF-formatted binary resource and return the {:centroids :cells}
   shape expected by knn/build-index. Labels are materialized as strings
   here; build-index converts them to byte arrays."
  [resource-name]
  (with-open [dis (DataInputStream. (BufferedInputStream. (.openStream (io/resource resource-name))))]
    (let [_total (.readInt dis)
          dim    (.readInt dis)
          nlist  (.readInt dis)
          centroids (vec (for [_ (range nlist)]
                           (vec (for [_ (range dim)]
                                  (.readDouble dis)))))
          cells (vec (for [_ (range nlist)]
                       (let [sz (.readInt dis)]
                         (loop [i 0
                                vs (transient [])
                                ls (transient [])]
                           (if (< i sz)
                             (let [lbl (if (== 1 (.readUnsignedByte dis)) "fraud" "legit")
                                   v   (double-array dim)]
                               (dotimes [j dim]
                                 (aset v j (.readDouble dis)))
                               (recur (inc i)
                                      (conj! vs v)
                                      (conj! ls lbl)))
                             {:vectors (persistent! vs)
                              :labels  (persistent! ls)})))))]
      {:centroids centroids :cells cells})))

(def search-index
  (knn/build-index (read-ivf-bin "references.bin")))

;; ---------------------------------------------------------------------------
;; IVF + KNN parameters
;; ---------------------------------------------------------------------------

(def ^:const k         5)
(def ^:const threshold 0.6)
(def ^:const nlist     256)  ; informational only; actual nlist comes from the file
(def ^:const nprobe    8)

;; ---------------------------------------------------------------------------
;; Score
;; ---------------------------------------------------------------------------

(defn score
  "Score a transaction for fraud using IVF KNN detection.
   Returns {:approved bool :fraud_score float}."
  [request]
  (let [vector (norm/normalize request normalization-config mcc-risk)]
    (knn/classify vector search-index k threshold nprobe)))

;; ---------------------------------------------------------------------------
;; JIT warm-up. Runs synthetic classifications so HotSpot promotes the IVF
;; hot paths (centroid rank, cell scan, PriorityQueue ops) before the server
;; accepts real traffic. Blocks server startup via the ns-load chain.
;; ---------------------------------------------------------------------------

(let [;; pick any non-empty cell to read the dimension from
      dim (let [cells (:cells search-index)
                n     (alength cells)
                probe (loop [i 0]
                        (if (< i n)
                          (let [m (:matrix (aget cells i))]
                            (if (pos? (alength ^"[[D" m))
                              (aget ^"[[D" m 0)
                              (recur (inc i))))
                          (throw (ex-info "all cells empty" {}))))]
            (alength ^doubles probe))]
  (dotimes [_ 2000]
    (knn/classify (vec (repeatedly dim #(rand)))
                  search-index k threshold nprobe)))
