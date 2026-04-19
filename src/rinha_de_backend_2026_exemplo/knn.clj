(ns rinha-de-backend-2026-exemplo.knn
  (:import [smile.math.distance EuclideanDistance]
           [smile.neighbor LinearSearch]))

(def euclidean-dist (EuclideanDistance.))

(defn build-search
  "Build a LinearSearch index from reference vectors.
   refs: seq of {:vector [...] :label \"fraud\"|\"legit\"}
   Returns {:search LinearSearch :labels [string]}"
  [refs]
  (let [labels (mapv :label refs)
        matrix (into-array (Class/forName "[D")
                           (map #(let [v (:vector %)]
                                   (if (instance? (Class/forName "[D") v)
                                     v
                                     (double-array v)))
                                refs))]
    {:search (LinearSearch/of matrix euclidean-dist)
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
