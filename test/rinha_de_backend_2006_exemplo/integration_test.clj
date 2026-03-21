(ns rinha-de-backend-2006-exemplo.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [rinha-de-backend-2006-exemplo.endpoints :as endpoints])
  (:import [java.io ByteArrayInputStream]))

;; ---------------------------------------------------------------------------
;; POST /test – demonstrating JSON vs EDN content types
;; ---------------------------------------------------------------------------

(defn- json-body-stream
  "Converts a Clojure map to a JSON InputStream, simulating what a real
   HTTP server would receive from a client sending JSON."
  [m]
  (ByteArrayInputStream. (.getBytes (json/write-str m) "UTF-8")))

(deftest test-post-test-endpoint-json-through-app
  (testing "POST /test with JSON body through `app` (with JSON middleware)"
    (let [response (endpoints/app {:request-method :post
                                    :uri            "/test"
                                    :headers        {"content-type" "application/json"}
                                    :body           (json-body-stream {:msg "hello json"})})]
      (is (= 200 (:status response)))
      (let [parsed (json/read-str (:body response) :key-fn keyword)]
        (is (= "hello json" (:msg parsed)))))))

(deftest test-post-test-endpoint-edn-through-routes
  (testing "POST /test with EDN body directly to `routes` (no JSON middleware)"
    (let [response (endpoints/routes {:request-method :post
                                       :uri            "/test"
                                       :headers        {}
                                       :body           {:msg "hello edn"}})]
      (is (= 200 (:status response)))
      (is (= "hello edn" (get-in response [:body :msg]))))))
