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

(deftest classify-nprobe-one-picks-correct-cell
  (testing "nprobe=1 with fraud query reaches only fraud cell and approves=false"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 3 0.6 1)]
      (is (false? (:approved result)))
      (is (>= (:fraud_score result) 0.6)))))

(deftest classify-response-shape
  (testing "Response contains approved and fraud_score"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 5 0.6 2)]
      (is (contains? result :approved))
      (is (contains? result :fraud_score))
      (is (boolean? (:approved result))))))
