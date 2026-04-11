(ns rinha-de-backend-2026-exemplo.knn)

(defn cosine-distance
  "Cosine distance between two vectors: 1 - cosine_similarity.
   Returns 0.0 for identical directions, 1.0 for orthogonal, 2.0 for opposite."
  [a b]
  (let [dot    (reduce + (map * a b))
        mag-a  (Math/sqrt (reduce + (map #(* % %) a)))
        mag-b  (Math/sqrt (reduce + (map #(* % %) b)))]
    (if (or (zero? mag-a) (zero? mag-b))
      1.0
      (- 1.0 (/ dot (* mag-a mag-b))))))

(defn classify
  "Classify a vector using KNN.
   Returns {:approved bool :fraud_score float}.
   - references: seq of {:vector [...] :label \"fraud\"|\"legit\"}
   - k: number of neighbors
   - threshold: fraud_score >= threshold means not approved"
  [vector references k threshold]
  (let [distances   (map (fn [ref]
                           {:label    (:label ref)
                            :distance (cosine-distance vector (:vector ref))})
                         references)
        nearest     (->> distances
                         (sort-by :distance)
                         (take k))
        fraud-count (count (filter #(= "fraud" (:label %)) nearest))
        fraud-score (double (/ fraud-count k))]
    {:approved    (< fraud-score threshold)
     :fraud_score fraud-score}))
