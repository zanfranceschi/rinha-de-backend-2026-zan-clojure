(ns rinha-de-backend-2006-exemplo.authorization
  (:require [clojure.string]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [envvar.core :as envvar :refer [env]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.reload :refer [wrap-reload]]
            [rinha-de-backend-2006-exemplo.db :as db]
            [rinha-de-backend-2006-exemplo.misc :as misc]
            [taoensso.telemere :as tel]))

(def card {:type :credit
           :token "123123123"
           :exp "2032-04"
           :holder {:underage true
                    :address {:latitude -23 :longitude -46}}})

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

(def restricted-ip-ranges
  (json/read-str (slurp (io/resource "restricted_ip_ranges.json")) :key-fn keyword))

(def restricted-domains
  (json/read-str (slurp (io/resource "restricted_domains.json")) :key-fn keyword))

(defonce current-year-month
  (let [now (java.time.Instant/now)
        ym  (java.time.YearMonth/from (.atZone now java.time.ZoneOffset/UTC))]
    {:year  (.getYear ym)
     :month (.getMonthValue ym)}))

(defn card-expired? [authorization-request current-year current-month]
  (let [expiration                     (-> authorization-request :environment :card :expiration)
        [card-exp-year card-exp-month] (->> (clojure.string/split expiration #"-")
                                            (map Integer/parseInt))]
    (cond
      (> current-year card-exp-year) true
      (and (= current-year card-exp-year)
           (> current-month card-exp-month)) true
      :else false)))

(defn from-restricted-ip? [authorization-request restricted-ips-list]
  (let [ip (-> authorization-request :context :payment :ip)]
    (boolean (and
              ip
              (some #(misc/in-cidr? % ip) restricted-ips-list)))))

(defn from-restricted-domain? [authorization-request restricted-domains]
  (let [domain (-> authorization-request :environment :terminal :location :name)]
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
  (let [{:keys [latitude longitude]}
        (-> authorization-request :environment :terminal :location :geo)]
    (boolean (some #(point-in-polygon? [longitude latitude] %) restricted-areas))))


; --------------- TESTS -----------------------

(def paylaod {:environment {:card {:expiration "2020-12"}
                            :terminal {:location {:geo {:latitude  -23.5505
                                                        :longitude -46.6333}}}}
              :context     {:payment {:ip "192.168.0.10"}}})

(def paylaod-2 {:environment {:card {:expiration "2020-12"}
                              :terminal {:location {:name "crypto-gambling-palace.org"}}}
                :context     {:payment {:ip "192.168.0.10"}}})

(card-expired? paylaod
          (:year current-year-month)
          (:month current-year-month))


(from-restricted-ip? paylaod
                    restricted-ip-ranges)

(from-restricted-domain? paylaod restricted-domains)

(in-restricted-area?
 paylaod
 restricted-areas-list)

(defn authorize [authorization-request])
