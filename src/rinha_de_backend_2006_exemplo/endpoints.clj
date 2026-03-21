(ns rinha-de-backend-2006-exemplo.endpoints
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [envvar.core :as envvar :refer [env]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.reload :refer [wrap-reload]]
            [rinha-de-backend-2006-exemplo.authorization :as authorization]
            [taoensso.telemere :as tel])
  (:gen-class))

(defroutes routes
  (GET "/hello" [] {:status 200
                    :body {:message "hello"}})
 
  (POST "/test" request
    {:status 200
     :body {:msg (-> request :body :msg)}})

  (POST "/authorizations" req
    (let [idempotency-key (get-in req [:headers "idempotency-key"])
          _ (tel/log! (str "idempotency-key: " idempotency-key))
          payload (:body req)
          result  (authorization/authorize payload authorization/restricted-areas-list)]
      {:status (if (:approved result) 200 403)
       :body   result}))

  (route/not-found {:status  404
                    :body    {:error "not found"}}))

(def app
  (-> routes
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

(defn -main [& _args]
  (let [port    (Integer/parseInt (or (@env :port) "3000"))
        dev?    (= "development" (@env :env))
        handler (if dev?
                  (wrap-reload #'app)
                  app)
        server  (jetty/run-jetty handler {:port  port
                                          :join? false})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (tel/log! "Shutting down...")
                                 (.stop server)
                                 (tel/log! "Goodbye!"))))
    (tel/log! (str "Server running on port " port (when dev? " (dev mode)")))))
