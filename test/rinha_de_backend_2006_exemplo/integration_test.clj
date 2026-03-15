(ns rinha-de-backend-2006-exemplo.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [hikari-cp.core :as hikari]
            [clojure.data.json :as json]
            [rinha-de-backend-2006-exemplo.endpoints :as endpoints]
            [rinha-de-backend-2006-exemplo.db :as db])
  (:import [org.testcontainers.containers PostgreSQLContainer]
           [java.io ByteArrayInputStream]))

;; ---------------------------------------------------------------------------
;; Testcontainers lifecycle
;; ---------------------------------------------------------------------------

(def ^:dynamic *ds* nil)
(def ^:dynamic *container* nil)

(defn start-postgres-container []
  (doto (PostgreSQLContainer. "postgres:17-alpine")
    (.withDatabaseName "rinha")
    (.withUsername "postgres")
    (.withPassword "postgres")
    (.start)))

(defn make-test-datasource [^PostgreSQLContainer container]
  (hikari/make-datasource
   {:jdbc-url          (.getJdbcUrl container)
    :username          (.getUsername container)
    :password          (.getPassword container)
    :minimum-idle      2
    :maximum-pool-size 5}))

(defn with-postgres-container [f]
  (let [container (start-postgres-container)]
    (try
      (let [ds (make-test-datasource container)]
        (try
          ;; Run any schema setup / migrations here
          (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS example (
                                id    SERIAL PRIMARY KEY,
                                name  TEXT NOT NULL)"])
          (binding [*container* container
                    *ds*       ds]
            (f))
          (finally
            (hikari/close-datasource ds))))
      (finally
        (.stop container)))))

(use-fixtures :once with-postgres-container)

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-db-connection
  (testing "container is running and connectable"
    (let [result (jdbc/execute-one! *ds* ["SELECT 1 AS result"])]
      (is (= 1 (:result result))))))

(deftest test-insert-and-query
  (testing "can insert and query rows"
    (jdbc/execute! *ds* ["INSERT INTO example (name) VALUES (?)" "hello"])
    (let [rows (jdbc/execute! *ds* ["SELECT * FROM example WHERE name = ?" "hello"])]
      (is (= 1 (count rows)))
      (is (= "hello" (:example/name (first rows)))))))

(deftest test-db-test-endpoint
  (testing "GET /db-test returns result through real Postgres"
    (let [handler (-> #'endpoints/routes (db/wrap-db *ds*))
          response (handler {:request-method :get
                             :uri            "/db-test"
                             :headers        {}
                             :body           nil})]
      (is (= 200 (:status response)))
      (is (= 1 (:result (:body response)))))))

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
    ;; `app` includes wrap-json-body + wrap-json-response.
    ;; wrap-json-body expects :body to be an InputStream (like a real request),
    ;; and it parses JSON into a Clojure map with keyword keys.
    ;; wrap-json-response serialises the response :body map into a JSON string.
    (let [handler  (db/wrap-db #'endpoints/app *ds*)
          response (handler {:request-method :post
                             :uri            "/test"
                             :headers        {"content-type" "application/json"}
                             :body           (json-body-stream {:msg "hello json"})})]
      (is (= 200 (:status response)))
      ;; After wrap-json-response, :body is a JSON string
      (let [parsed (json/read-str (:body response) :key-fn keyword)]
        (is (= "hello json" (:msg parsed)))))))

(deftest test-post-test-endpoint-edn-through-routes
  (testing "POST /test with EDN body directly to `routes` (no JSON middleware)"
    ;; Calling `routes` directly skips wrap-json-body / wrap-json-response.
    ;; So :body is just a plain Clojure map — no InputStream, no JSON parsing.
    ;; The response :body is also a plain Clojure map (not serialised).
    (let [handler  (db/wrap-db #'endpoints/routes *ds*)
          response (handler {:request-method :post
                             :uri            "/test"
                             :headers        {}
                             :body           {:msg "hello edn"}})]
      (is (= 200 (:status response)))
      ;; :body is a raw Clojure map – no JSON parsing needed
      (is (= "hello edn" (get-in response [:body :msg]))))))
