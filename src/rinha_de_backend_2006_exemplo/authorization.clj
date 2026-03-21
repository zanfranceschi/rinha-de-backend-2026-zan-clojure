(ns rinha-de-backend-2006-exemplo.authorization
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [rinha-de-backend-2006-exemplo.misc :as misc]))

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

(defn authorize [auth-request restricted-areas]
  (if-let [violation (in-restricted-area? auth-request restricted-areas)]
    {:approved false :violation violation}
    {:approved true}))

;; --------------- TESTS -----------------------

(def transaction-request {:environment {:terminal {:lat -23.5505
                                                    :lon -46.6333}}})

(comment
  "testes"

  (authorize transaction-request restricted-areas-list)
  (in-restricted-area? transaction-request restricted-areas-list))
