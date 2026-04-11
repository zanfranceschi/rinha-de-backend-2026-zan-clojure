(ns rinha-de-backend-2026-exemplo.normalization-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026-exemplo.normalization :as norm]))

(def test-config
  {:max_amount              10000
   :max_installments        12
   :amount_vs_avg_ratio     10
   :max_minutes             1440
   :max_km                  1000
   :max_tx_count_24h        20
   :max_merchant_avg_amount 10000})

(def test-mcc-risk
  {"5411" 0.15
   "7995" 0.85})

(defn base-request []
  {:id          "tx-1"
   :transaction {:amount       1500.90
                 :installments 6
                 :requested_at "2026-03-14T12:15:00Z"}
   :customer    {:avg_amount      250.00
                 :tx_count_24h    5
                 :known_merchants ["MERC-001" "MERC-042"]}
   :merchant    {:id         "MERC-099"
                 :mcc        "5411"
                 :avg_amount 180.00}
   :terminal    {:is_online    false
                 :card_present true
                 :km_from_home 357.0}
   :last_transaction {:timestamp       "2026-03-14T11:30:00Z"
                      :km_from_current 72.3}})

(deftest normalize-produces-14-dimensions
  (testing "Normalization produces a 14-element vector"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (= 14 (count v))))))

(deftest normalize-values-in-range
  (testing "All values are between 0.0 and 1.0 (or -1 sentinel)"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (doseq [val v]
        (is (or (= -1.0 val)
                (and (<= 0.0 val) (<= val 1.0))))))))

(deftest normalize-amount
  (testing "amount = 1500.90 / 10000 = 0.15009"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- 0.15009 (nth v 0))) 1e-6)))))

(deftest normalize-installments
  (testing "installments = 6 / 12 = 0.5"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- 0.5 (nth v 1))) 1e-6)))))

(deftest normalize-amount-vs-avg
  (testing "amount_vs_avg = min((1500.90 / 250.00) / 10, 1.0) = 0.60036"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- 0.60036 (nth v 2))) 1e-4)))))

(deftest normalize-hour-of-day
  (testing "hour = 12 / 23 = 0.52174"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- (/ 12.0 23.0) (nth v 3))) 1e-4)))))

(deftest normalize-day-of-week
  (testing "2026-03-14 is Saturday = 5 / 6 = 0.8333"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- (/ 5.0 6.0) (nth v 4))) 1e-4)))))

(deftest normalize-minutes-since-last-tx
  (testing "45 minutes / 1440 = 0.03125"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- (/ 45.0 1440.0) (nth v 5))) 1e-4)))))

(deftest normalize-km-from-last-tx
  (testing "72.3 / 1000 = 0.0723"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- 0.0723 (nth v 6))) 1e-4)))))

(deftest normalize-km-from-home
  (testing "357.0 / 1000 = 0.357"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- 0.357 (nth v 7))) 1e-4)))))

(deftest normalize-tx-count-24h
  (testing "5 / 20 = 0.25"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- 0.25 (nth v 8))) 1e-4)))))

(deftest normalize-is-online
  (testing "is_online false = 0.0"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (= 0.0 (nth v 9))))))

(deftest normalize-card-present
  (testing "card_present true = 1.0"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (= 1.0 (nth v 10))))))

(deftest normalize-is-new-merchant
  (testing "MERC-099 not in [MERC-001 MERC-042] = 1.0 (new)"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (= 1.0 (nth v 11))))))

(deftest normalize-mcc-risk
  (testing "MCC 5411 risk = 0.15"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (= 0.15 (nth v 12))))))

(deftest normalize-merchant-avg-amount
  (testing "180.00 / 10000 = 0.018"
    (let [v (norm/normalize (base-request) test-config test-mcc-risk)]
      (is (< (Math/abs (- 0.018 (nth v 13))) 1e-4)))))

;; Edge cases

(deftest normalize-null-last-transaction
  (testing "null last_transaction produces -1 sentinel for dimensions 6 and 7"
    (let [req (assoc (base-request) :last_transaction nil)
          v   (norm/normalize req test-config test-mcc-risk)]
      (is (= -1.0 (nth v 5)))
      (is (= -1.0 (nth v 6))))))

(deftest normalize-unknown-mcc
  (testing "Unknown MCC defaults to 0.5"
    (let [req (assoc-in (base-request) [:merchant :mcc] "9999")
          v   (norm/normalize req test-config test-mcc-risk)]
      (is (= 0.5 (nth v 12))))))

(deftest normalize-clamps-above-max
  (testing "Amount above max_amount clamps to 1.0"
    (let [req (assoc-in (base-request) [:transaction :amount] 15000.0)
          v   (norm/normalize req test-config test-mcc-risk)]
      (is (= 1.0 (nth v 0))))))

(deftest normalize-known-merchant
  (testing "Merchant in known_merchants = 0.0 (known)"
    (let [req (assoc-in (base-request) [:merchant :id] "MERC-001")
          v   (norm/normalize req test-config test-mcc-risk)]
      (is (= 0.0 (nth v 11))))))
