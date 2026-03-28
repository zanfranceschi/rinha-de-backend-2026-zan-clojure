# Rinha de Backend 2026 — Codebase Reorganization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the sandbox codebase into a clean example submission for the Rinha de Backend 2026 fraud detection challenge, with aligned API contract, proper Docker infrastructure, and test scripts.

**Architecture:** Stateless HTTP API (Ring/Jetty + Compojure) with 5 fraud detection rules. Two API instances behind an nginx load balancer, all orchestrated via Docker Compose. Test scripts (k6 + data generator) live in a `test-scripts/` directory.

**Tech Stack:** Clojure 1.12, Ring/Jetty, Compojure, data.json, Telemere logging, nginx, Docker Compose, k6

---

## File Structure

After reorganization, the repo will look like this:

```
/
├── src/rinha_de_backend_2026_exemplo/
│   ├── endpoints.clj          # HTTP server + single POST /authorizations route
│   ├── authorization.clj      # 5 fraud rules + authorize orchestrator
│   └── geo.clj                # Point-in-polygon + distance calculations (renamed from misc.clj)
├── test/rinha_de_backend_2026_exemplo/
│   ├── authorization_test.clj # Unit tests for each fraud rule
│   └── endpoints_test.clj     # Integration tests for the HTTP endpoint
├── resources/
│   ├── restricted_areas.json
│   ├── mccs_restrictions.json
│   └── mcc_relation_restrictions.json
├── test-scripts/
│   ├── k6/
│   │   └── test.js            # k6 load test script
│   └── data-generator/
│       └── generate.clj       # Seeded test data generator (Babashka script)
├── nginx.conf                 # Load balancer config
├── Dockerfile                 # Clojure app container
├── docker-compose.yml         # 2 API instances + nginx
├── project.clj                # Cleaned dependencies
└── requests.http              # Updated to match spec payload
```

