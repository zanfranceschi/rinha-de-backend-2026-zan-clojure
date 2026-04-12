(ns rinha-de-backend-2026-exemplo.knn
  (:import [smile.math MathEx]
           [smile.math.distance Distance EuclideanDistance]
           [smile.neighbor LinearSearch]))

(def cosine-dist
  "Cosine distance via Smile's MathEx/cosine similarity."
  (reify Distance
    (d [_ a b]
      (- 1.0 (MathEx/cosine ^doubles a ^doubles b)))))

(def euclidean-dist (EuclideanDistance.))

(defn build-search
  "Build a LinearSearch index from reference vectors.
   refs: seq of {:vector [...] :label \"fraud\"|\"legit\"}
   dist: a Smile Distance implementation (default: cosine-dist)
   Returns {:search LinearSearch :labels [string]}"
  ([refs] (build-search refs cosine-dist))
  ([refs dist]
   (let [labels (mapv :label refs)
         matrix (into-array (Class/forName "[D")
                            (map #(double-array (:vector %)) refs))]
     {:search (LinearSearch/of matrix dist)
      :labels labels})))

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
