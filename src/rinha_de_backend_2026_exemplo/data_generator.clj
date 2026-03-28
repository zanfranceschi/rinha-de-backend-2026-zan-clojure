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
