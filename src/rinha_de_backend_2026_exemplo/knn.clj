(ns rinha-de-backend-2026-exemplo.knn
  (:import [java.util PriorityQueue Comparator]))

(defn- squared-distance
  ^double [^doubles x ^doubles y]
  (let [n (alength x)]
    (loop [i 0 sum 0.0]
      (if (< i n)
        (let [d (- (aget x i) (aget y i))]
          (recur (inc i) (+ sum (* d d))))
        sum))))

(defn build-search
  "Build a search index from reference vectors.
   refs: seq of {:vector [...] :label \"fraud\"|\"legit\"}
   Returns {:matrix [[D :labels [string]}"
  [refs]
  (let [labels (mapv :label refs)
        matrix (into-array (Class/forName "[D")
                           (map #(let [v (:vector %)]
                                   (if (instance? (Class/forName "[D") v)
                                     v
                                     (double-array v)))
                                refs))]
    {:matrix matrix
     :labels labels}))

(def ^:private ^Comparator worst-first
  (reify Comparator
    (compare [_ a b]
      (Double/compare (aget ^doubles b 0) (aget ^doubles a 0)))))

(defn classify
  "Classify a vector using KNN.
   Returns {:approved bool :fraud_score float}.
   - search-index: result of build-search
   - k: number of neighbors
   - threshold: fraud_score >= threshold means not approved"
  [vector search-index k threshold]
  (let [query         (double-array vector)
        ^"[[D" matrix (:matrix search-index)
        labels        (:labels search-index)
        n             (alength matrix)
        heap          (PriorityQueue. (int k) worst-first)]
    (dotimes [i n]
      (let [d (squared-distance query (aget matrix i))]
        (if (< (.size heap) k)
          (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i))))
          (let [^doubles worst (.peek heap)]
            (when (< d (aget worst 0))
              (.poll heap)
              (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i)))))))))
    (let [fraud-count (reduce (fn [acc ^doubles entry]
                                (if (= "fraud" (nth labels (int (aget entry 1))))
                                  (inc acc)
                                  acc))
                              0
                              heap)
          fraud-score (double (/ fraud-count k))]
      {:approved    (< fraud-score threshold)
       :fraud_score fraud-score})))
