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
  (testing "Clean payload returns 200 with approved true"
    (let [payload  {:transaction    {:id "tx-1" :amount 100.0 :currency "BRL"
                                     :installments 1
                                     :timestamp "2026-03-27T14:30:00Z"}
                    :environment    {:merchant {:id "m1" :name "Store" :mcc "5411"}
                                     :terminal {:id "t1" :latitude 0.0 :longitude 0.0}}
                    :context        {:sale_mcc "5411"}
                    :last_transaction nil}
          response (post-authorization payload)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (true? (:approved body))))))

;; ---------------------------------------------------------------------------
;; 2. Denied transaction
;; ---------------------------------------------------------------------------

(deftest denied-transaction
  (testing "Payload violating mcc_amount_restriction returns 200 with approved false"
    (let [payload  {:transaction    {:id "tx-2" :amount 500.0 :currency "BRL"
                                     :installments 1
                                     :timestamp "2026-03-27T14:30:00Z"}
                    :environment    {:merchant {:id "m1" :name "Casino" :mcc "7801"}
                                     :terminal {:id "t1" :latitude 0.0 :longitude 0.0}}
                    :context        {:sale_mcc "7801"}
                    :last_transaction nil}
          response (post-authorization payload)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (false? (:approved body)))
      (is (some #{"mcc_amount_restriction"} (:rules_violated body))))))

;; ---------------------------------------------------------------------------
;; 3. Not found
;; ---------------------------------------------------------------------------

(deftest not-found-route
  (testing "GET to unknown route returns 404"
    (let [response (endpoints/app {:request-method :get
                                   :uri            "/does-not-exist"
                                   :headers        {}})]
      (is (= 404 (:status response))))))
