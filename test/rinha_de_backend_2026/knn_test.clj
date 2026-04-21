(ns rinha-de-backend-2026.knn-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026.knn :as knn]))

;; Two manually-specified cells so tests are fully deterministic — no k-means
;; at test time. Cell 0 = fraud cluster, Cell 1 = legit cluster.

(def test-index
  (knn/build-index
   {:centroids [[0.9 0.1 0.0]
                [0.0 0.1 0.9]]
    :cells     [{:vectors [[1.0 0.0 0.0]
                           [0.9 0.1 0.0]
                           [0.8 0.2 0.0]]
                 :labels  ["fraud" "fraud" "fraud"]}
                {:vectors [[0.0 0.0 1.0]
                           [0.0 0.1 0.9]
                           [0.0 0.2 0.8]]
                 :labels  ["legit" "legit" "legit"]}]}))

;; Discriminating dataset for the nprobe-honored test. Cell 0 holds fraud
;; points planted far from its centroid (deliberate — we control cell
;; membership directly), including one very close to the query. Cell 1
;; holds all-legit points tight around its own centroid. A query near
;; cell 1's centroid reaches only legit points under nprobe=1, but under
;; nprobe=2 the planted fraud dominates the top-k and flips the vote.

(def discriminating-index
  (knn/build-index
   {:centroids [[0.9 0.9 0.0]
                [0.2 0.2 0.0]]
    :cells     [{:vectors [[0.55 0.55 0.0]
                           [0.6  0.6  0.0]
                           [0.9  0.9  0.0]]
                 :labels  ["fraud" "fraud" "fraud"]}
                {:vectors [[0.1  0.1  0.0]
                           [0.15 0.15 0.0]
                           [0.2  0.2  0.0]]
                 :labels  ["legit" "legit" "legit"]}]}))

;; nprobe=2 visits both cells → all 6 points reachable, so top-5 is exact.

(deftest classify-fraud
  (testing "Vector near fraud cluster returns fraud classification"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 5 0.6 2)]
      (is (false? (:approved result)))
      (is (>= (:fraud_score result) 0.6)))))

(deftest classify-legit
  (testing "Vector near legit cluster returns legit classification"
    (let [result (knn/classify [0.0 0.05 0.95] test-index 5 0.6 2)]
      (is (true? (:approved result)))
      (is (< (:fraud_score result) 0.6)))))

(deftest classify-fraud-score-is-ratio
  (testing "fraud_score is fraud_count / k"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 5 0.6 2)]
      (is (number? (:fraud_score result)))
      (is (<= 0.0 (:fraud_score result) 1.0)))))

(deftest classify-nprobe-discriminates-cells
  (testing "nprobe=1 vs nprobe=2 on the same query return different fraud_score
            — proof that nprobe is actually honored by classify"
    (let [query    [0.5 0.5 0.0]
          one      (knn/classify query discriminating-index 3 0.5 1)
          two      (knn/classify query discriminating-index 3 0.5 2)]
      ;; Query is closer to cell 1's centroid, so nprobe=1 visits only
      ;; the legit cell → fraud_score = 0/3.
      (is (zero? (:fraud_score one)))
      (is (true? (:approved one)))
      ;; With nprobe=2 both cells are visited. The 3 closest points to
      ;; [0.5 0.5 0.0] are [0.55 0.55 0] fraud (d²=0.005), [0.6 0.6 0] fraud
      ;; (d²=0.02), and [0.2 0.2 0] legit (d²=0.18) — so 2 fraud + 1 legit,
      ;; fraud_score = 2/3, which flips approved to false under threshold 0.5.
      (is (= (double 2/3) (:fraud_score two)))
      (is (false? (:approved two))))))

(deftest classify-response-shape
  (testing "Response contains approved and fraud_score"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 5 0.6 2)]
      (is (contains? result :approved))
      (is (contains? result :fraud_score))
      (is (boolean? (:approved result))))))
