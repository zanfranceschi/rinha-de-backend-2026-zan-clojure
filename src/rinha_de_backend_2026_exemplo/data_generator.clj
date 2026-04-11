(ns rinha-de-backend-2026-exemplo.data-generator
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [rinha-de-backend-2026-exemplo.normalization :as norm])
  (:import
   [java.util Random]))

(def reference-seed 42)
(def payload-seed 4242)

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
;; Normalization config + MCC risk (loaded for vector generation)
;; ---------------------------------------------------------------------------

(def normalization-config
  (json/read-str (slurp (io/resource "normalization.json")) :key-fn keyword))

(def mcc-risk
  (json/read-str (slurp (io/resource "mcc_risk.json"))))

;; ---------------------------------------------------------------------------
;; Merchant pools
;; ---------------------------------------------------------------------------

(def merchant-pool
  ["MERC-001" "MERC-002" "MERC-003" "MERC-004" "MERC-005"
   "MERC-010" "MERC-011" "MERC-012" "MERC-013" "MERC-014"])

(def mcc-pool (vec (keys mcc-risk)))

;; ---------------------------------------------------------------------------
;; Profile-based request generation
;; ---------------------------------------------------------------------------

(defn- gen-timestamp
  "Generate a timestamp string for a given hour range."
  [^Random rng year month day hour-min hour-max]
  (let [hour (rand-int-range rng hour-min hour-max)
        min  (rand-int-range rng 0 60)
        sec  (rand-int-range rng 0 60)]
    (format "%04d-%02d-%02dT%02d:%02d:%02dZ" year month day hour min sec)))

(defn- gen-last-transaction
  "Generate a last_transaction or nil.
   ~20% chance of nil (new customer / no history)."
  [^Random rng profile requested-at-str]
  (if (< (.nextDouble rng) 0.2)
    nil
    (let [requested-at (java.time.Instant/parse requested-at-str)
          minutes-back (case profile
                         :legit      (rand-int-range rng 30 720)
                         :fraud      (rand-int-range rng 1 10)
                         :borderline (rand-int-range rng 5 120))
          last-ts      (.toString (.minusSeconds requested-at (* minutes-back 60)))
          km           (case profile
                         :legit      (rand-double rng 0.0 20.0)
                         :fraud      (rand-double rng 200.0 1000.0)
                         :borderline (rand-double rng 20.0 300.0))]
      {:timestamp       last-ts
       :km_from_current km})))

