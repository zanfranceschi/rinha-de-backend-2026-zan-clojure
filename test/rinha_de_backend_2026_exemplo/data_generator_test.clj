(ns rinha-de-backend-2026-exemplo.data-generator-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.data.json :as json]
   [rinha-de-backend-2026-exemplo.data-generator :as gen]
   [rinha-de-backend-2026-exemplo.knn :as knn]))

;; PRNG helpers

(deftest rand-double-in-range-test
  (testing "rand-double returns value within [min, max]"
    (let [rng (java.util.Random. 42)
          v   (gen/rand-double rng 10.0 20.0)]
      (is (>= v 10.0))
      (is (< v 20.0)))))

(deftest rand-int-in-range-test
  (testing "rand-int-range returns value within [min, max)"
    (let [rng (java.util.Random. 42)
          v   (gen/rand-int-range rng 5 10)]
      (is (>= v 5))
      (is (< v 10)))))

(deftest rand-nth-seq-test
  (testing "rand-nth-seq picks an element from the collection"
    (let [rng  (java.util.Random. 42)
          coll [:a :b :c :d]
          v    (gen/rand-nth-seq rng coll)]
      (is (contains? (set coll) v)))))

;; Request generation

(deftest generate-request-produces-valid-payload
  (testing "generate-request returns a map with all required fields"
    (let [rng (java.util.Random. 42)
          req (gen/generate-request rng :legit)]
      (is (string? (:id req)))
      (is (map? (:transaction req)))
      (is (number? (-> req :transaction :amount)))
      (is (number? (-> req :transaction :installments)))
      (is (string? (-> req :transaction :requested_at)))
      (is (map? (:customer req)))
      (is (number? (-> req :customer :avg_amount)))
      (is (number? (-> req :customer :tx_count_24h)))
      (is (vector? (-> req :customer :known_merchants)))
      (is (map? (:merchant req)))
      (is (string? (-> req :merchant :id)))
      (is (string? (-> req :merchant :mcc)))
      (is (number? (-> req :merchant :avg_amount)))
      (is (map? (:terminal req)))
      (is (boolean? (-> req :terminal :is_online)))
      (is (boolean? (-> req :terminal :card_present)))
      (is (number? (-> req :terminal :km_from_home))))))

(deftest generate-request-legit-coherence
  (testing "Legit profile produces coherent low-risk values"
    (let [rng (java.util.Random. 42)
          req (gen/generate-request rng :legit)]
      (is (<= (-> req :transaction :amount) 2000.0))
      (is (<= (-> req :terminal :km_from_home) 100.0)))))

(deftest generate-request-fraud-coherence
  (testing "Fraud profile produces coherent high-risk values"
    (let [rng (java.util.Random. 42)
          req (gen/generate-request rng :fraud)]
      (is (>= (-> req :transaction :amount) 1000.0))
      (is (>= (-> req :terminal :km_from_home) 100.0)))))

(deftest generate-request-online-coherence
  (testing "Online transactions have card_present=false"
    (let [rng      (java.util.Random. 99)
          requests (repeatedly 50 #(gen/generate-request rng :fraud))
          online   (filter #(-> % :terminal :is_online) requests)]
      (doseq [req online]
        (is (false? (-> req :terminal :card_present)))))))

(deftest generate-request-null-last-transaction
  (testing "Some generated requests have null last_transaction"
    (let [rng      (java.util.Random. 42)
          requests (repeatedly 50 #(gen/generate-request rng :legit))
          nulls    (filter #(nil? (:last_transaction %)) requests)]
      (is (pos? (count nulls))))))

;; Reference dataset generation

(deftest generate-reference-dataset-shape
  (testing "Reference dataset has correct shape"
    (let [dataset (gen/generate-reference-dataset 100)]
      (is (= 100 (count dataset)))
      (doseq [entry dataset]
        (is (vector? (:vector entry)))
        (is (= 14 (count (:vector entry))))
        (is (#{"fraud" "legit"} (:label entry)))))))

(deftest generate-reference-dataset-values-in-range
  (testing "All vector values are 0..1 or -1 sentinel"
    (let [dataset (gen/generate-reference-dataset 100)]
      (doseq [entry dataset
              val   (:vector entry)]
        (is (or (= -1.0 val)
                (and (<= 0.0 val) (<= val 1.0))))))))

(deftest generate-reference-dataset-has-both-labels
  (testing "Dataset contains both fraud and legit labels"
    (let [dataset (gen/generate-reference-dataset 100)
          labels  (set (map :label dataset))]
      (is (contains? labels "fraud"))
      (is (contains? labels "legit")))))

(deftest generate-reference-dataset-deterministic
  (testing "Same size produces same dataset (seed 42)"
    (let [ds1 (gen/generate-reference-dataset 50)
          ds2 (gen/generate-reference-dataset 50)]
      (is (= ds1 ds2)))))

;; Test payload generation

(def test-refs (gen/generate-reference-dataset 50))
(def test-search-index (knn/build-search test-refs))

(deftest generate-test-payloads-shape
  (testing "Test payloads have request, info.vector, info.expected_response"
    (let [payloads (gen/generate-test-payloads 50 test-search-index)]
      (is (= 50 (count payloads)))
      (doseq [p payloads]
        (is (map? (:request p)))
        (is (map? (:info p)))
        (is (vector? (-> p :info :vector)))
        (is (= 14 (count (-> p :info :vector))))
        (is (map? (-> p :info :expected_response)))
        (is (contains? (-> p :info :expected_response) :approved))
        (is (contains? (-> p :info :expected_response) :fraud_score))))))

(deftest generate-test-payloads-deterministic
  (testing "Same size produces same payloads (different seed from references)"
    (let [p1 (gen/generate-test-payloads 50 test-search-index)
          p2 (gen/generate-test-payloads 50 test-search-index)]
      (is (= p1 p2)))))

;; generate-all!

(deftest generate-all-test
  (testing "generate-all! writes both files with correct content"
    (let [refs-file     (java.io.File/createTempFile "test-refs" ".json")
          payloads-file (java.io.File/createTempFile "test-payloads" ".json")
          refs-path     (.getAbsolutePath refs-file)
          payloads-path (.getAbsolutePath payloads-file)]
      (try
        (gen/generate-all! 20 20 refs-path payloads-path)
        (let [refs     (json/read-str (slurp refs-path) :key-fn keyword)
              output   (json/read-str (slurp payloads-path) :key-fn keyword)
              stats    (:stats output)
              entries  (:entries output)]
          (is (= 20 (count refs)))
          (is (= 20 (count entries)))
          (is (= 20 (:total stats)))
          (is (= (:total stats) (+ (:fraud_count stats) (:legit_count stats)))))
        (finally
          (.delete refs-file)
          (.delete payloads-file))))))
