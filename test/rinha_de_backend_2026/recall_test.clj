(ns rinha-de-backend-2026.recall-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026.fraud-score :as fraud-score]
   [rinha-de-backend-2026.knn :as knn])
  (:import [java.util PriorityQueue Comparator Random]))

;; ---------------------------------------------------------------------------
;; Self-contained brute-force oracle. Intentionally a separate implementation
;; from knn/classify so this test is a real apples-to-apples comparison.
;; ---------------------------------------------------------------------------

(defn- sq-dist
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

(defn- flatten-index
  "Materialize the IVF search-index into flat [^\"[[D\" matrix, ^bytes labels]
   for brute-force scanning."
  [search-index]
  (let [^objects cells (:cells search-index)
        n              (alength cells)
        total          (loop [i 0 s 0]
                         (if (< i n)
                           (recur (inc i)
                                  (+ s (alength ^"[[D" (:matrix (aget cells i)))))
                           s))
        flat-m         (make-array (Class/forName "[D") total)
        flat-l         (byte-array total)]
    (loop [ci 0 off 0]
      (if (< ci n)
        (let [cell    (aget cells ci)
              ^"[[D" m (:matrix cell)
              ^bytes l (:labels cell)
              sz      (alength m)]
          (dotimes [p sz]
            (aset ^objects flat-m (+ off p) (aget m p))
            (aset-byte flat-l (+ off p) (aget l p)))
          (recur (inc ci) (+ off sz)))
        [flat-m flat-l]))))

(defn- brute-classify
  [vector [^"[[D" flat-m ^bytes flat-l] k threshold]
  (let [query (double-array vector)
        n     (alength flat-m)
        heap  (PriorityQueue. (int k) worst-first)]
    (dotimes [i n]
      (let [d (sq-dist query (aget flat-m i))]
        (if (< (.size heap) k)
          (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i))))
          (let [^doubles worst (.peek heap)]
            (when (< d (aget worst 0))
              (.poll heap)
              (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i)))))))))
    (let [fraud-count (reduce (fn [acc ^doubles entry]
                                (let [idx (int (aget entry 1))]
                                  (if (== 1 (aget flat-l idx))
                                    (inc acc) acc)))
                              0
                              heap)
          fraud-score (double (/ fraud-count k))]
      {:approved    (< fraud-score threshold)
       :fraud_score fraud-score})))

;; ---------------------------------------------------------------------------
;; Recall gate
;; ---------------------------------------------------------------------------

(def ^:private recall-target 0.97)
(def ^:private num-queries   1000)
(def ^:private dim           14)
(def ^:private rng-seed      42)

(defn- synthetic-queries [^long n ^long dim ^long seed]
  (let [rng (Random. seed)]
    (vec (for [_ (range n)]
           (vec (for [_ (range dim)]
                  (.nextDouble rng)))))))

(deftest ivf-recall-matches-brute-force
  (testing "IVF fraud_score agrees with brute-force on ≥ 97% of queries"
    (let [idx          fraud-score/search-index
          oracle-data  (flatten-index idx)
          queries      (synthetic-queries num-queries dim rng-seed)
          agreements   (reduce (fn [acc q]
                                 (let [ivf-r   (knn/classify q idx
                                                             fraud-score/k
                                                             fraud-score/threshold
                                                             fraud-score/nprobe)
                                       brute-r (brute-classify q oracle-data
                                                               fraud-score/k
                                                               fraud-score/threshold)]
                                   (if (== (:fraud_score ivf-r)
                                           (:fraud_score brute-r))
                                     (inc acc) acc)))
                               0
                               queries)
          recall       (/ (double agreements) num-queries)]
      (println (format "IVF recall vs brute-force: %d/%d = %.3f"
                       agreements num-queries recall))
      (is (>= recall recall-target)
          (format "Recall %.3f below target %.3f — consider raising nprobe."
                  recall recall-target)))))
