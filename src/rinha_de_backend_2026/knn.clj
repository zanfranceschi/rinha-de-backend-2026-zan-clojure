(ns rinha-de-backend-2026.knn
  (:import [java.util PriorityQueue Comparator]))

;; Compile-time ceiling for points per cell. Heap entries pack
;; (cellIdx * MAX-CELL-SIZE + pointIdx) into the [1] slot of a double[2],
;; mirroring the pre-IVF packing trick. 65536 comfortably fits n/nlist at
;; n=100k/nlist=256 and stays well under 2^53.
(def ^:const MAX-CELL-SIZE 65536)

(defn- squared-distance
  ^double [^doubles x ^doubles y]
  (let [n (alength x)]
    (loop [i 0 sum 0.0]
      (if (< i n)
        (let [d (- (aget x i) (aget y i))]
          (recur (inc i) (+ sum (* d d))))
        sum))))

(def ^:private ^Comparator worst-first
  (reify Comparator
    (compare [_ a b]
      (Double/compare (aget ^doubles b 0) (aget ^doubles a 0)))))

(defn- ->doubles ^doubles [v]
  (if (instance? (Class/forName "[D") v)
    v
    (double-array v)))

(defn- ->matrix ^"[[D" [vectors]
  (into-array (Class/forName "[D") (map ->doubles vectors)))

(defn- ->byte-labels ^bytes [labels]
  (let [n   (count labels)
        out (byte-array n)]
    (dotimes [i n]
      (aset-byte out i (if (= "fraud" (nth labels i)) 1 0)))
    out))

(defn build-index
  "Build an IVF search index from pre-clustered data.
   Input shape:
     {:centroids [[d1..dD] * nlist]
      :cells     [{:vectors [[...]...], :labels [\"fraud\"|\"legit\"...]} * nlist]}
   Output is opaque to callers. Cells may be empty (size 0) and cells.length
   must equal centroids.length. Any cell exceeding MAX-CELL-SIZE throws."
  [{:keys [centroids cells]}]
  (let [nlist        (count centroids)
        _            (assert (= nlist (count cells))
                             "centroids/cells length mismatch")
        centroids-m  (->matrix centroids)
        cell-objs    (object-array nlist)]
    (dotimes [i nlist]
      (let [{:keys [vectors labels]} (nth cells i)
            sz (count vectors)]
        (when (> sz MAX-CELL-SIZE)
          (throw (ex-info "Cell exceeds MAX-CELL-SIZE"
                          {:cell i :size sz :max MAX-CELL-SIZE})))
        (assert (= sz (count labels)) "vectors/labels length mismatch")
        (aset cell-objs i
              {:matrix (->matrix vectors)
               :labels (->byte-labels labels)})))
    {:centroids centroids-m
     :cells     cell-objs}))

(defn- top-nprobe-cell-indices
  "Return an int-array of the nprobe nearest centroid indices to query."
  ^ints [^doubles query ^"[[D" centroids ^long nprobe]
  (let [n    (alength centroids)
        np   (min nprobe n)
        heap (PriorityQueue. (int np) worst-first)]
    (dotimes [i n]
      (let [d (squared-distance query (aget centroids i))]
        (if (< (.size heap) np)
          (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i))))
          (let [^doubles worst (.peek heap)]
            (when (< d (aget worst 0))
              (.poll heap)
              (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i)))))))))
    (let [out (int-array (.size heap))
          it  (.iterator heap)]
      (loop [i 0]
        (if (.hasNext it)
          (let [^doubles e (.next it)]
            (aset out i (int (aget e 1)))
            (recur (inc i)))
          out)))))

(defn- scan-cell!
  "Push points from the given cell into the top-k heap. pack-base is
   cellIdx * MAX-CELL-SIZE; the second heap slot stores pack-base + pointIdx."
  [^doubles query ^"[[D" matrix pack-base ^PriorityQueue heap k]
  (let [n         (alength matrix)
        pack-base (long pack-base)
        k         (long k)]
    (dotimes [p n]
      (let [d (squared-distance query (aget matrix p))]
        (if (< (.size heap) k)
          (.offer heap (doto (double-array 2)
                         (aset 0 d)
                         (aset 1 (double (+ pack-base p)))))
          (let [^doubles worst (.peek heap)]
            (when (< d (aget worst 0))
              (.poll heap)
              (.offer heap (doto (double-array 2)
                             (aset 0 d)
                             (aset 1 (double (+ pack-base p))))))))))))

(defn classify
  "Classify a vector using IVF KNN.
   - search-index: result of build-index
   - k:            top-k neighbors for the vote
   - threshold:    fraud_score >= threshold => not approved
   - nprobe:       number of nearest cells to scan

   Returns {:approved bool :fraud_score double}."
  [vector search-index k threshold nprobe]
  (let [query         (->doubles vector)
        ^"[[D" centroids (:centroids search-index)
        ^objects cells (:cells search-index)
        ^ints top-cells (top-nprobe-cell-indices query centroids (long nprobe))
        heap          (PriorityQueue. (int k) worst-first)]
    (dotimes [i (alength top-cells)]
      (let [ci       (aget top-cells i)
            cell     (aget cells ci)
            matrix   ^"[[D" (:matrix cell)
            pack-base (* (long ci) (long MAX-CELL-SIZE))]
        (scan-cell! query matrix pack-base heap (long k))))
    (let [fraud-count (reduce (fn [acc ^doubles entry]
                                (let [packed (long (aget entry 1))
                                      ci     (quot packed MAX-CELL-SIZE)
                                      pi     (rem  packed MAX-CELL-SIZE)
                                      ^bytes labels (:labels (aget cells (int ci)))]
                                  (if (== 1 (aget labels (int pi)))
                                    (inc acc)
                                    acc)))
                              0
                              heap)
          fraud-score (double (/ fraud-count k))]
      {:approved    (< fraud-score threshold)
       :fraud_score fraud-score})))
