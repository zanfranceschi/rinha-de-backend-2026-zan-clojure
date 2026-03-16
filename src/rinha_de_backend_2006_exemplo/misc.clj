(ns rinha-de-backend-2006-exemplo.misc
  (:require [clojure.string])
  (:import [org.apache.commons.net.util SubnetUtils]))

(defn in-cidr? [cidr ip]
  (let [subnet (SubnetUtils. cidr)]
    (.setInclusiveHostCount subnet true)
    (-> subnet .getInfo (.isInRange ip))))

(defn haversine-km-distance
  "Calculates the great-circle distance (in km) between two points
   given their latitude and longitude in degrees."
  ^double [{^double lat1 :lat
            ^double lon1 :lon}
           {^double lat2 :lat
            ^double lon2 :lon}]
  (let [R      6371 ; Earth's radius in km
        to-rad #(Math/toRadians %)
        dlat   (to-rad (- lat2 lat1))
        dlon   (to-rad (- lon2 lon1))
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
  ^double [{^double lat1 :lat
            ^double lon1 :lon}
           {^double lat2 :lat
            ^double lon2 :lon}]
  (let [R    6371.0
        lat1 (Math/toRadians lat1)
        lat2 (Math/toRadians lat2)
        dlon (Math/toRadians (- lon2 lon1))
        dlat (- lat2 lat1)
        x    (* dlon (Math/cos (/ (+ lat1 lat2) 2.0)))
        y    dlat]
    (* R (Math/sqrt (+ (* x x) (* y y))))))

(defn point-in-polygon?
  "Checks if a point [lon lat] is inside a polygon (vector of [lon lat] vertices).
   Uses the ray casting algorithm."
  [[lon lat] polygon]
  (let [n (count polygon)]
    (loop [i       0
           j       (dec n)
           inside? false]
      (if (< i n)
        (let [[xi yi]    (nth polygon i)
              [xj yj]    (nth polygon j)
              intersect? (and (or (and (> yi lat) (<= yj lat))
                                  (and (> yj lat) (<= yi lat)))
                              (< lon (+ xj (* (/ (- lat yj) (- yi yj))
                                              (- xi xj)))))]
          (recur (inc i) i (if intersect? (not inside?) inside?)))
        inside?))))