(defn generate-request
  "Generate a coherent authorization request for a given profile (:legit, :fraud, :borderline).
   Fields are internally consistent based on the profile."
  [^Random rng profile]
  (let [;; Core transaction values by profile
        amount       (case profile
                       :legit      (rand-double rng 10.0 500.0)
                       :fraud      (rand-double rng 2000.0 10000.0)
                       :borderline (rand-double rng 400.0 3000.0))
        installments (case profile
                       :legit      (rand-int-range rng 1 4)
                       :fraud      (rand-int-range rng 6 13)
                       :borderline (rand-int-range rng 3 8))

        ;; Time — fraud tends to be nighttime
        hour-min     (case profile :legit 8  :fraud 0  :borderline 6)
        hour-max     (case profile :legit 21 :fraud 7  :borderline 23)
        requested-at (gen-timestamp rng 2026 3 (rand-int-range rng 10 28) hour-min hour-max)

        ;; Customer
        avg-amount   (case profile
                       :legit      (rand-double rng (/ amount 0.5) (* amount 2.0))
                       :fraud      (rand-double rng 50.0 300.0)
                       :borderline (rand-double rng 100.0 500.0))
        tx-count     (case profile
                       :legit      (rand-int-range rng 1 6)
                       :fraud      (rand-int-range rng 8 21)
                       :borderline (rand-int-range rng 4 12))
        ;; Known merchants: legit usually knows the merchant, fraud doesn't
        known-count  (rand-int-range rng 2 6)
        known-merchants (mapv #(str "MERC-" (format "%03d" %))
                              (repeatedly known-count #(rand-int-range rng 1 20)))

        ;; Merchant
        merchant-id  (case profile
                       :legit      (rand-nth-seq rng known-merchants)
                       :fraud      (str "MERC-" (format "%03d" (rand-int-range rng 50 100)))
                       :borderline (if (< (.nextDouble rng) 0.5)
                                     (rand-nth-seq rng known-merchants)
                                     (str "MERC-" (format "%03d" (rand-int-range rng 30 60)))))
        mcc          (case profile
                       :legit      (rand-nth-seq rng ["5411" "5812" "5912" "5311"])
                       :fraud      (rand-nth-seq rng ["7995" "7801" "7802"])
                       :borderline (rand-nth-seq rng mcc-pool))
        merch-avg    (case profile
                       :legit      (rand-double rng 30.0 500.0)
                       :fraud      (rand-double rng 20.0 100.0)
                       :borderline (rand-double rng 50.0 300.0))

        ;; Terminal
        is-online    (case profile
                       :legit      (< (.nextDouble rng) 0.3)
                       :fraud      (< (.nextDouble rng) 0.8)
                       :borderline (< (.nextDouble rng) 0.5))
        card-present (if is-online false (< (.nextDouble rng) 0.9))
        km-from-home (case profile
                       :legit      (rand-double rng 0.0 50.0)
                       :fraud      (rand-double rng 200.0 1000.0)
                       :borderline (rand-double rng 30.0 400.0))

        ;; Last transaction
        last-tx      (gen-last-transaction rng profile requested-at)]

    {:id               (str "tx-" (.nextInt rng Integer/MAX_VALUE))
     :transaction      {:amount       amount
                        :installments installments
                        :requested_at requested-at}
     :customer         {:avg_amount      avg-amount
                        :tx_count_24h    tx-count
                        :known_merchants known-merchants}
     :merchant         {:id         merchant-id
                        :mcc        mcc
                        :avg_amount merch-avg}
     :terminal         {:is_online    is-online
                        :card_present card-present
                        :km_from_home km-from-home}
     :last_transaction last-tx}))

;; ---------------------------------------------------------------------------
;; Profile distribution
;; ---------------------------------------------------------------------------

(defn- pick-profile
  "Pick a profile based on distribution: 65% legit, 25% fraud, 10% borderline."
  [^Random rng]
  (let [r (.nextDouble rng)]
    (cond
      (< r 0.65) :legit
      (< r 0.90) :fraud
      :else      :borderline)))

;; ---------------------------------------------------------------------------
;; Reference dataset generation
;; ---------------------------------------------------------------------------

(defn generate-reference-dataset
  "Generate N labeled reference vectors. Uses reference-seed for determinism."
  [n]
  (let [rng (Random. reference-seed)]
    (mapv (fn [_]
            (let [profile (pick-profile rng)
                  request (generate-request rng profile)
                  vector  (norm/normalize request normalization-config mcc-risk)
                  label   (case profile
                            :legit      "legit"
                            :fraud      "fraud"
                            :borderline (if (< (.nextDouble rng) 0.5) "fraud" "legit"))]
              {:vector vector
               :label  label}))
          (range n))))

;; ---------------------------------------------------------------------------
;; Test payload generation
;; ---------------------------------------------------------------------------

(defn generate-test-payloads
  "Generate M test request payloads. Uses payload-seed (different from reference-seed)
   so test payloads are NOT in the reference dataset."
  [m]
  (let [rng (Random. payload-seed)]
    (mapv (fn [_]
            (let [profile (pick-profile rng)]
              (generate-request rng profile)))
          (range m))))

;; ---------------------------------------------------------------------------
;; Write functions
;; ---------------------------------------------------------------------------

(defn write-reference-dataset!
  "Generate and write references.json."
  ([n] (write-reference-dataset! n "resources/references.json"))
  ([n path]
   (let [dataset (generate-reference-dataset n)]
     (spit path (json/write-str dataset))
     (println (str "Wrote " (count dataset) " reference vectors to " path)))))

(defn write-test-payloads!
  "Generate and write test payloads JSON."
  ([m] (write-test-payloads! m "test-scripts/data-generator/test-payloads.json"))
  ([m path]
   (let [payloads (generate-test-payloads m)]
     (spit path (json/write-str payloads))
     (println (str "Wrote " (count payloads) " test payloads to " path)))))
