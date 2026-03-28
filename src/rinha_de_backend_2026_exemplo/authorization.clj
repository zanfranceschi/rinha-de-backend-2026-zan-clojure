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
  (let [{:keys [lat lon]}
        (-> authorization-request :environment :terminal)]
    (boolean (and lat lon (some #(geo/point-in-polygon? [lon lat] %) restricted-areas-list)))))

(defn annomalous-distance-time? [auth-request]
  (if-let [last-tx (:last-transaction auth-request)]
    (let [current-tx-timestamp  (java.time.Instant/parse (-> auth-request :transaction :timestamp))
          {:keys [lat lon]}     (-> auth-request :environment :terminal)
          last-tx-terminal      (-> last-tx :environment :terminal)
          last-tx-lat           (:lat last-tx-terminal)
          last-tx-lon           (:lon last-tx-terminal)
          last-tx-distance-km   (geo/equirectangular-km-distance
                                 {:lat lat
                                  :lon lon}
                                 {:lat last-tx-lat
                                  :lon last-tx-lon})
          last-tx-timestamp     (java.time.Instant/parse (-> last-tx :transaction :timestamp))
          last-tx-interval-secs (.toSeconds
                                 (Duration/between
                                  last-tx-timestamp
                                  current-tx-timestamp))
          distance-thresholds   [{:name      :micro
                                  :max-km    1
                                  :max-speed 15}
                                 {:name      :urban
                                  :max-km    5
                                  :max-speed 40}
                                 {:name      :suburban
                                  :max-km    50
                                  :max-speed 100}
                                 {:name      :regional
                                  :max-km    300
                                  :max-speed 140}
                                 {:name      :domestic-flight
                                  :max-km    1500
                                  :max-speed 450}
                                 {:name      :intercontinental
                                  :max-km    99999
                                  :max-speed 700}]
          speed-kmh             (if (> last-tx-interval-secs 0)
                                  (* (/ last-tx-distance-km last-tx-interval-secs) 3600.0)
                                  0)
          range                 (first (filter #(<= last-tx-distance-km (:max-km %)) distance-thresholds))]
      (boolean (and range (> speed-kmh (:max-speed range)))))
    false))

(defn annomalous-interval? [auth-request]
  (if-let [last-tx (:last-transaction auth-request)]
    (let [current-tx-timestamp  (java.time.Instant/parse (-> auth-request :transaction :timestamp))
          last-tx-timestamp     (java.time.Instant/parse (-> last-tx :transaction :timestamp))
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
  (let [sale-mcc                     (-> authorization-request :context :sale-mcc)
        merchant-mcc                 (-> authorization-request :environment :merchant :mcc)
        mcc-relation-restriction     (->> mcc-relation-restrictions
                                          (filter (fn [restriction]
                                                    (= (:mcc restriction) merchant-mcc)))
                                          first)
        allowed-mccs                 (map :mcc (conj (:related mcc-relation-restriction) merchant-mcc))]
    (boolean
     (not
      (some #{sale-mcc} allowed-mccs)))))

(defn authorize [payload]
  {:approved (not (or (in-restricted-area? payload)
                      (annomalous-distance-time? payload)
                      (annomalous-interval? payload)
                      (mcc-restricted? payload)
                      (mcc-relation-restricted? payload)))})
