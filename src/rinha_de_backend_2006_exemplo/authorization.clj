(ns rinha-de-backend-2006-exemplo.authorization
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string]
            [rinha-de-backend-2006-exemplo.misc :as misc]
            [taoensso.telemere :as tel])
  (:import
   [java.time Duration Instant]))

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

(def mccs-restrictions
  (json/read-str
   (slurp (io/resource "mccs_restrictions.json")) :key-fn keyword))

(def mcc-restriction-restrictions
  (json/read-str
   (slurp (io/resource "mcc_relation_restrictions.json")) :key-fn keyword))

(def restricted-ip-ranges
  (json/read-str (slurp (io/resource "restricted_ip_ranges.json")) :key-fn keyword))

(def restricted-domains
  (json/read-str (slurp (io/resource "restricted_domains.json")) :key-fn keyword))

(defn from-restricted-ip? [authorization-request restricted-ips-list]
  (let [ip (-> authorization-request :context :payment-ip)]
    (boolean (and
              ip
              (some #(misc/in-cidr? % ip) restricted-ips-list)))))

(defn from-restricted-domain? [authorization-request restricted-domains]
  (let [domain (-> authorization-request :environment :domain)]
    (boolean (and
              domain
              (some #{domain} restricted-domains)))))

(defn point-in-polygon?
  "Checks if a point [lon lat] is inside a polygon (vector of [lon lat] vertices).
   Uses the ray casting algorithm."
  [[lon lat] polygon]
  (let [n (count polygon)]
    (loop [i 0
           j (dec n)
           inside? false]
      (if (< i n)
        (let [[xi yi] (nth polygon i)
              [xj yj] (nth polygon j)
              intersect? (and (or (and (> yi lat) (<= yj lat))
                                  (and (> yj lat) (<= yi lat)))
                              (< lon (+ xj (* (/ (- lat yj) (- yi yj))
                                              (- xi xj)))))]
          (recur (inc i) i (if intersect? (not inside?) inside?)))
        inside?))))

(defn in-restricted-area? [authorization-request restricted-areas]
  (let [{:keys [lat lon]}
        (-> authorization-request :environment :terminal)]
    (boolean (and lat lon (some #(point-in-polygon? [lon lat] %) restricted-areas)))))

(defn annomalous-distance-time? [auth-request]
  (if (and (= (-> auth-request :environment :type) :onsite)
           (some #(= :onsite (-> % :environment :type)) (:last-transactions auth-request)))
    (let [current-tx-timestamp  (java.time.Instant/parse (-> auth-request :transaction :timestamp))
          {:keys [lat lon]}     (-> auth-request :environment :terminal)
          _card-token           (-> auth-request :environment :card :token)
          last-txs-onsite       (filter #(= :onsite (-> % :environment :type)) (:last-transactions auth-request))
          last-tx-sorted        (sort-by #(Instant/parse (-> % :transaction :timestamp))
                                         #(compare %2 %1)
                                         last-txs-onsite)
          last-tx-onsite        (first last-tx-sorted)
          last-tx-terminal      (-> last-tx-onsite :environment :terminal)
          last-tx-lat           (:lat last-tx-terminal)
          last-tx-lon           (:lon last-tx-terminal)
          last-tx-distance-km   (misc/equirectangular-km-distance
                                 {:lat lat
                                  :lon lon}
                                 {:lat last-tx-lat
                                  :lon last-tx-lon})
          last-tx-timestamp     (java.time.Instant/parse (-> last-tx-onsite :transaction :timestamp))
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
          range                 (first (filter #(<= last-tx-distance-km (:max-km %)) distance-thresholds))
          fraud                 (if (and range (> speed-kmh (:max-speed range)))
                                  [(:name range) true]
                                  [nil false])]
      {:fraud         fraud
       :speed_kmh     speed-kmh
       :distance_km   last-tx-distance-km
       :interval_secs last-tx-interval-secs})
    [nil false]))

(defn annomalous-interval? [auth-request]
  (if-let [last-txs (seq (:last-transactions auth-request))]
    (let [current-tx-timestamp  (java.time.Instant/parse (-> auth-request :transaction :timestamp))
          last-tx-sorted        (sort-by #(Instant/parse (-> % :transaction :timestamp))
                                         #(compare %2 %1)
                                         last-txs)
          last-tx               (first last-tx-sorted)
          last-tx-timestamp     (java.time.Instant/parse (-> last-tx :transaction :timestamp))
          last-tx-interval-mins (.toMinutes
                                 (Duration/between
                                  last-tx-timestamp
                                  current-tx-timestamp))]
      [(< last-tx-interval-mins 5) last-tx-interval-mins])
    [false nil]))

(defn mcc-restricted? [authorization-request mccs-restrictions]
  (let [tx-amount              (-> authorization-request :transaction :amount)
        sale-mcc               (-> authorization-request :context :sale-mcc)
        mcc-restrictions       (->> mccs-restrictions
                                    (filter (fn [restriction]
                                              (= (:mcc restriction) sale-mcc)))
                                    first)
        has-restrictions?      (boolean (seq mcc-restrictions))
        _ (tel/log! mcc-restrictions)
        max-amount-restricted? (and has-restrictions?
                                    (:max_amount mcc-restrictions)
                                    (> tx-amount (:max_amount mcc-restrictions)))
        min-amount-restricted? (and has-restrictions?
                                    (:min_amount mcc-restrictions)
                                    (< tx-amount (:min_amount mcc-restrictions)))]
    {:restrictions           mcc-restrictions
     :max-amount-restricted? max-amount-restricted?
     :min-amount-restricted? min-amount-restricted?}))

(defn mcc-relation-restricted? [authorization-request mcc-relation-restrictions]
  (let [sale-mcc                  (-> authorization-request :context :sale-mcc)
        merchant-mcc              (-> authorization-request :environment :merchant :mcc)
        mcc-relation-restrictions (->> mcc-relation-restrictions
                                       (filter (fn [restriction]
                                                 (= (:mcc restriction) merchant-mcc)))
                                       first)
        allowed-mccs              (map :mcc (conj (:related mcc-relation-restrictions) merchant-mcc))]
    (not
     (some #{sale-mcc} allowed-mccs))))

; --------------- TESTS -----------------------

(def transaction-request {:id                "41b44ad7-37a3-4ddc-b7fb-7c6ea803b07d"
                          :status            :pending
                          :card-holder-id    "aada5902-2a2b-4000-b495-1f6aad3400a1"
                          :transaction       {:timestamp             "2026-03-14T14:00:00.000-03:00"
                                              :amount                101.0
                                              :currency              :brl
                                              :number-of-intallments 12}
                          :environment       {:type                :onsite
                                              :card                {:id "240cb527-92ea-4451-9884-94784bad072a"
                                                                    :type                                 :credit}
                                              :merchant            {:id "3e106a59-ee63-45b0-a8b9-3416d95ee485"
                                                                    :mcc                                  "7801"}
                                              :domain              "win-big-tonight.net"
                                              :card-present        true
                                              :card-holder-present true
                                              :terminal            {:id "b2395638-fdcf-44ad-bcd1-a27968b84a16"
                                                                    :manufacturer                          "Ingenico"
                                                                    :model                                 "move/5000"
                                                                    :serial-number                         "123123ABC"
                                                                    :lat                                   -23.5505
                                                                    :lon                                   -46.6333}}
                          :context           {:sale-mcc   "5813"
                                              :payment-ip "192.168.0.10"}
                          :last-transactions [{:id             "f93b819c-8e23-44b8-b032-5224763656fd"
                                               :status         :denied
                                               :card-holder-id "aada5902-2a2b-4000-b495-1f6aad3400a1"
                                               :transaction    {:timestamp             "2026-03-14T13:57:00.000-03:00"
                                                                :amount                1000.0
                                                                :currency              :brl
                                                                :number-of-intallments 12}
                                               :environment    {:type                :online
                                                                :card                {:id   "240cb527-92ea-4451-9884-94784bad072a"
                                                                                      :type :credit}
                                                                :merchant            {:id  "3e106a59-ee63-45b0-a8b9-3416d95ee485"
                                                                                      :mcc "7801"}
                                                                :domain              "qqcoisa.com"
                                                                :card-present        true
                                                                :card-holder-present true
                                                                :terminal            nil}
                                               :context        {:sale-mcc   "7801"
                                                                :payment-ip "192.168.0.10"}}
                                              {:id             "98101582-cd05-4ed2-85df-48bd4e11ae76"
                                               :status         :approved
                                               :card-holder-id "aada5902-2a2b-4000-b495-1f6aad3400a1"
                                               :transaction    {:timestamp             "2026-03-14T10:00:00.000-03:00"
                                                                :amount                80.0
                                                                :currency              :brl
                                                                :number-of-intallments 12}
                                               :environment    {:type                :onsite
                                                                :card                {:id   "240cb527-92ea-4451-9884-94784bad072a"
                                                                                      :type :credit}
                                                                :merchant            {:id  "381ae548-d660-437d-b46e-a31c2b162d36"
                                                                                      :mcc "7801"}
                                                                :domain              nil
                                                                :card-present        true
                                                                :card-holder-present true
                                                                :terminal            {:id            "b2395638-fdcf-44ad-bcd1-a27968b84a16"
                                                                                      :manufacturer  "Ingenico"
                                                                                      :model         "move/5000"
                                                                                      :serial-number "123123ABC"
                                                                                      :lat           -23.15
                                                                                      :lon           -46.633}}
                                               :context        {:sale-mcc   "7801"
                                                                :payment-ip nil}}
                                              {:id             "6e09a1ce-ed7b-40af-a3e8-e9d6cda8a555"
                                               :status         :approved
                                               :card-holder-id "aada5902-2a2b-4000-b495-1f6aad3400a1"
                                               :transaction    {:timestamp             "2026-03-13T10:00:12.300-03:00"
                                                                :amount                80.0
                                                                :currency              :brl
                                                                :number-of-intallments 12}
                                               :environment    {:type                :onsite
                                                                :card                {:id   "240cb527-92ea-4451-9884-94784bad072a"
                                                                                      :type :credit}
                                                                :merchant            {:id  "c3c91f5b-00f4-4850-aa5a-363363739a05"
                                                                                      :mcc "7801"}
                                                                :domain              nil
                                                                :card-present        true
                                                                :card-holder-present true
                                                                :terminal            {:id            "b2395638-fdcf-44ad-bcd1-a27968b84a16"
                                                                                      :manufacturer  "Ingenico"
                                                                                      :model         "move/5000"
                                                                                      :serial-number "123123ABC"
                                                                                      :lat           -20.9505
                                                                                      :lon           -40.1333}}
                                               :context        {:sale-mcc   "7801"
                                                                :payment-ip "192.168.0.10"}}]})

(comment
  "testes"
  (annomalous-distance-time? transaction-request)
  (annomalous-interval? transaction-request)
  (mcc-restricted? transaction-request mccs-restrictions)
  (mcc-relation-restricted? transaction-request mcc-restriction-restrictions)
  (from-restricted-ip? transaction-request restricted-ip-ranges)
  (from-restricted-domain? transaction-request restricted-domains)
  (in-restricted-area? transaction-request restricted-areas-list)
  )
