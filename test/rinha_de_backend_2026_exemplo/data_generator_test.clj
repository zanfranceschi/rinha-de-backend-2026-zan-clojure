(ns rinha-de-backend-2026-exemplo.data-generator-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026-exemplo.data-generator :as gen]
   [rinha-de-backend-2026-exemplo.authorization :as auth]))

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

(deftest base-payload-test
  (testing "base-payload produces a valid clean request structure"
    (let [rng     (java.util.Random. 42)
          payload (gen/base-payload rng 0)]
      (is (map? (:transaction payload)))
      (is (string? (-> payload :transaction :id)))
      (is (number? (-> payload :transaction :amount)))
      (is (= "BRL" (-> payload :transaction :currency)))
      (is (map? (:environment payload)))
      (is (map? (:context payload)))
      (is (nil? (:last_transaction payload))))))

(deftest gen-clean-test
  (testing "gen-clean produces an approved transaction"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-clean rng 0)
          result  (auth/authorize payload)]
      (is (true? (:approved result))))))

(deftest gen-restricted-area-test
  (testing "gen-restricted-area triggers restricted_area rule"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-restricted-area rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"restricted_area"} (:rules_violated result))))))

(deftest gen-anomalous-interval-test
  (testing "gen-anomalous-interval triggers anomalous_interval rule"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-anomalous-interval rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"anomalous_interval"} (:rules_violated result))))))

(deftest gen-anomalous-travel-speed-test
  (testing "gen-anomalous-travel-speed triggers anomalous_travel_speed rule"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-anomalous-travel-speed rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"anomalous_travel_speed"} (:rules_violated result))))))
