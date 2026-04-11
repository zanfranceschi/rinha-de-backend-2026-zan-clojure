(ns rinha-de-backend-2026-exemplo.knn-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026-exemplo.knn :as knn]))

;; cosine-distance tests

(deftest cosine-distance-identical-vectors
  (testing "Identical vectors have distance 0.0"
    (is (< (knn/cosine-distance [1.0 0.0 0.0] [1.0 0.0 0.0]) 1e-9))))

(deftest cosine-distance-orthogonal-vectors
  (testing "Orthogonal vectors have distance 1.0"
    (is (< (Math/abs (- 1.0 (knn/cosine-distance [1.0 0.0] [0.0 1.0]))) 1e-9))))

(deftest cosine-distance-opposite-vectors
  (testing "Opposite vectors have distance 2.0"
    (is (< (Math/abs (- 2.0 (knn/cosine-distance [1.0 0.0] [-1.0 0.0]))) 1e-9))))

(deftest cosine-distance-similar-vectors
  (testing "Similar vectors have small distance"
    (let [d (knn/cosine-distance [0.9 0.8 0.7] [0.85 0.75 0.65])]
      (is (< d 0.01)))))

;; classify tests

(def test-references
  [{:vector [1.0 0.0 0.0] :label "fraud"}
   {:vector [0.9 0.1 0.0] :label "fraud"}
   {:vector [0.8 0.2 0.0] :label "fraud"}
   {:vector [0.0 0.0 1.0] :label "legit"}
   {:vector [0.0 0.1 0.9] :label "legit"}
   {:vector [0.0 0.2 0.8] :label "legit"}])

(deftest classify-fraud
  (testing "Vector near fraud cluster returns fraud classification"
    (let [result (knn/classify [0.95 0.05 0.0] test-references 5 0.6)]
      (is (false? (:approved result)))
      (is (>= (:fraud_score result) 0.6)))))

(deftest classify-legit
  (testing "Vector near legit cluster returns legit classification"
    (let [result (knn/classify [0.0 0.05 0.95] test-references 5 0.6)]
      (is (true? (:approved result)))
      (is (< (:fraud_score result) 0.6)))))

(deftest classify-fraud-score-is-ratio
  (testing "fraud_score is fraud_count / K"
    (let [result (knn/classify [0.95 0.05 0.0] test-references 5 0.6)]
      (is (number? (:fraud_score result)))
      (is (<= 0.0 (:fraud_score result) 1.0)))))

(deftest classify-exact-threshold
  (testing "fraud_score exactly at threshold is not approved"
    (let [result (knn/classify [0.95 0.05 0.0] test-references 5 0.6)]
      (if (>= (:fraud_score result) 0.6)
        (is (false? (:approved result)))
        (is (true? (:approved result)))))))
