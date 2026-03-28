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
;; Safe MCCs
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

;; ---------------------------------------------------------------------------
;; Scenario: clean
;; ---------------------------------------------------------------------------

(defn gen-clean
  "Generates a payload that passes all authorization rules."
  [^Random rng ^long idx]
  (base-payload rng idx))

;; ---------------------------------------------------------------------------
;; Scenario: restricted_area
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Scenario: anomalous_interval
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Scenario: anomalous_travel_speed
;; ---------------------------------------------------------------------------

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
