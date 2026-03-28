(ns rinha-de-backend-2026-exemplo.authorization-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026-exemplo.authorization :as auth]))

;; ---------------------------------------------------------------------------
;; Helper: base payload builder
;; ---------------------------------------------------------------------------

(defn base-payload
  "Returns a clean payload that should pass all rules.
   Coordinates at 0,0 (middle of the ocean) - outside any restricted zone.
   Merchant and sale MCC both 5411 (grocery) - compatible relation.
   Amount 100 - within grocery limits (max 5000)."
  []
  {:transaction    {:id "tx-1" :amount 100.0 :currency "BRL" :installments 1
                    :timestamp "2026-03-27T14:30:00Z"}
   :environment    {:merchant {:id "m1" :name "Store" :mcc "5411"}
                    :terminal {:id "t1" :latitude 0.0 :longitude 0.0}}
   :context        {:sale_mcc "5411"}
   :last_transaction nil})

;; ---------------------------------------------------------------------------
;; 1. in-restricted-area?
;; ---------------------------------------------------------------------------

(deftest in-restricted-area?-flagged
  (testing "Terminal inside a Sao Paulo restricted polygon returns true"
    (let [payload (-> (base-payload)
                      (assoc-in [:environment :terminal :latitude] -23.6890)
                      (assoc-in [:environment :terminal :longitude] -46.8200))]
      (is (true? (auth/in-restricted-area? payload))))))

(deftest in-restricted-area?-passing
  (testing "Terminal at 0,0 (ocean) returns false"
    (is (false? (auth/in-restricted-area? (base-payload))))))

(deftest in-restricted-area?-missing-coords
  (testing "Terminal with nil coordinates returns false"
    (let [payload (-> (base-payload)
                      (assoc-in [:environment :terminal :latitude] nil)
                      (assoc-in [:environment :terminal :longitude] nil))]
      (is (false? (auth/in-restricted-area? payload))))))

;; ---------------------------------------------------------------------------
;; 2. anomalous-travel-speed?
;; ---------------------------------------------------------------------------

(deftest anomalous-travel-speed?-flagged
  (testing "Impossible speed (far apart, seconds apart) returns true"
    ;; Current terminal at 0,0 ; last transaction at -23.55,-46.63 (Sao Paulo)
    ;; 10 seconds apart -> ~10000km in 10s -> absurd speed
    (let [payload (-> (base-payload)
                      (assoc :last_transaction
                             {:timestamp "2026-03-27T14:29:50Z"
                              :terminal  {:latitude -23.55 :longitude -46.63}}))]
      (is (true? (auth/anomalous-travel-speed? payload))))))

(deftest anomalous-travel-speed?-passing
  (testing "Same location, reasonable time returns false"
    (let [payload (-> (base-payload)
                      (assoc :last_transaction
                             {:timestamp "2026-03-27T12:00:00Z"
                              :terminal  {:latitude 0.001 :longitude 0.001}}))]
      (is (false? (auth/anomalous-travel-speed? payload))))))

(deftest anomalous-travel-speed?-no-last-tx
  (testing "No last_transaction returns false"
    (is (false? (auth/anomalous-travel-speed? (base-payload))))))

;; ---------------------------------------------------------------------------
;; 3. anomalous-interval?
;; ---------------------------------------------------------------------------

(deftest anomalous-interval?-flagged
  (testing "Transactions 2 minutes apart returns true"
    (let [payload (-> (base-payload)
                      (assoc :last_transaction
                             {:timestamp "2026-03-27T14:28:00Z"
                              :terminal  {:latitude 0.0 :longitude 0.0}}))]
      (is (true? (auth/anomalous-interval? payload))))))

(deftest anomalous-interval?-passing
  (testing "Transactions 10 minutes apart returns false"
    (let [payload (-> (base-payload)
                      (assoc :last_transaction
                             {:timestamp "2026-03-27T14:20:00Z"
                              :terminal  {:latitude 0.0 :longitude 0.0}}))]
      (is (false? (auth/anomalous-interval? payload))))))

(deftest anomalous-interval?-no-last-tx
  (testing "No last_transaction returns false"
    (is (false? (auth/anomalous-interval? (base-payload))))))

;; ---------------------------------------------------------------------------
;; 4. mcc-amount-restricted?
;; ---------------------------------------------------------------------------

(deftest mcc-amount-restricted?-flagged-max
  (testing "Amount exceeds max for MCC 7801 (online casinos, max 100) returns true"
    (let [payload (-> (base-payload)
                      (assoc-in [:transaction :amount] 150.0)
                      (assoc-in [:context :sale_mcc] "7801"))]
      (is (true? (auth/mcc-amount-restricted? payload))))))

