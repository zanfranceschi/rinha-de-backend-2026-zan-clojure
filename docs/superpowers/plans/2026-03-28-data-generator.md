# Data Generator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a seeded, deterministic test data generator that produces ~200 authorization requests with known expected outcomes for k6 load testing.

**Architecture:** Single namespace with scenario functions that construct requests targeting specific rule outcomes. Uses `java.util.Random` with seed 42 for determinism. Calls `authorization/authorize` to compute expected results. Two public functions: `generate-dataset` (returns EDN) and `write-dataset!` (writes JSON).

**Tech Stack:** Clojure, clojure.data.json, java.util.Random, existing authorization namespace

---

## File Structure

- **Create:** `src/rinha_de_backend_2026_exemplo/data_generator.clj` — the generator namespace
- **Create:** `test/rinha_de_backend_2026_exemplo/data_generator_test.clj` — tests for the generator
- **Generated output:** `test-scripts/data-generator/preview-dataset.json` — the dataset file (gitignored or committed, user's choice)

---

### Task 1: Scaffold namespace with PRNG helpers and base payload

**Files:**
- Create: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`
- Create: `src/rinha_de_backend_2026_exemplo/data_generator.clj`

- [ ] **Step 1: Write failing test for PRNG helper and base payload**

```clojure
(ns rinha-de-backend-2026-exemplo.data-generator-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026-exemplo.data-generator :as gen]))

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Write minimal implementation**

```clojure
(ns rinha-de-backend-2026-exemplo.data-generator
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [rinha-de-backend-2026-exemplo.authorization :as auth])
  (:import
   [java.util Random]))

(def seed 42)
(def default-num-requests 200)

;; ---------------------------------------------------------------------------
;; PRNG helpers
;; ---------------------------------------------------------------------------

(defn rand-double
  "Returns a random double in [min, max)."
  ^double [^Random rng ^double min-val ^double max-val]
  (+ min-val (* (.nextDouble rng) (- max-val min-val))))

(defn rand-int-range
  "Returns a random int in [min, max)."
  ^long [^Random rng ^long min-val ^long max-val]
  (+ min-val (.nextInt rng (- max-val min-val))))

(defn rand-nth-seq
  "Picks a random element from a sequential collection."
  [^Random rng coll]
  (let [v (vec coll)]
    (v (.nextInt rng (count v)))))

;; ---------------------------------------------------------------------------
;; Safe MCCs — MCCs that have no amount restrictions and no relation restrictions
;; Used for clean transactions.
;; ---------------------------------------------------------------------------

(def safe-merchant-mcc
  "MCC 5411 (Grocery) — has amount restriction (max 5000) but is in relation
   restrictions with known related MCCs. We use it with sale_mcc also 5411
   so relation check passes, and we keep amounts within range."
  "5411")

;; ---------------------------------------------------------------------------
;; Base payload
;; ---------------------------------------------------------------------------

(defn base-payload
  "Builds a clean payload that passes all rules.
   - Coordinates at safe location (10.0, 10.0) — far from any restricted polygon
   - Merchant and sale MCC both 5411 (grocery) — compatible relation
   - Amount between 50-4000 (within grocery max of 5000)
   - No last_transaction"
  [^Random rng ^long idx]
  {:transaction    {:id           (str "tx-" seed "-" (format "%04d" idx))
                    :amount       (rand-double rng 50.0 4000.0)
                    :currency     "BRL"
                    :installments (rand-int-range rng 1 13)
                    :timestamp    (str "2026-03-27T"
                                       (format "%02d" (rand-int-range rng 8 22))
                                       ":"
                                       (format "%02d" (rand-int-range rng 0 60))
                                       ":"
                                       (format "%02d" (rand-int-range rng 0 60))
                                       "Z")}
   :environment    {:merchant {:id   (str "m-" (format "%04d" idx))
                               :name (str "Store-" idx)
                               :mcc  safe-merchant-mcc}
                    :terminal {:id        (str "t-" (format "%04d" idx))
                               :latitude  (rand-double rng 5.0 15.0)
                               :longitude (rand-double rng 5.0 15.0)}}
   :context        {:sale_mcc safe-merchant-mcc}
   :last_transaction nil})
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: scaffold data-generator namespace with PRNG helpers and base payload"
```

---

### Task 2: Clean scenario generator

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/data_generator.clj`
- Modify: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`

- [ ] **Step 1: Write failing test**

Add to `data_generator_test.clj`:

