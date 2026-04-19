(ns rinha-de-backend-2026-exemplo.knn)

(defn- euclidean-distance
  ^double [^doubles x ^doubles y]
  (let [n (alength x)]
    (loop [i 0 sum 0.0]
      (if (< i n)
        (let [d (- (aget x i) (aget y i))]
          (recur (inc i) (+ sum (* d d))))
        (Math/sqrt sum)))))

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
        top-k         (->> (range n)
                           (map (fn [i] [i (euclidean-distance query (aget matrix i))]))
                           (sort-by second)
                           (take k))
        fraud-count   (count (filter #(= "fraud" (nth labels (first %))) top-k))
        fraud-score   (double (/ fraud-count k))]
    {:approved    (< fraud-score threshold)
     :fraud_score fraud-score}))
