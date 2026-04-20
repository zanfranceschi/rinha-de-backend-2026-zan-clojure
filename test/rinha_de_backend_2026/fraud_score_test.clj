(ns rinha-de-backend-2026.fraud-score-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026.fraud-score :as fraud-score]))

(defn legit-request
  "A request that should be classified as legit:
   small amount, daytime, near home, known merchant, low MCC risk."
  []
  {:id          "tx-legit"
   :transaction {:amount       50.0
                 :installments 1
                 :requested_at "2026-03-16T14:00:00Z"}
   :customer    {:avg_amount      60.0
                 :tx_count_24h    2
                 :known_merchants ["MERC-001"]}
   :merchant    {:id         "MERC-001"
                 :mcc        "5411"
                 :avg_amount 45.0}
   :terminal    {:is_online    false
                 :card_present true
                 :km_from_home 2.0}
   :last_transaction {:timestamp       "2026-03-16T12:00:00Z"
                      :km_from_current 1.5}})

(defn fraud-request
  "A request that should be classified as fraud:
   high amount, nighttime, far from home, new merchant, high MCC risk."
  []
  {:id          "tx-fraud"
   :transaction {:amount       9500.0
                 :installments 12
                 :requested_at "2026-03-14T03:00:00Z"}
   :customer    {:avg_amount      200.0
                 :tx_count_24h    15
                 :known_merchants ["MERC-001"]}
   :merchant    {:id         "MERC-999"
                 :mcc        "7995"
                 :avg_amount 50.0}
   :terminal    {:is_online    true
                 :card_present false
                 :km_from_home 900.0}
   :last_transaction {:timestamp       "2026-03-14T02:55:00Z"
                      :km_from_current 800.0}})

(deftest authorize-legit-transaction
  (testing "Legit-looking transaction is approved"
    (let [result (fraud-score/score (legit-request))]
      (is (true? (:approved result)))
      (is (< (:fraud_score result) 0.6)))))

(deftest authorize-fraud-transaction
  (testing "Fraud-looking transaction is not approved"
    (let [result (fraud-score/score (fraud-request))]
      (is (false? (:approved result)))
      (is (>= (:fraud_score result) 0.6)))))

(deftest authorize-null-last-transaction
  (testing "Request with null last_transaction still works"
    (let [req    (assoc (legit-request) :last_transaction nil)
          result (fraud-score/score req)]
      (is (boolean? (:approved result)))
      (is (number? (:fraud_score result))))))

(deftest authorize-response-shape
  (testing "Response contains approved and fraud_score"
    (let [result (fraud-score/score (legit-request))]
      (is (contains? result :approved))
      (is (contains? result :fraud_score))
      (is (boolean? (:approved result)))
      (is (<= 0.0 (:fraud_score result) 1.0)))))
