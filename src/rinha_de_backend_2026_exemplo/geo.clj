(ns rinha-de-backend-2026-exemplo.geo)

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
