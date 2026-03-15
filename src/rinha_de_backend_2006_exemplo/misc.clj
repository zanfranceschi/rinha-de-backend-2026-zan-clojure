(ns rinha-de-backend-2006-exemplo.misc
  (:require [clojure.string]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv])
  (:import [org.apache.commons.net.util SubnetUtils]))

(defn in-cidr? [cidr ip]
  (let [subnet (SubnetUtils. cidr)]
    (.setInclusiveHostCount subnet true)
    (-> subnet .getInfo (.isInRange ip))))

(defn haversine-km-distance
  "Calculates the great-circle distance (in km) between two points
   given their latitude and longitude in degrees."
  ^double [{^double lat1 :lat ^double long1 :long}
           {^double lat2 :lat ^double long2 :long}]
  (let [R      6371 ; Earth's radius in km
        to-rad #(Math/toRadians %)
        dlat   (to-rad (- lat2 lat1))
        dlon   (to-rad (- long2 long1))
        lat1   (to-rad lat1)
        lat2   (to-rad lat2)
        a      (+ (* (Math/sin (/ dlat 2)) (Math/sin (/ dlat 2)))
                  (* (Math/cos lat1) (Math/cos lat2)
                     (Math/sin (/ dlon 2)) (Math/sin (/ dlon 2))))
        c      (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (* R c)))

(defn equirectangular-km-distance
  "Calculates approximate distance (in km) between two points
   using the Equirectangular approximation. Fast but less accurate
   for long distances."
  ^double [{^double lat1 :lat ^double long1 :long}
           {^double lat2 :lat ^double long2 :long}]
  (let [R 6371.0
        lat1 (Math/toRadians lat1)
        lat2 (Math/toRadians lat2)
        dlon (Math/toRadians (- long2 long1))
        dlat (- lat2 lat1)
        x    (* dlon (Math/cos (/ (+ lat1 lat2) 2.0)))
        y    dlat]
    (* R (Math/sqrt (+ (* x x) (* y y))))))

(defn load-mcc-codes
  "Loads MCC codes from resources/mcc_codes.csv into a vector of maps
   with keyword keys (e.g. :mcc, :edited_description, etc.)."
  []
  (with-open [rdr (io/reader (io/resource "mcc_codes.csv"))]
    (let [[header & rows] (csv/read-csv rdr)
          ks (mapv #(keyword (clojure.string/replace % #"[^\w]" "_")) header)]
      (mapv #(zipmap ks %) rows))))
