(ns rinha-de-backend-2026-exemplo.endpoints
  (:require [compojure.core :refer [defroutes POST]]
            [compojure.route :as route]
            [envvar.core :as envvar :refer [env]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [rinha-de-backend-2026-exemplo.authorization :as authorization]
            [taoensso.telemere :as tel])
  (:gen-class))

(defroutes routes
  (POST "/authorizations" req
    (let [payload (:body req)
          result  (authorization/authorize payload)]
      {:status 200
       :body   result}))

  (route/not-found {:status  404
                    :body    {:error "not found"}}))

(def app
  (-> routes
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

(defn -main [& _args]
  (let [port    (Integer/parseInt (or (@env :port) "3000"))
        server  (jetty/run-jetty app {:port  port
                                      :join? false})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (tel/log! "Shutting down...")
                                 (.stop server)
                                 (tel/log! "Goodbye!"))))
    (tel/log! (str "Server running on port " port))))