```clojure
(deftest gen-clean-test
  (testing "gen-clean produces an approved transaction"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-clean rng 0)
          result  (auth/authorize payload)]
      (is (true? (:approved result))))))
```

Add to requires: `[rinha-de-backend-2026-exemplo.authorization :as auth]`

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-clean-test`
Expected: FAIL — `gen-clean` not found

- [ ] **Step 3: Write implementation**

Add to `data_generator.clj`:

```clojure
(defn gen-clean
  "Generates a payload that passes all authorization rules."
  [^Random rng ^long idx]
  (base-payload rng idx))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-clean-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: add gen-clean scenario for clean transactions"
```

---

### Task 3: Restricted area scenario

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/data_generator.clj`
- Modify: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`

- [ ] **Step 1: Write failing test**

Add to `data_generator_test.clj`:

```clojure
(deftest gen-restricted-area-test
  (testing "gen-restricted-area triggers restricted_area rule"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-restricted-area rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"restricted_area"} (:rules_violated result))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-restricted-area-test`
Expected: FAIL — `gen-restricted-area` not found

- [ ] **Step 3: Write implementation**

The restricted_areas.json polygons are already loaded in `authorization.clj` as `restricted-areas-list`. Each polygon is a vector of `[lon, lat]` pairs. We pick a random polygon, then pick a point inside it by averaging some of its vertices (centroid approximation).

Add to `data_generator.clj`:

```clojure
(defn- polygon-centroid
  "Computes a rough centroid of a polygon (vector of [lon lat] pairs).
   Excludes the last point (which is the same as the first in GeoJSON)."
  [polygon]
  (let [pts (butlast polygon)
        n   (count pts)]
    [(/ (reduce + (map first pts)) n)
     (/ (reduce + (map second pts)) n)]))

