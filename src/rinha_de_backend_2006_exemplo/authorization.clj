(ns rinha-de-backend-2006-exemplo.authorization
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string]
            [rinha-de-backend-2006-exemplo.misc :as misc]
            [taoensso.telemere :as tel])
  (:import [java.time Duration Instant]))

;; https://gemini.google.com/share/f83641d179d9
;; too short purchase request intervals for the same card
;; many installments for small values
;; long distances in short periods of time
;; underage vs mcc
;; change in entryMode/txChannel for the same hardware
;; sale mcc vs merchant mcc

;; https://geojson.io/#map=4.14/-20.46/-49.04
;; https://geojson.io/next/

(def restricted-areas-list
  (let [coords (json/read-str
                (slurp (io/resource "restricted_areas.json")) :key-fn keyword)]
    (map (fn [feature]
           (-> feature :geometry :coordinates first))
         (:features coords))))

(defn in-restricted-area? [authorization-request restricted-areas]
  (let [{:keys [lat lon]}
        (-> authorization-request :environment :terminal)]
    (when (and lat lon (some #(misc/point-in-polygon? [lon lat] %) restricted-areas))
      {:code    :restricted-area
       :message "Transaction originated from a restricted geographic area"})))

(defn annomalous-distance-time? [auth-request]
  (if-let [last-tx (:last-transaction auth-request)]
    (let [current-tx-timestamp (java.time.Instant/parse (-> auth-request :transaction :timestamp))
          {:keys [lat lon]}    (-> auth-request :environment :terminal)
          _card-token          (-> auth-request :environment :card :token)
          last-tx-terminal     (-> last-tx :environment :terminal)
          last-tx-lat          (:lat last-tx-terminal)
          last-tx-lon          (:lon last-tx-terminal)
          last-tx-distance-km  (misc/haversine-km-distance
                                {:lat lat
                                 :lon lon}
                                {:lat last-tx-lat
                                 :lon last-tx-lon})
          last-tx-timestamp    (java.time.Instant/parse (-> last-tx :transaction :timestamp))
          last-tx-interval-ms  (.toMillis
                                (Duration/between
                                 last-tx-timestamp
                                 current-tx-timestamp))
          distance-thresholds  [{:name      :micro
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
          speed-kmh            (if (> last-tx-interval-ms 0)
                                 (* (/ last-tx-distance-km last-tx-interval-ms) 3600000.0)
                                 0)
          range                (first (filter #(<= last-tx-distance-km (:max-km %)) distance-thresholds))]
      (when (and range (> speed-kmh (:max-speed range)))
        {:code    :anomalous-distance-time
         :message "Implausible travel speed detected"
         :details {:speed_kmh   speed-kmh
                   :distance_km last-tx-distance-km
                   :interval_m  (/ last-tx-interval-ms 60000.0)
                   :range       (:name range)}}))
    nil))

(defn authorize [auth-request restricted-areas]
  (let [violations (filterv some?
                            [(in-restricted-area? auth-request restricted-areas)
                             (annomalous-distance-time? auth-request)])]
    {:approved   (empty? violations)
     :violations violations}))

;; --------------- TESTS -----------------------

(def transaction-request {:transaction      {:timestamp "2026-03-14T14:00:00.000-03:00"}
                          :environment      {:terminal {:lat -23.5505
                                                        :lon -46.6333}}
                          :last-transaction {:transaction {:timestamp "2026-03-14T13:30:00.000-03:00"}
                                             :environment  {:terminal {:lat -20.15
                                                                       :lon -46.633}}}})

(comment
  "testes"

  (authorize transaction-request restricted-areas-list)
  (in-restricted-area? transaction-request restricted-areas-list)
  (annomalous-distance-time? transaction-request))
