(ns rinha-de-backend-2026-exemplo.endpoints-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [rinha-de-backend-2026-exemplo.endpoints :as endpoints])
  (:import [java.io ByteArrayInputStream]))

(defn- json-body-stream [m]
  (ByteArrayInputStream. (.getBytes (json/write-str m) "UTF-8")))

(defn- post-authorization [payload]
  (endpoints/app {:request-method :post
                  :uri            "/authorizations"
                  :headers        {"content-type" "application/json"}
                  :body           (json-body-stream payload)}))

(defn- parse-body [response]
  (json/read-str (:body response) :key-fn keyword))

;; ---------------------------------------------------------------------------
;; 1. Approved transaction
;; ---------------------------------------------------------------------------

(deftest approved-transaction
  (testing "Legit-looking payload returns 200 with approved and fraud_score"
    (let [payload  {:id          "tx-legit"
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
                                       :km_from_current 1.5}}
          response (post-authorization payload)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (contains? body :approved))
      (is (contains? body :fraud_score))
      (is (boolean? (:approved body)))
      (is (<= 0.0 (:fraud_score body) 1.0)))))

;; ---------------------------------------------------------------------------
;; 2. Not found
;; ---------------------------------------------------------------------------

(deftest not-found-route
  (testing "GET to unknown route returns 404"
    (let [response (endpoints/app {:request-method :get
                                   :uri            "/does-not-exist"
                                   :headers        {}})]
      (is (= 404 (:status response))))))