(deftest mcc-amount-restricted?-flagged-min
  (testing "Amount below min for MCC 4511 (airlines, min 50) returns true"
    (let [payload (-> (base-payload)
                      (assoc-in [:transaction :amount] 10.0)
                      (assoc-in [:context :sale_mcc] "4511"))]
      (is (true? (auth/mcc-amount-restricted? payload))))))

(deftest mcc-amount-restricted?-passing
  (testing "Amount within limits returns false"
    (let [payload (-> (base-payload)
                      (assoc-in [:transaction :amount] 50.0)
                      (assoc-in [:context :sale_mcc] "7801"))]
      (is (false? (auth/mcc-amount-restricted? payload))))))

(deftest mcc-amount-restricted?-unknown-mcc
  (testing "MCC not in restrictions file returns false"
    (let [payload (-> (base-payload)
                      (assoc-in [:context :sale_mcc] "9999"))]
      (is (false? (auth/mcc-amount-restricted? payload))))))

;; ---------------------------------------------------------------------------
;; 5. mcc-relation-restricted?
;; ---------------------------------------------------------------------------

(deftest mcc-relation-restricted?-flagged
  (testing "Sale MCC incompatible with merchant MCC returns true"
    ;; Merchant MCC 7802 (horse racing) related: [7995, 7801, 5813]
    ;; Sale MCC 5411 (grocery) is NOT in [7802, 7995, 7801, 5813] -> restricted
    (let [payload (-> (base-payload)
                      (assoc-in [:environment :merchant :mcc] "7802")
                      (assoc-in [:context :sale_mcc] "5411"))]
      (is (true? (auth/mcc-relation-restricted? payload))))))

(deftest mcc-relation-restricted?-passing-related
  (testing "Sale MCC in related list returns false"
    ;; Merchant MCC 7802 related includes 7995
    (let [payload (-> (base-payload)
                      (assoc-in [:environment :merchant :mcc] "7802")
                      (assoc-in [:context :sale_mcc] "7995"))]
      (is (false? (auth/mcc-relation-restricted? payload))))))

(deftest mcc-relation-restricted?-passing-same-mcc
  (testing "Sale MCC equals merchant MCC returns false"
    (let [payload (-> (base-payload)
                      (assoc-in [:environment :merchant :mcc] "7802")
                      (assoc-in [:context :sale_mcc] "7802"))]
      (is (false? (auth/mcc-relation-restricted? payload))))))

(deftest mcc-relation-restricted?-unknown-merchant-mcc
  (testing "Merchant MCC not in relations file returns false"
    (let [payload (-> (base-payload)
                      (assoc-in [:environment :merchant :mcc] "9999")
                      (assoc-in [:context :sale_mcc] "5411"))]
      (is (false? (auth/mcc-relation-restricted? payload))))))

;; ---------------------------------------------------------------------------
;; authorize orchestrator
;; ---------------------------------------------------------------------------

(deftest authorize-clean
  (testing "Clean transaction is approved"
    (let [result (auth/authorize (base-payload))]
      (is (true? (:approved result)))
      (is (nil? (:rules_violated result))))))

(deftest authorize-single-violation
  (testing "Transaction with one violation returns approved false with rule name"
    ;; Trigger only mcc_amount_restriction: amount 150 on MCC 7801 (max 100)
    ;; Use merchant MCC 7801 and sale MCC 7801 to avoid relation restriction
    (let [payload (-> (base-payload)
                      (assoc-in [:transaction :amount] 150.0)
                      (assoc-in [:environment :merchant :mcc] "7801")
                      (assoc-in [:context :sale_mcc] "7801"))
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"mcc_amount_restriction"} (:rules_violated result))))))

(deftest authorize-multiple-violations
  (testing "Transaction with multiple violations includes all violated rules"
    ;; Trigger anomalous_interval (2 min apart) + mcc_amount_restriction (150 on MCC 7801 max 100)
    ;; Use merchant MCC 7801 and sale MCC 7801 to avoid relation restriction
    (let [payload (-> (base-payload)
                      (assoc-in [:transaction :amount] 150.0)
                      (assoc-in [:environment :merchant :mcc] "7801")
                      (assoc-in [:context :sale_mcc] "7801")
                      (assoc :last_transaction
                             {:timestamp "2026-03-27T14:28:00Z"
                              :terminal  {:latitude 0.0 :longitude 0.0}}))
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"anomalous_interval"} (:rules_violated result)))
      (is (some #{"mcc_amount_restriction"} (:rules_violated result))))))
