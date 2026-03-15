(ns rinha-de-backend-2006-exemplo.endpoints
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [envvar.core :as envvar :refer [env]]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.reload :refer [wrap-reload]]
            [rinha-de-backend-2006-exemplo.db :as db]
            [rinha-de-backend-2006-exemplo.misc :as misc]
            [rinha-de-backend-2006-exemplo.authorization :as authorization]
            [taoensso.telemere :as tel])
  (:gen-class))

(defroutes routes
  (GET "/hello" [] {:status 200
                    :body {:message "hello"}})

  (GET "/db-test" req
    (let [ds (:db req)
          result (atom {:result "NADA"})]
      (jdbc/with-transaction [tx ds]
        (let [sql-result (jdbc/execute-one! tx ["SELECT 1 AS result"])]
          (swap! result (fn [_] sql-result))))
      {:status 200
       :body @result}))

  (POST "/test" request
    {:status 200
     :body {:msg (-> request :body :msg)}})

  (POST "/authorizations" req
    (let [idempotency-key (get-in req [:headers "idempotency-key"])
          _ (tel/log! (str "idempotency-key: " idempotency-key))
          payload        (:body req)
          ip-address     (-> payload :context :payment :ipAddr)
          {lat  :lat
           long :long}   (-> payload :env :terminal :location :geo)
          lat-long-rj {:lat  -22.9068
                       :long -43.1729}
          dist-haversine (misc/haversine-km-distance lat-long-rj
                                                     {:lat  lat
                                                      :long long})
          dist-equirectangular (misc/equirectangular-km-distance lat-long-rj
                                                                 {:lat  lat
                                                                  :long long})
          in-range?      (misc/in-cidr? "192.168.1.0/24" "192.168.1.42")]
      {:status 200
       :body   {:in-range?            in-range?
                :ip                   ip-address
                :dist-haversine       (str dist-haversine " kms")
                :dist-equirectangular (str dist-equirectangular " kms")
                :status               "ok"}}))

  (route/not-found {:status  404
                    :body    {:error "not found"}}))

(def app
  (-> routes
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

(defn -main [& _args]
  (let [port    (Integer/parseInt (or (@env :port) "3000"))
        dev?    (= "development" (@env :env))
        ds      (db/create-datasource)
        handler (cond-> (-> #'app (db/wrap-db ds))
                  dev? wrap-reload)
        server  (jetty/run-jetty handler {:port  port
                                          :join? false})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (tel/log! "Shutting down...")
                                 (.stop server)
                                 (db/close-datasource ds)
                                 (tel/log! "Goodbye!"))))
    (tel/log! (str "Server running on port " port (when dev? " (dev mode)")))))
