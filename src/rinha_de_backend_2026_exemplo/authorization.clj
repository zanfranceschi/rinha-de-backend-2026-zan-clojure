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

(defn in-restricted-area? [authorization-request]
  (let [{:keys [latitude longitude]}
        (-> authorization-request :environment :terminal)]
    (boolean (and latitude longitude (some #(geo/point-in-polygon? [longitude latitude] %) restricted-areas-list)))))

(defn anomalous-travel-speed? [auth-request]
  (if-let [last-tx (:last_transaction auth-request)]
    (let [current-tx-timestamp  (java.time.Instant/parse (-> auth-request :transaction :timestamp))
          {:keys [latitude longitude]}     (-> auth-request :environment :terminal)
          last-tx-terminal      (:terminal last-tx)
          last-tx-lat           (:latitude last-tx-terminal)
          last-tx-lon           (:longitude last-tx-terminal)
          last-tx-distance-km   (geo/equirectangular-km-distance
                                 {:lat latitude
                                  :lon longitude}
                                 {:lat last-tx-lat
                                  :lon last-tx-lon})
          last-tx-timestamp     (java.time.Instant/parse (:timestamp last-tx))
          last-tx-interval-secs (.toSeconds
                                 (Duration/between
                                  last-tx-timestamp
                                  current-tx-timestamp))
          distance-thresholds   [{:max-km    10
                                  :max-speed 15}
                                 {:max-km    50
                                  :max-speed 60}
                                 {:max-km    200
                                  :max-speed 120}
                                 {:max-km    1000
                                  :max-speed 350}
                                 {:max-km    ##Inf
                                  :max-speed 700}]
          speed-kmh             (if (> last-tx-interval-secs 0)
                                  (* (/ last-tx-distance-km last-tx-interval-secs) 3600.0)
                                  0)
          range                 (first (filter #(<= last-tx-distance-km (:max-km %)) distance-thresholds))]
      (boolean (and range (> speed-kmh (:max-speed range)))))
    false))

(defn anomalous-interval? [auth-request]
  (if-let [last-tx (:last_transaction auth-request)]
    (let [current-tx-timestamp  (java.time.Instant/parse (-> auth-request :transaction :timestamp))
          last-tx-timestamp     (java.time.Instant/parse (:timestamp last-tx))
          last-tx-interval-mins (.toMinutes
                                 (Duration/between
                                  last-tx-timestamp
                                  current-tx-timestamp))]
      (< last-tx-interval-mins 5))
    false))

(defn mcc-amount-restricted? [authorization-request]
  (let [tx-amount              (-> authorization-request :transaction :amount)
        sale-mcc               (-> authorization-request :context :sale_mcc)
        mcc-restrictions       (->> mccs-restrictions
                                    (filter (fn [restriction]
                                              (= (:mcc restriction) sale-mcc)))
                                    first)
        has-restrictions?      (boolean (seq mcc-restrictions))
        max-amount-restricted? (and has-restrictions?
                                    (:max_amount mcc-restrictions)
                                    (> tx-amount (:max_amount mcc-restrictions)))
        min-amount-restricted? (and has-restrictions?
                                    (:min_amount mcc-restrictions)
                                    (< tx-amount (:min_amount mcc-restrictions)))]
    (boolean (or max-amount-restricted? min-amount-restricted?))))

(defn mcc-relation-restricted? [authorization-request]
  (let [sale-mcc                     (-> authorization-request :context :sale_mcc)
        merchant-mcc                 (-> authorization-request :environment :merchant :mcc)
        mcc-relation-restriction     (->> mcc-relation-restrictions
                                          (filter (fn [restriction]
                                                    (= (:mcc restriction) merchant-mcc)))
                                          first)]
    (if (nil? mcc-relation-restriction)
      false
      (let [allowed-mccs (conj (set (map :mcc (:related mcc-relation-restriction))) merchant-mcc)]
        (boolean
         (not
          (some #{sale-mcc} allowed-mccs)))))))

(defn authorize [payload]
  (let [rules [["restricted_area"        in-restricted-area?]
               ["anomalous_travel_speed" anomalous-travel-speed?]
               ["anomalous_interval"     anomalous-interval?]
               ["mcc_amount_restriction"  mcc-amount-restricted?]
               ["mcc_relation_restriction" mcc-relation-restricted?]]
        violated (into []
                       (comp (filter (fn [[_ check-fn]] (check-fn payload)))
                             (map first))
                       rules)]
    (if (empty? violated)
      {:approved true}
      {:approved false :rules_violated violated})))