**Files to delete:**
- `src/rinha_de_backend_2006_exemplo/` (entire old namespace directory, after migration)
- `src/rinha_de_backend_2006_exemplo/authorization_requests_generation.clj` (exploratory code)
- `test/rinha_de_backend_2006_exemplo/` (entire old test namespace directory)
- `doc/intro.md` (placeholder)
- `db/init.sql` (referenced but doesn't exist; remove `db/` directory)
- `.env` (not needed for Docker-based setup)
- `.hgignore` (legacy)
- `CHANGELOG.md` (placeholder)
- ~~`shell.nix`~~ — KEEP: user uses this for dev tools (lein, jdk, k6, jq)

---

### Task 1: Rename namespace from 2006 to 2026

**Files:**
- Create: `src/rinha_de_backend_2026_exemplo/endpoints.clj`
- Create: `src/rinha_de_backend_2026_exemplo/authorization.clj`
- Create: `src/rinha_de_backend_2026_exemplo/geo.clj`
- Create: `test/rinha_de_backend_2026_exemplo/authorization_test.clj`
- Create: `test/rinha_de_backend_2026_exemplo/endpoints_test.clj`
- Modify: `project.clj`
- Delete: `src/rinha_de_backend_2006_exemplo/` (entire directory)
- Delete: `test/rinha_de_backend_2006_exemplo/` (entire directory)

- [ ] **Step 1: Create new source directories**

```bash
mkdir -p src/rinha_de_backend_2026_exemplo
mkdir -p test/rinha_de_backend_2026_exemplo
```

- [ ] **Step 2: Copy and rename namespace in geo.clj (was misc.clj)**

Create `src/rinha_de_backend_2026_exemplo/geo.clj`:

```clojure
(ns rinha-de-backend-2026-exemplo.geo)

(defn equirectangular-km-distance
  ^double [{^double lat1 :lat ^double lon1 :lon}
           {^double lat2 :lat ^double lon2 :lon}]
  (let [R    6371.0
        lat1 (Math/toRadians lat1)
        lat2 (Math/toRadians lat2)
        dlon (Math/toRadians (- lon2 lon1))
        dlat (- lat2 lat1)
        x    (* dlon (Math/cos (/ (+ lat1 lat2) 2.0)))
        y    dlat]
    (* R (Math/sqrt (+ (* x x) (* y y))))))

(defn point-in-polygon?
  [[lon lat] polygon]
  (let [n (count polygon)]
    (loop [i       0
           j       (dec n)
           inside? false]
      (if (< i n)
        (let [[xi yi]    (nth polygon i)
              [xj yj]    (nth polygon j)
              intersect? (and (or (and (> yi lat) (<= yj lat))
                                  (and (> yj lat) (<= yi lat)))
                              (< lon (+ xj (* (/ (- lat yj) (- yi yj))
                                              (- xi xj)))))]
          (recur (inc i) i (if intersect? (not inside?) inside?)))
        inside?))))
```

Note: `haversine-km-distance` is dropped — unused in the authorization rules. Only `equirectangular-km-distance` is used.

- [ ] **Step 3: Copy and rename namespace in authorization.clj**

Create `src/rinha_de_backend_2026_exemplo/authorization.clj` with the existing rule functions, updating the namespace and require:

```clojure
(ns rinha-de-backend-2026-exemplo.authorization
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [rinha-de-backend-2026-exemplo.geo :as geo])
  (:import
   [java.time Duration Instant]))

(def restricted-areas-list
  (let [coords (json/read-str
                (slurp (io/resource "restricted_areas.json")) :key-fn keyword)]
    (map (fn [feature]
           (-> feature :geometry :coordinates first))
         (:features coords))))

(def mccs-restrictions
  (json/read-str
   (slurp (io/resource "mccs_restrictions.json")) :key-fn keyword))

(def mcc-relation-restrictions
  (json/read-str
   (slurp (io/resource "mcc_relation_restrictions.json")) :key-fn keyword))

;; Rule functions will be updated in Task 3 to align with spec
;; For now, copy existing implementations with namespace fix

(defn in-restricted-area? [authorization-request restricted-areas]
  (let [{:keys [lat lon]}
        (-> authorization-request :environment :terminal)]
    (boolean (and lat lon (some #(geo/point-in-polygon? [lon lat] %) restricted-areas)))))

(defn annomalous-distance-time? [auth-request]
  (if-let [last-tx (:last-transaction auth-request)]
    (let [current-tx-timestamp  (Instant/parse (-> auth-request :transaction :timestamp))
          {:keys [lat lon]}     (-> auth-request :environment :terminal)
          last-tx-terminal      (-> last-tx :environment :terminal)
          last-tx-lat           (:lat last-tx-terminal)
          last-tx-lon           (:lon last-tx-terminal)
          last-tx-distance-km   (geo/equirectangular-km-distance
                                 {:lat lat :lon lon}
                                 {:lat last-tx-lat :lon last-tx-lon})
          last-tx-timestamp     (Instant/parse (-> last-tx :transaction :timestamp))
          last-tx-interval-secs (.toSeconds
                                 (Duration/between
                                  last-tx-timestamp
                                  current-tx-timestamp))
          distance-thresholds   [{:max-km 1   :max-speed 15}
                                 {:max-km 5   :max-speed 40}
                                 {:max-km 50  :max-speed 100}
                                 {:max-km 300 :max-speed 140}
                                 {:max-km 1500 :max-speed 450}
                                 {:max-km 99999 :max-speed 700}]
          speed-kmh             (if (> last-tx-interval-secs 0)
                                  (* (/ last-tx-distance-km last-tx-interval-secs) 3600.0)
                                  0)
          range                 (first (filter #(<= last-tx-distance-km (:max-km %)) distance-thresholds))
          fraud?                (boolean (and range (> speed-kmh (:max-speed range))))]
      fraud?)
    false))

(defn annomalous-interval? [auth-request]
  (if-let [last-tx (:last-transaction auth-request)]
    (let [current-tx-timestamp  (Instant/parse (-> auth-request :transaction :timestamp))
          last-tx-timestamp     (Instant/parse (-> last-tx :transaction :timestamp))
          last-tx-interval-mins (.toMinutes
                                 (Duration/between
                                  last-tx-timestamp
                                  current-tx-timestamp))]
      (< last-tx-interval-mins 5))
    false))

(defn mcc-restricted? [authorization-request]
  (let [tx-amount              (-> authorization-request :transaction :amount)
        sale-mcc               (-> authorization-request :context :sale-mcc)
        mcc-restrictions       (->> mccs-restrictions
                                    (filter #(= (:mcc %) sale-mcc))
                                    first)]
    (if mcc-restrictions
      (or (and (:max_amount mcc-restrictions) (> tx-amount (:max_amount mcc-restrictions)))
          (and (:min_amount mcc-restrictions) (< tx-amount (:min_amount mcc-restrictions))))
      false)))

(defn mcc-relation-restricted? [authorization-request]
  (let [sale-mcc      (-> authorization-request :context :sale-mcc)
        merchant-mcc  (-> authorization-request :environment :merchant :mcc)
        relation      (->> mcc-relation-restrictions
                           (filter #(= (:mcc %) merchant-mcc))
                           first)]
    (if relation
      (let [allowed-mccs (conj (set (map :mcc (:related relation))) merchant-mcc)]
        (not (contains? allowed-mccs sale-mcc)))
      false)))

(defn authorize [payload]
  (let [violations (cond-> []
                     (in-restricted-area? payload restricted-areas-list)
                     (conj "restricted_area")

                     (annomalous-distance-time? payload)
                     (conj "anomalous_travel_speed")

                     (annomalous-interval? payload)
                     (conj "anomalous_interval")

                     (mcc-restricted? payload)
                     (conj "mcc_amount_restriction")

                     (mcc-relation-restricted? payload)
                     (conj "mcc_relation_restriction"))]
    (if (empty? violations)
      {:approved true}
      {:approved false :rules_violated violations})))
```

- [ ] **Step 4: Copy and rename namespace in endpoints.clj**

Create `src/rinha_de_backend_2026_exemplo/endpoints.clj`:

```clojure
(ns rinha-de-backend-2026-exemplo.endpoints
  (:require [compojure.core :refer [defroutes POST]]
            [compojure.route :as route]
            [envvar.core :refer [env]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [rinha-de-backend-2026-exemplo.authorization :as authorization]
            [taoensso.telemere :as tel])
  (:gen-class))

(defroutes routes
  (POST "/authorizations" req
    (let [payload (:body req)
          result  (authorization/authorize payload)]
      {:status 200
       :body   result}))

  (route/not-found {:status 404
                    :body   {:error "not found"}}))

(def app
  (-> routes
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

(defn -main [& _args]
  (let [port    (Integer/parseInt (or (@env :port) "3000"))
        server  (jetty/run-jetty app {:port  port
                                       :join? false})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (tel/log! "Shutting down...")
                                 (.stop server))))
    (tel/log! (str "Server running on port " port))))
```

Key changes:
- Removed `/hello`, `/test`, and `/db-test` routes (not part of submission)
- Removed idempotency key handling (dropped per spec)
- Removed dev-mode hot-reload (not needed for submission example)
- Response is always HTTP 200 (per spec)
- `authorize` now takes only payload (restricted areas loaded internally)

- [ ] **Step 5: Update project.clj**

```clojure
(defproject rinha-de-backend-2026-exemplo "0.1.0-SNAPSHOT"
  :description "Rinha de Backend 2026 - Fraud Detection Engine - Example Submission"
  :url "https://github.com/zanfranceschi/rinha-de-backend-2026-exemplo-clojure"
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [ring/ring-jetty-adapter "1.15.3"]
                 [ring/ring-json "0.5.1"]
                 [compojure "1.7.2"]
                 [envvar "1.1.2"]
                 [com.taoensso/telemere "1.2.1"]
                 [org.clojure/data.json "2.5.2"]]
  :main ^:skip-aot rinha-de-backend-2026-exemplo.endpoints
  :target-path "target/%s"
  :profiles {:uberjar {:aot      :all
                        :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
```

Removed: reitit, postgresql, next.jdbc, hikari-cp, data.csv, ring-devel, jts-core, testcontainers.

- [ ] **Step 6: Delete old namespace directories and exploratory files**

```bash
rm -rf src/rinha_de_backend_2006_exemplo
rm -rf test/rinha_de_backend_2006_exemplo
```

- [ ] **Step 7: Run the project to verify it compiles**

```bash
lein run
```

Expected: Server starts on port 3000 with no errors.

- [ ] **Step 8: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/ project.clj
git add -u  # stages deletions
git commit -m "refactor: rename namespace 2006 → 2026, clean up deps and routes"
```

---

### Task 2: Align payload field names with spec

The current code uses kebab-case keywords (`:sale-mcc`, `:last-transaction`) because of Clojure conventions, but the JSON payload uses snake_case (`sale_mcc`, `last_transaction`). Since `wrap-json-body` with `{:keywords? true}` converts JSON keys to keywords as-is (no case conversion), the incoming keywords will be `:sale_mcc`, `:last_transaction`, `:latitude`, `:longitude`, etc.

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/authorization.clj`

- [ ] **Step 1: Update field access paths in all rule functions**

The spec payload uses these paths (as keywords after JSON parsing):
- Transaction amount: `(-> payload :transaction :amount)`
- Transaction timestamp: `(-> payload :transaction :timestamp)`
- Terminal lat/lon: `(-> payload :environment :terminal :latitude)` and `:longitude`
- Merchant MCC: `(-> payload :environment :merchant :mcc)`
- Sale MCC: `(-> payload :context :sale_mcc)`
- Last transaction: `(:last_transaction payload)`
- Last tx timestamp: `(-> last-tx :timestamp)`
- Last tx terminal: `(-> last-tx :terminal :latitude)` and `:longitude`

Update `authorization.clj` — replace all field access paths:

In `in-restricted-area?`:
```clojure
(defn in-restricted-area? [payload restricted-areas]
  (let [latitude  (-> payload :environment :terminal :latitude)
        longitude (-> payload :environment :terminal :longitude)]
    (boolean (and latitude longitude
                  (some #(geo/point-in-polygon? [longitude latitude] %) restricted-areas)))))
```

In `annomalous-distance-time?` — rename to `anomalous-travel-speed?`:
```clojure
(defn anomalous-travel-speed? [payload]
  (if-let [last-tx (:last_transaction payload)]
    (let [current-ts    (Instant/parse (-> payload :transaction :timestamp))
          lat           (-> payload :environment :terminal :latitude)
          lon           (-> payload :environment :terminal :longitude)
          last-lat      (-> last-tx :terminal :latitude)
          last-lon      (-> last-tx :terminal :longitude)
          distance-km   (geo/equirectangular-km-distance
                         {:lat lat :lon lon}
                         {:lat last-lat :lon last-lon})
          last-ts       (Instant/parse (:timestamp last-tx))
          interval-secs (.toSeconds (Duration/between last-ts current-ts))
          thresholds    [{:max-km 10   :max-speed 15}
                         {:max-km 50   :max-speed 60}
                         {:max-km 200  :max-speed 120}
                         {:max-km 1000 :max-speed 350}
                         {:max-km 99999 :max-speed 700}]
          speed-kmh     (if (> interval-secs 0)
                          (* (/ distance-km interval-secs) 3600.0)
                          0)
          threshold     (first (filter #(<= distance-km (:max-km %)) thresholds))]
      (boolean (and threshold (> speed-kmh (:max-speed threshold)))))
    false))
```

Note: thresholds updated to match the spec table exactly (0–10, 10–50, 50–200, 200–1000, 1000+).

In `annomalous-interval?` — rename to `anomalous-interval?`:
```clojure
(defn anomalous-interval? [payload]
  (if-let [last-tx (:last_transaction payload)]
    (let [current-ts (Instant/parse (-> payload :transaction :timestamp))
          last-ts    (Instant/parse (:timestamp last-tx))
          interval   (.toMinutes (Duration/between last-ts current-ts))]
      (< interval 5))
    false))
```

In `mcc-restricted?` — rename to `mcc-amount-restricted?`:
```clojure
(defn mcc-amount-restricted? [payload]
  (let [amount     (-> payload :transaction :amount)
        sale-mcc   (-> payload :context :sale_mcc)
        restriction (->> mccs-restrictions
                         (filter #(= (:mcc %) sale-mcc))
                         first)]
    (if restriction
      (or (and (:max_amount restriction) (> amount (:max_amount restriction)))
          (and (:min_amount restriction) (< amount (:min_amount restriction))))
      false)))
```

In `mcc-relation-restricted?`:
```clojure
(defn mcc-relation-restricted? [payload]
  (let [sale-mcc     (-> payload :context :sale_mcc)
        merchant-mcc (-> payload :environment :merchant :mcc)
        relation     (->> mcc-relation-restrictions
                          (filter #(= (:mcc %) merchant-mcc))
                          first)]
    (if relation
      (let [allowed (conj (set (map :mcc (:related relation))) merchant-mcc)]
        (not (contains? allowed sale-mcc)))
      false)))
```

Update `authorize`:
```clojure
(defn authorize [payload]
  (let [violations (cond-> []
                     (in-restricted-area? payload restricted-areas-list)
                     (conj "restricted_area")

                     (anomalous-travel-speed? payload)
                     (conj "anomalous_travel_speed")

                     (anomalous-interval? payload)
                     (conj "anomalous_interval")

                     (mcc-amount-restricted? payload)
                     (conj "mcc_amount_restriction")

                     (mcc-relation-restricted? payload)
                     (conj "mcc_relation_restriction"))]
    (if (empty? violations)
      {:approved true}
      {:approved false :rules_violated violations})))
```

- [ ] **Step 2: Run the project and test with curl**

```bash
lein run &
sleep 3
curl -s -X POST http://localhost:3000/authorizations \
  -H "Content-Type: application/json" \
  -d '{
    "transaction": {"id": "abc", "amount": 150.00, "currency": "BRL", "installments": 3, "timestamp": "2026-03-27T14:30:00Z"},
    "environment": {"merchant": {"id": "m1", "name": "Store", "mcc": "5411"}, "terminal": {"id": "t1", "latitude": -23.5505, "longitude": -46.6333}},
    "context": {"sale_mcc": "5411"},
    "last_transaction": null
  }' | jq .
kill %1
```

Expected: `{"approved": true}` or `{"approved": false, "rules_violated": [...]}` depending on whether the coordinates hit a restricted zone.

- [ ] **Step 3: Commit**

```bash
git add -u
git commit -m "refactor: align payload fields with spec, rename rules to match spec names"
```

---

### Task 3: Write authorization rule unit tests

**Files:**
- Create: `test/rinha_de_backend_2026_exemplo/authorization_test.clj`

- [ ] **Step 1: Write tests for all 5 rules**

Create `test/rinha_de_backend_2026_exemplo/authorization_test.clj`:

```clojure
(ns rinha-de-backend-2026-exemplo.authorization-test
  (:require [clojure.test :refer [deftest is testing]]
            [rinha-de-backend-2026-exemplo.authorization :as auth]))

;; Helper to build a minimal valid payload
(defn- make-payload
  [{:keys [amount sale-mcc merchant-mcc lat lon timestamp
           last-tx-lat last-tx-lon last-tx-timestamp]}]
  (cond-> {:transaction  {:id        "tx-1"
                          :amount    (or amount 100.0)
                          :currency  "BRL"
                          :installments 1
                          :timestamp (or timestamp "2026-03-27T14:30:00Z")}
           :environment  {:merchant {:id "m1" :name "Store" :mcc (or merchant-mcc "5411")}
                          :terminal {:id "t1"
                                     :latitude (or lat -23.5505)
                                     :longitude (or lon -46.6333)}}
           :context      {:sale_mcc (or sale-mcc "5411")}}
    last-tx-timestamp
    (assoc :last_transaction
           {:timestamp last-tx-timestamp
            :terminal  {:latitude  (or last-tx-lat -23.5505)
                        :longitude (or last-tx-lon -46.6333)}})))

;; --- Rule 1: restricted_area ---

(deftest test-restricted-area-outside
  (testing "terminal outside all restricted zones passes"
    (let [payload (make-payload {:lat 0.0 :lon 0.0})]
      (is (false? (auth/in-restricted-area? payload auth/restricted-areas-list))))))

;; --- Rule 2: anomalous_travel_speed ---

(deftest test-anomalous-travel-speed-no-last-tx
  (testing "no last_transaction passes automatically"
    (let [payload (make-payload {})]
      (is (false? (auth/anomalous-travel-speed? payload))))))

(deftest test-anomalous-travel-speed-normal
  (testing "reasonable speed between close terminals passes"
    (let [payload (make-payload {:lat -23.5505 :lon -46.6333
                                 :timestamp "2026-03-27T14:30:00Z"
                                 :last-tx-lat -23.5600 :last-tx-lon -46.6400
                                 :last-tx-timestamp "2026-03-27T10:00:00Z"})]
      (is (false? (auth/anomalous-travel-speed? payload))))))

(deftest test-anomalous-travel-speed-impossible
  (testing "impossibly fast travel is flagged"
    (let [payload (make-payload {:lat -23.5505 :lon -46.6333
                                 :timestamp "2026-03-27T14:01:00Z"
                                 :last-tx-lat -22.9068 :last-tx-lon -43.1729
                                 :last-tx-timestamp "2026-03-27T14:00:00Z"})]
      (is (true? (auth/anomalous-travel-speed? payload))))))

;; --- Rule 3: anomalous_interval ---

(deftest test-anomalous-interval-no-last-tx
  (testing "no last_transaction passes automatically"
    (let [payload (make-payload {})]
      (is (false? (auth/anomalous-interval? payload))))))

(deftest test-anomalous-interval-too-fast
  (testing "transactions less than 5 minutes apart are flagged"
    (let [payload (make-payload {:timestamp "2026-03-27T14:03:00Z"
                                 :last-tx-timestamp "2026-03-27T14:00:00Z"})]
      (is (true? (auth/anomalous-interval? payload))))))

(deftest test-anomalous-interval-normal
  (testing "transactions more than 5 minutes apart pass"
    (let [payload (make-payload {:timestamp "2026-03-27T14:10:00Z"
                                 :last-tx-timestamp "2026-03-27T14:00:00Z"})]
      (is (false? (auth/anomalous-interval? payload))))))

;; --- Rule 4: mcc_amount_restriction ---

(deftest test-mcc-amount-no-restriction
  (testing "MCC with no restrictions passes"
    (let [payload (make-payload {:sale-mcc "5411" :amount 5000.0})]
      (is (false? (auth/mcc-amount-restricted? payload))))))

(deftest test-mcc-amount-over-max
  (testing "amount over max for restricted MCC is flagged"
    (let [payload (make-payload {:sale-mcc "7801" :amount 500.0})]
      (is (true? (auth/mcc-amount-restricted? payload))))))

(deftest test-mcc-amount-within-limits
  (testing "amount within limits for restricted MCC passes"
    (let [payload (make-payload {:sale-mcc "7801" :amount 50.0})]
      (is (false? (auth/mcc-amount-restricted? payload))))))

;; --- Rule 5: mcc_relation_restriction ---

(deftest test-mcc-relation-compatible
  (testing "sale MCC compatible with merchant MCC passes"
    (let [payload (make-payload {:merchant-mcc "7802" :sale-mcc "7995"})]
      (is (false? (auth/mcc-relation-restricted? payload))))))

(deftest test-mcc-relation-incompatible
  (testing "sale MCC incompatible with merchant MCC is flagged"
    (let [payload (make-payload {:merchant-mcc "7802" :sale-mcc "5411"})]
      (is (true? (auth/mcc-relation-restricted? payload))))))

(deftest test-mcc-relation-no-restriction
  (testing "merchant MCC not in relations file passes"
    (let [payload (make-payload {:merchant-mcc "9999" :sale-mcc "5411"})]
      (is (false? (auth/mcc-relation-restricted? payload))))))

;; --- Orchestrator ---

(deftest test-authorize-approved
  (testing "clean transaction is approved"
    (let [payload (make-payload {:lat 0.0 :lon 0.0
                                 :amount 10.0
                                 :sale-mcc "5411"
                                 :merchant-mcc "5411"})
          result  (auth/authorize payload)]
      (is (true? (:approved result)))
      (is (nil? (:rules_violated result))))))

(deftest test-authorize-denied-multiple-rules
  (testing "transaction violating multiple rules returns all violations"
    (let [payload (make-payload {:sale-mcc "7801"
                                 :merchant-mcc "5411"
                                 :amount 500.0
                                 :timestamp "2026-03-27T14:03:00Z"
                                 :last-tx-timestamp "2026-03-27T14:00:00Z"})
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (contains? (set (:rules_violated result)) "mcc_amount_restriction"))
      (is (contains? (set (:rules_violated result)) "mcc_relation_restriction"))
      (is (contains? (set (:rules_violated result)) "anomalous_interval")))))
```

- [ ] **Step 2: Run tests**

```bash
lein test
```

Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add test/rinha_de_backend_2026_exemplo/authorization_test.clj
git commit -m "test: add unit tests for all 5 fraud detection rules"
```

---

### Task 4: Write endpoint integration tests

**Files:**
- Create: `test/rinha_de_backend_2026_exemplo/endpoints_test.clj`

- [ ] **Step 1: Write integration tests for POST /authorizations**

Create `test/rinha_de_backend_2026_exemplo/endpoints_test.clj`:

```clojure
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

(deftest test-approved-transaction
  (testing "POST /authorizations with clean transaction returns approved"
    (let [payload  {:transaction  {:id "tx-1" :amount 10.0 :currency "BRL"
                                   :installments 1 :timestamp "2026-03-27T14:30:00Z"}
                    :environment  {:merchant {:id "m1" :name "Store" :mcc "5411"}
                                   :terminal {:id "t1" :latitude 0.0 :longitude 0.0}}
                    :context      {:sale_mcc "5411"}
                    :last_transaction nil}
          response (post-authorization payload)
          body     (json/read-str (:body response) :key-fn keyword)]
      (is (= 200 (:status response)))
      (is (true? (:approved body))))))

(deftest test-denied-transaction
  (testing "POST /authorizations with fraudulent transaction returns denied with rules"
    (let [payload  {:transaction  {:id "tx-1" :amount 500.0 :currency "BRL"
                                   :installments 1 :timestamp "2026-03-27T14:30:00Z"}
                    :environment  {:merchant {:id "m1" :name "Casino" :mcc "5411"}
                                   :terminal {:id "t1" :latitude 0.0 :longitude 0.0}}
                    :context      {:sale_mcc "7801"}
                    :last_transaction nil}
          response (post-authorization payload)
          body     (json/read-str (:body response) :key-fn keyword)]
      (is (= 200 (:status response)))
      (is (false? (:approved body)))
      (is (some #(= "mcc_amount_restriction" %) (:rules_violated body))))))

(deftest test-not-found
  (testing "unknown route returns 404"
    (let [response (endpoints/app {:request-method :get
                                    :uri            "/unknown"
                                    :headers        {}})]
      (is (= 404 (:status response))))))
```

- [ ] **Step 2: Run all tests**

```bash
lein test
```

Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add test/rinha_de_backend_2026_exemplo/endpoints_test.clj
git commit -m "test: add endpoint integration tests"
```

---

### Task 5: Docker infrastructure (Dockerfile + nginx + docker-compose)

**Files:**
- Create: `Dockerfile`
- Create: `nginx.conf`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Create Dockerfile**

```dockerfile
FROM clojure:temurin-21-lein AS builder
WORKDIR /app
COPY project.clj .
RUN lein deps
COPY . .
RUN lein uberjar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/uberjar/*-standalone.jar app.jar
EXPOSE 3000
CMD ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Create nginx.conf**

```nginx
upstream api {
    server api1:3000;
    server api2:3000;
}

server {
    listen 9999;

    location / {
        proxy_pass http://api;
    }
}
```

- [ ] **Step 3: Rewrite docker-compose.yml**

```yaml
services:
  api1:
    build: .
    environment:
      PORT: "3000"
    deploy:
      resources:
        limits:
          cpus: "0.5"
          memory: "256M"

  api2:
    build: .
    environment:
      PORT: "3000"
    deploy:
      resources:
        limits:
          cpus: "0.5"
          memory: "256M"

  nginx:
    image: nginx:alpine
    ports:
      - "9999:9999"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf
    depends_on:
      - api1
      - api2
    deploy:
      resources:
        limits:
          cpus: "0.25"
          memory: "128M"
```

Note: CPU/memory limits are placeholders — exact values TBD per contest rules.

- [ ] **Step 4: Test the Docker setup**

```bash
docker compose up --build -d
sleep 10
curl -s -X POST http://localhost:9999/authorizations \
  -H "Content-Type: application/json" \
  -d '{
    "transaction": {"id": "abc", "amount": 10.0, "currency": "BRL", "installments": 1, "timestamp": "2026-03-27T14:30:00Z"},
    "environment": {"merchant": {"id": "m1", "name": "Store", "mcc": "5411"}, "terminal": {"id": "t1", "latitude": 0.0, "longitude": 0.0}},
    "context": {"sale_mcc": "5411"},
    "last_transaction": null
  }' | jq .
docker compose down
```

Expected: `{"approved": true}` from port 9999 (through nginx).

- [ ] **Step 5: Commit**

```bash
git add Dockerfile nginx.conf docker-compose.yml
git commit -m "infra: add Dockerfile, nginx LB, and docker-compose for submission"
```

---

### Task 6: Test scripts directory (k6 + data generator placeholder)

**Files:**
- Create: `test-scripts/k6/test.js`
- Create: `test-scripts/data-generator/README.md`

- [ ] **Step 1: Create k6 test script**

Create `test-scripts/k6/test.js`:

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const testData = new SharedArray('test-data', function () {
  return JSON.parse(open('../data-generator/preview-dataset.json'));
});

export const options = {
  stages: [
    { duration: '10s', target: 10 },
    { duration: '30s', target: 50 },
    { duration: '10s', target: 0 },
  ],
};

export default function () {
  const idx = Math.floor(Math.random() * testData.length);
  const testCase = testData[idx];

  const res = http.post(
    'http://localhost:9999/authorizations',
    JSON.stringify(testCase.request),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(res, {
    'status is 200': (r) => r.status === 200,
    'correct decision': (r) => {
      const body = JSON.parse(r.body);
      if (testCase.expected.approved) {
        return body.approved === true;
      } else {
        return (
          body.approved === false &&
          JSON.stringify(body.rules_violated.sort()) ===
            JSON.stringify(testCase.expected.rules_violated.sort())
        );
      }
    },
  });
}
```

- [ ] **Step 2: Create data generator placeholder**

Create `test-scripts/data-generator/README.md`:

```markdown
# Test Data Generator

Seeded generator that produces deterministic authorization request datasets.

## Output Format

```json
[
  {
    "request": { ... },
    "expected": {
      "approved": false,
      "rules_violated": ["restricted_area", "anomalous_interval"]
    }
  }
]
```

## TODO

- Implement generator script (Babashka or standalone Clojure)
- Define seed for preview dataset
- Define coverage targets (% of requests per rule, multi-rule violations, clean transactions)
```

- [ ] **Step 3: Commit**

```bash
git add test-scripts/
git commit -m "feat: add k6 test script and data generator placeholder"
```

---

### Task 7: Clean up repo (remove sandbox artifacts)

**Files:**
- Delete: `doc/intro.md`
- Delete: `CHANGELOG.md`
- Delete: `.hgignore`
- Delete: `.env`
- Modify: `requests.http`
- Modify: `.gitignore`
- Modify: `README.md`

- [ ] **Step 1: Remove sandbox files**

```bash
rm -f doc/intro.md CHANGELOG.md .hgignore .env
rmdir db 2>/dev/null || true
```

- [ ] **Step 2: Update requests.http to match spec payload**

```http
POST http://localhost:9999/authorizations
Content-Type: application/json

{
    "transaction": {
        "id": "3fd455a5-e78f-4951-a5c9-254c4cd394ef",
        "amount": 150.00,
        "currency": "BRL",
        "installments": 3,
        "timestamp": "2026-03-27T14:30:00Z"
    },
    "environment": {
        "merchant": {
            "id": "MERC-001",
            "name": "Lojas Mel",
            "mcc": "5411"
        },
        "terminal": {
            "id": "c736ab68-87f9-4a80-9ded-043324950b59",
            "latitude": -23.5505,
            "longitude": -46.6333
        }
    },
    "context": {
        "sale_mcc": "5411"
    },
    "last_transaction": {
        "amount": 50.00,
        "timestamp": "2026-03-27T14:20:00Z",
        "terminal": {
            "latitude": -23.5600,
            "longitude": -46.6400
        }
    }
}

###

POST http://localhost:9999/authorizations
Content-Type: application/json

{
    "transaction": {
        "id": "b2c3d4e5-f6a7-8901-b234-567890abcdef",
        "amount": 10.00,
        "currency": "BRL",
        "installments": 1,
        "timestamp": "2026-03-27T14:30:00Z"
    },
    "environment": {
        "merchant": {
            "id": "MERC-002",
            "name": "Farmacia Central",
            "mcc": "5411"
        },
        "terminal": {
            "id": "d847bc79-98g0-5ba1-0eed-154435061c60",
            "latitude": 0.0,
            "longitude": 0.0
        }
    },
    "context": {
        "sale_mcc": "5411"
    },
    "last_transaction": null
}
```

- [ ] **Step 3: Update .gitignore**

Add to `.gitignore`:

```
# Test data (generated)
test-scripts/data-generator/*.json
```

- [ ] **Step 4: Update README.md**

The README should be rewritten to serve as both the challenge introduction and example submission documentation. Content:

```markdown
# Rinha de Backend 2026 — Exemplo em Clojure

Exemplo de submissão para a [Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026) — Fraud Detection Engine.

## Como rodar

```bash
docker compose up --build
```

A API estará disponível em `http://localhost:9999/authorizations`.

## Exemplo de requisição

```bash
curl -X POST http://localhost:9999/authorizations \
  -H "Content-Type: application/json" \
  -d '{
    "transaction": {
      "id": "abc-123",
      "amount": 150.00,
      "currency": "BRL",
      "installments": 3,
      "timestamp": "2026-03-27T14:30:00Z"
    },
    "environment": {
      "merchant": {"id": "m1", "name": "Loja", "mcc": "5411"},
      "terminal": {"id": "t1", "latitude": -23.5505, "longitude": -46.6333}
    },
    "context": {"sale_mcc": "5411"},
    "last_transaction": null
  }'
```

## Resposta

Aprovada:
```json
{"approved": true}
```

Negada:
```json
{"approved": false, "rules_violated": ["restricted_area", "anomalous_interval"]}
```
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: clean up sandbox artifacts, update README and requests.http"
```

---

### Task 8: Final verification

- [ ] **Step 1: Run all tests**

```bash
lein test
```

Expected: All tests pass.

- [ ] **Step 2: Build and run Docker setup**

```bash
docker compose up --build -d
sleep 10
```

- [ ] **Step 3: Test through load balancer**

```bash
curl -s -X POST http://localhost:9999/authorizations \
  -H "Content-Type: application/json" \
  -d '{
    "transaction": {"id": "abc", "amount": 10.0, "currency": "BRL", "installments": 1, "timestamp": "2026-03-27T14:30:00Z"},
    "environment": {"merchant": {"id": "m1", "name": "Store", "mcc": "5411"}, "terminal": {"id": "t1", "latitude": 0.0, "longitude": 0.0}},
    "context": {"sale_mcc": "5411"},
    "last_transaction": null
  }' | jq .
```

Expected: `{"approved": true}`

- [ ] **Step 4: Run k6 preview (once data generator produces preview dataset)**

```bash
cd test-scripts && k6 run k6/test.js
```

- [ ] **Step 5: Tear down and commit any final fixes**

```bash
docker compose down
```
