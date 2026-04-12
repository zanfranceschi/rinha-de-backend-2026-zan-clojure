(ns rinha-de-backend-2026-exemplo.knn
  (:import [smile.math.distance Distance]
           [smile.neighbor LinearSearch]))

(def cosine-dist
  "Cosine distance as a Smile Distance implementation."
  (reify Distance
    (d [_ a b]
      (let [^doubles a a
            ^doubles b b
            n          (alength a)]
        (loop [i   (int 0)
               dot 0.0
               ma  0.0
               mb  0.0]
          (if (< i n)
            (let [ai (aget a i)
                  bi (aget b i)]
              (recur (unchecked-inc-int i)
                     (+ dot (* ai bi))
                     (+ ma (* ai ai))
                     (+ mb (* bi bi))))
            (let [mag-a (Math/sqrt ma)
                  mag-b (Math/sqrt mb)]
              (if (or (== mag-a 0.0) (== mag-b 0.0))
                1.0
                (- 1.0 (/ dot (* mag-a mag-b)))))))))))

(defn cosine-distance
  "Cosine distance between two vectors."
  [a b]
  (.d cosine-dist (double-array a) (double-array b)))

(defn build-search
  "Build a LinearSearch index from reference vectors.
   refs: seq of {:vector [...] :label \"fraud\"|\"legit\"}
   Returns {:search LinearSearch :labels [string]}"
  [refs]
  (let [labels (mapv :label refs)
        matrix (into-array (Class/forName "[D")
                           (map #(double-array (:vector %)) refs))]
    {:search (LinearSearch/of matrix cosine-dist)
     :labels labels}))

(defn classify
  "Classify a vector using KNN.
   Returns {:approved bool :fraud_score float}.
   - search-index: result of build-search
   - k: number of neighbors
   - threshold: fraud_score >= threshold means not approved"
  [vector search-index k threshold]
  (let [query       (double-array vector)
        neighbors   (.search (:search search-index) query k)
        labels      (:labels search-index)
        fraud-count (count (filter #(= "fraud" (nth labels (.index %))) neighbors))
        fraud-score (double (/ fraud-count k))]
    {:approved    (< fraud-score threshold)
     :fraud_score fraud-score}))