(defn gen-restricted-area
  "Generates a payload with terminal coordinates inside a restricted polygon.
   All other fields are clean so only restricted_area triggers."
  [^Random rng ^long idx]
  (let [polygons  auth/restricted-areas-list
        polygon   (rand-nth-seq rng polygons)
        [lon lat] (polygon-centroid polygon)
        payload   (base-payload rng idx)]
    (-> payload
        (assoc-in [:environment :terminal :latitude] lat)
        (assoc-in [:environment :terminal :longitude] lon))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-restricted-area-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: add gen-restricted-area scenario"
```

---

### Task 4: Anomalous interval scenario

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/data_generator.clj`
- Modify: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`

- [ ] **Step 1: Write failing test**

Add to `data_generator_test.clj`:

```clojure
(deftest gen-anomalous-interval-test
  (testing "gen-anomalous-interval triggers anomalous_interval rule"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-anomalous-interval rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"anomalous_interval"} (:rules_violated result))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-anomalous-interval-test`
Expected: FAIL — `gen-anomalous-interval` not found

- [ ] **Step 3: Write implementation**

Add to `data_generator.clj`:

```clojure
(defn gen-anomalous-interval
  "Generates a payload where last_transaction timestamp is < 5 minutes
   before current transaction. Terminal locations are the same (safe coords)
   so anomalous_travel_speed does NOT trigger."
  [^Random rng ^long idx]
  (let [payload   (base-payload rng idx)
        ts        (-> payload :transaction :timestamp)
        instant   (java.time.Instant/parse ts)
        ;; 1 to 4 minutes before current transaction
        mins-back (rand-int-range rng 1 5)
        last-ts   (.toString (.minusSeconds instant (* mins-back 60)))
        terminal  (-> payload :environment :terminal)]
    (assoc payload :last_transaction
           {:timestamp last-ts
            :terminal  {:latitude  (:latitude terminal)
                        :longitude (:longitude terminal)}})))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-anomalous-interval-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: add gen-anomalous-interval scenario"
```

---

### Task 5: Anomalous travel speed scenario

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/data_generator.clj`
- Modify: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`

- [ ] **Step 1: Write failing test**

Add to `data_generator_test.clj`:

```clojure
(deftest gen-anomalous-travel-speed-test
  (testing "gen-anomalous-travel-speed triggers anomalous_travel_speed rule"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-anomalous-travel-speed rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"anomalous_travel_speed"} (:rules_violated result))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-anomalous-travel-speed-test`
Expected: FAIL — `gen-anomalous-travel-speed` not found

- [ ] **Step 3: Write implementation**

Place the current terminal at safe coords (10,10) and the last transaction at a far location (e.g., -23.55, -46.63 — Sao Paulo) just 10 seconds ago. The distance is ~6000km in 10s which is absurdly fast. The time gap is >5 minutes expressed in negative (we use a gap >5min to avoid also triggering anomalous_interval).

Actually, to avoid triggering anomalous_interval, we need the gap to be >= 5 minutes. But to trigger anomalous_travel_speed, we need the speed to exceed thresholds. Let's use 6 minutes gap with ~6000km distance. Speed = 6000 / 0.1h = 60000 km/h, well above 700 km/h threshold.

Wait — 6 minutes = 360 seconds. Speed = (6000 / 360) * 3600 = 60000 km/h. Still very fast.

Add to `data_generator.clj`:

```clojure
(defn gen-anomalous-travel-speed
  "Generates a payload where the cardholder traveled impossibly fast.
   Current terminal at safe coords, last transaction at a distant location
   6 minutes ago (>= 5 min so anomalous_interval does NOT trigger)."
  [^Random rng ^long idx]
  (let [payload   (base-payload rng idx)
        ts        (-> payload :transaction :timestamp)
        instant   (java.time.Instant/parse ts)
        ;; 6 minutes back — just above the 5-minute anomalous_interval threshold
        last-ts   (.toString (.minusSeconds instant 360))
        ;; Far-away location: ~6000km from safe coords at (10, 10)
        far-lat   (rand-double rng -25.0 -22.0)
        far-lon   (rand-double rng -48.0 -45.0)]
    (assoc payload :last_transaction
           {:timestamp last-ts
            :terminal  {:latitude far-lat :longitude far-lon}})))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-anomalous-travel-speed-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: add gen-anomalous-travel-speed scenario"
```

---

### Task 6: MCC amount restriction scenario

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/data_generator.clj`
- Modify: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`

- [ ] **Step 1: Write failing test**

Add to `data_generator_test.clj`:

```clojure
(deftest gen-mcc-amount-restriction-test
  (testing "gen-mcc-amount-restriction triggers mcc_amount_restriction rule"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-mcc-amount-restriction rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"mcc_amount_restriction"} (:rules_violated result))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-mcc-amount-restriction-test`
Expected: FAIL — `gen-mcc-amount-restriction` not found

- [ ] **Step 3: Write implementation**

Pick an MCC that has amount restrictions. For MCCs with `max_amount`, set amount above max. For MCCs with `min_amount`, set amount below min. Must also ensure the merchant MCC and sale MCC are compatible (use same MCC for both, or use a merchant MCC that has the sale MCC in its relation set).

Strategy: Pick MCCs that have max_amount (like 7801, max 100) — use the same MCC for merchant and sale to avoid relation restriction. The merchant MCC must either be in the relation restrictions file with the sale MCC as related, OR not in the file at all (which returns false for relation check), OR be the same as sale MCC.

For MCCs like 7801 — merchant MCC 7801 is in the relation file, and sale_mcc 7801 == merchant_mcc 7801, so relation check passes. Amount 150 > max 100, so amount restriction triggers.

Add to `data_generator.clj`:

```clojure
(def mccs-with-max
  "MCCs from mccs_restrictions.json that have a max_amount."
  (filterv :max_amount auth/mccs-restrictions))

(def mccs-with-min
  "MCCs from mccs_restrictions.json that have a min_amount."
  (filterv :min_amount auth/mccs-restrictions))

(defn gen-mcc-amount-restriction
  "Generates a payload that violates mcc_amount_restriction.
   Picks a restricted MCC randomly and sets the amount above max or below min.
   Uses the same MCC for merchant and sale to avoid relation restriction."
  [^Random rng ^long idx]
  (let [payload    (base-payload rng idx)
        ;; Alternate between max and min violations
        use-max?   (.nextBoolean rng)
        mcc-entry  (if (and use-max? (seq mccs-with-max))
                     (rand-nth-seq rng mccs-with-max)
                     (if (seq mccs-with-min)
                       (rand-nth-seq rng mccs-with-min)
                       (rand-nth-seq rng mccs-with-max)))
        mcc        (:mcc mcc-entry)
        amount     (if (:max_amount mcc-entry)
                     (rand-double rng
                                  (+ (:max_amount mcc-entry) 0.01)
                                  (+ (:max_amount mcc-entry) 500.0))
                     (rand-double rng 0.01 (- (:min_amount mcc-entry) 0.01)))]
    (-> payload
        (assoc-in [:transaction :amount] amount)
        (assoc-in [:environment :merchant :mcc] mcc)
        (assoc-in [:context :sale_mcc] mcc))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-mcc-amount-restriction-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: add gen-mcc-amount-restriction scenario"
```

---

### Task 7: MCC relation restriction scenario

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/data_generator.clj`
- Modify: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`

- [ ] **Step 1: Write failing test**

Add to `data_generator_test.clj`:

```clojure
(deftest gen-mcc-relation-restriction-test
  (testing "gen-mcc-relation-restriction triggers mcc_relation_restriction rule"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-mcc-relation-restriction rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (some #{"mcc_relation_restriction"} (:rules_violated result))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-mcc-relation-restriction-test`
Expected: FAIL — `gen-mcc-relation-restriction` not found

- [ ] **Step 3: Write implementation**

Pick a merchant MCC from the relation restrictions file. Build the allowed set (merchant + related MCCs). Then pick a sale MCC that is NOT in the allowed set. Must also ensure the chosen sale MCC doesn't trigger mcc_amount_restriction — use a safe amount.

Add to `data_generator.clj`:

```clojure
(def all-mccs-in-relations
  "Set of all MCCs that appear anywhere in the relation restrictions file."
  (into #{}
        (concat
         (map :mcc auth/mcc-relation-restrictions)
         (mapcat (fn [r] (map :mcc (:related r))) auth/mcc-relation-restrictions))))

(defn gen-mcc-relation-restriction
  "Generates a payload that violates mcc_relation_restriction.
   Picks a merchant MCC from relation restrictions, then picks a sale MCC
   that is NOT in the allowed set. Uses a safe amount to avoid amount restriction."
  [^Random rng ^long idx]
  (let [payload     (base-payload rng idx)
        ;; Pick a random merchant from relation restrictions
        merchant-r  (rand-nth-seq rng auth/mcc-relation-restrictions)
        merchant-mcc (:mcc merchant-r)
        allowed     (conj (set (map :mcc (:related merchant-r))) merchant-mcc)
        ;; Pick a sale MCC that is NOT in the allowed set and not amount-restricted
        ;; Use "9999" as a safe unrelated MCC — it's not in any restriction file
        sale-mcc    "9999"]
    (-> payload
        (assoc-in [:environment :merchant :mcc] merchant-mcc)
        (assoc-in [:context :sale_mcc] sale-mcc)
        ;; Use a safe amount that won't trigger amount restriction
        ;; (9999 is not in mccs_restrictions.json)
        (assoc-in [:transaction :amount] (rand-double rng 10.0 100.0)))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-mcc-relation-restriction-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: add gen-mcc-relation-restriction scenario"
```

---

### Task 8: Multi-rule scenario

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/data_generator.clj`
- Modify: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`

- [ ] **Step 1: Write failing test**

Add to `data_generator_test.clj`:

```clojure
(deftest gen-multi-rule-test
  (testing "gen-multi-rule triggers at least 2 rules"
    (let [rng     (java.util.Random. 42)
          payload (gen/gen-multi-rule rng 0)
          result  (auth/authorize payload)]
      (is (false? (:approved result)))
      (is (>= (count (:rules_violated result)) 2)))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-multi-rule-test`
Expected: FAIL — `gen-multi-rule` not found

- [ ] **Step 3: Write implementation**

Combine violations. Strategies:
- restricted_area + anomalous_interval: coords inside polygon, last_transaction < 5min ago at same location
- mcc_amount_restriction + mcc_relation_restriction: merchant MCC from relations, incompatible sale MCC that also has amount limits
- restricted_area + anomalous_travel_speed: coords inside polygon, last transaction far away and recent (but >= 5min)

We rotate through these combos.

Add to `data_generator.clj`:

```clojure
(defn- gen-multi-restricted-area+interval
  "Restricted area + anomalous interval."
  [^Random rng ^long idx]
  (let [payload   (gen-restricted-area rng idx)
        ts        (-> payload :transaction :timestamp)
        instant   (java.time.Instant/parse ts)
        mins-back (rand-int-range rng 1 5)
        last-ts   (.toString (.minusSeconds instant (* mins-back 60)))
        terminal  (-> payload :environment :terminal)]
    (assoc payload :last_transaction
           {:timestamp last-ts
            :terminal  {:latitude  (:latitude terminal)
                        :longitude (:longitude terminal)}})))

(defn- gen-multi-amount+relation
  "MCC amount restriction + MCC relation restriction.
   Pick a merchant MCC from relations. Pick a sale MCC that is NOT in the
   allowed set AND has amount restrictions. Use an amount that violates."
  [^Random rng ^long idx]
  (let [payload      (base-payload rng idx)
        ;; Use merchant MCC 7802 (horse racing), allowed sale: {7802, 7995, 7801, 5813}
        ;; Use sale MCC 5411 (grocery, max 5000) — not in allowed set
        ;; Amount > 5000 triggers amount restriction
        merchant-mcc "7802"
        sale-mcc     "5411"
        amount       (rand-double rng 5001.0 8000.0)]
    (-> payload
        (assoc-in [:environment :merchant :mcc] merchant-mcc)
        (assoc-in [:context :sale_mcc] sale-mcc)
        (assoc-in [:transaction :amount] amount))))

(defn- gen-multi-restricted-area+speed
  "Restricted area + anomalous travel speed.
   Terminal inside polygon, last transaction far away >= 5 min ago."
  [^Random rng ^long idx]
  (let [payload  (gen-restricted-area rng idx)
        ts       (-> payload :transaction :timestamp)
        instant  (java.time.Instant/parse ts)
        last-ts  (.toString (.minusSeconds instant 360))
        ;; Far away location
        far-lat  (rand-double rng 30.0 40.0)
        far-lon  (rand-double rng 30.0 40.0)]
    (assoc payload :last_transaction
           {:timestamp last-ts
            :terminal  {:latitude far-lat :longitude far-lon}})))

(def ^:private multi-rule-generators
  [gen-multi-restricted-area+interval
   gen-multi-amount+relation
   gen-multi-restricted-area+speed])

(defn gen-multi-rule
  "Generates a payload that violates 2-3 rules by rotating through
   multi-rule generator combinations."
  [^Random rng ^long idx]
  (let [gen-fn (rand-nth-seq rng multi-rule-generators)]
    (gen-fn rng idx)))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/gen-multi-rule-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: add gen-multi-rule scenario for multi-rule violations"
```

---

### Task 9: generate-dataset and write-dataset! public functions

**Files:**
- Modify: `src/rinha_de_backend_2026_exemplo/data_generator.clj`
- Modify: `test/rinha_de_backend_2026_exemplo/data_generator_test.clj`

- [ ] **Step 1: Write failing test for generate-dataset**

Add to `data_generator_test.clj`:

```clojure
(deftest generate-dataset-test
  (testing "generate-dataset returns a vector of ~200 entries with :request and :expected"
    (let [dataset (gen/generate-dataset)]
      (is (vector? dataset))
      (is (= gen/default-num-requests (count dataset)))
      (doseq [entry dataset]
        (is (map? (:request entry)))
        (is (map? (:expected entry)))
        (is (contains? (:expected entry) :approved)))))

  (testing "generate-dataset is deterministic — same output each time"
    (let [ds1 (gen/generate-dataset)
          ds2 (gen/generate-dataset)]
      (is (= ds1 ds2))))

  (testing "dataset contains a mix of approved and denied"
    (let [dataset   (gen/generate-dataset)
          approved  (filter #(-> % :expected :approved) dataset)
          denied    (remove #(-> % :expected :approved) dataset)]
      (is (pos? (count approved)))
      (is (pos? (count denied))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/generate-dataset-test`
Expected: FAIL — `generate-dataset` not found

- [ ] **Step 3: Write implementation**

Add to `data_generator.clj`:

```clojure
(def ^:private scenario-distribution
  "Distribution of scenarios. Weights determine how many of each ~200 total.
   Clean ~40%, each single-rule ~10%, multi-rule ~10%."
  [{:generator gen-clean                  :weight 40}
   {:generator gen-restricted-area        :weight 10}
   {:generator gen-anomalous-interval     :weight 10}
   {:generator gen-anomalous-travel-speed :weight 10}
   {:generator gen-mcc-amount-restriction :weight 10}
   {:generator gen-mcc-relation-restriction :weight 10}
   {:generator gen-multi-rule             :weight 10}])

(defn- build-scenario-list
  "Expands scenario-distribution into a flat list of generator fns
   proportional to weights, totaling num-requests."
  [num-requests]
  (let [total-weight (reduce + (map :weight scenario-distribution))
        expanded     (mapcat (fn [{:keys [generator weight]}]
                               (let [n (Math/round (* (/ (double weight) total-weight)
                                                      num-requests))]
                                 (repeat n generator)))
                             scenario-distribution)
        ;; Adjust to exact count — pad or trim with clean
        diff         (- num-requests (count expanded))]
    (cond
      (pos? diff)  (concat expanded (repeat diff gen-clean))
      (neg? diff)  (take num-requests expanded)
      :else        expanded)))

(defn generate-dataset
  "Generates the full dataset as a vector of {:request ... :expected ...} maps.
   Uses hardcoded seed 42 for determinism."
  ([] (generate-dataset default-num-requests))
  ([num-requests]
   (let [rng        (Random. seed)
         scenarios  (vec (build-scenario-list num-requests))
         ;; Shuffle scenarios using the seeded RNG for variety in ordering
         shuffled   (let [arr (java.util.ArrayList. scenarios)]
                      (java.util.Collections/shuffle arr rng)
                      (vec arr))]
     (mapv (fn [idx gen-fn]
             (let [request  (gen-fn rng idx)
                   expected (auth/authorize request)]
               {:request  request
                :expected expected}))
           (range num-requests)
           shuffled))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/generate-dataset-test`
Expected: PASS

- [ ] **Step 5: Write failing test for write-dataset!**

Add to `data_generator_test.clj`:

```clojure
(deftest write-dataset!-test
  (testing "write-dataset! writes valid JSON to preview-dataset.json"
    (let [dataset    (gen/generate-dataset)
          ;; Use a temp file to avoid polluting the real output
          tmp-file   (java.io.File/createTempFile "test-dataset" ".json")
          tmp-path   (.getAbsolutePath tmp-file)]
      (try
        (gen/write-dataset! dataset tmp-path)
        (let [content   (slurp tmp-path)
              parsed    (json/read-str content :key-fn keyword)]
          (is (vector? parsed))
          (is (= (count dataset) (count parsed)))
          ;; Check first entry has string keys in the JSON (parsed back as keywords)
          (is (contains? (first parsed) :request))
          (is (contains? (first parsed) :expected)))
        (finally
          (.delete tmp-file))))))
```

Add `[clojure.data.json :as json]` to test requires.

- [ ] **Step 6: Run test to verify it fails**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test/write-dataset!-test`
Expected: FAIL — `write-dataset!` not found

- [ ] **Step 7: Write implementation**

Add to `data_generator.clj`:

```clojure
(def default-output-path "test-scripts/data-generator/preview-dataset.json")

(defn write-dataset!
  "Writes the dataset to a JSON file. Keywords are converted to strings."
  ([dataset] (write-dataset! dataset default-output-path))
  ([dataset path]
   (spit path (json/write-str dataset))
   (println (str "Wrote " (count dataset) " entries to " path))))
```

- [ ] **Step 8: Run all tests to verify everything passes**

Run: `lein test :only rinha-de-backend-2026-exemplo.data-generator-test`
Expected: All tests PASS

- [ ] **Step 9: Commit**

```bash
git add src/rinha_de_backend_2026_exemplo/data_generator.clj test/rinha_de_backend_2026_exemplo/data_generator_test.clj
git commit -m "feat: add generate-dataset and write-dataset! public functions"
```

---

### Task 10: Generate the preview dataset and final verification

**Files:**
- Generated: `test-scripts/data-generator/preview-dataset.json`

- [ ] **Step 1: Generate the preview dataset via REPL or lein run**

Run in a REPL or via:

```bash
lein run -m clojure.main -e "(require '[rinha-de-backend-2026-exemplo.data-generator :as gen]) (gen/write-dataset! (gen/generate-dataset))"
```

Expected: `Wrote 200 entries to test-scripts/data-generator/preview-dataset.json`

- [ ] **Step 2: Verify the generated file is valid JSON**

```bash
python3 -c "import json; d=json.load(open('test-scripts/data-generator/preview-dataset.json')); print(f'{len(d)} entries'); print('First:', json.dumps(d[0], indent=2)[:200])"
```

Expected: 200 entries, first entry looks like a valid request/expected pair

- [ ] **Step 3: Run all project tests to ensure nothing is broken**

Run: `lein test`
Expected: All tests PASS (existing + new data generator tests)

- [ ] **Step 4: Commit the generated dataset**

```bash
git add test-scripts/data-generator/preview-dataset.json
git commit -m "feat: generate preview-dataset.json with seed 42"
```
