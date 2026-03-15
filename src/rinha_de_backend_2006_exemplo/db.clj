(ns rinha-de-backend-2006-exemplo.db
  (:require [hikari-cp.core :as hikari]
            [envvar.core :refer [env]]))

(defn create-datasource []
  (let [host     (or (@env :db-host) "localhost")
        port     (or (@env :db-port) "5432")
        db-name  (or (@env :db-name) "rinha")
        user     (or (@env :db-user) "postgres")
        password (or (@env :db-password) "postgres")
        min-idle (Integer/parseInt (or (@env :db-min-idle) "2"))
        max-pool (Integer/parseInt (or (@env :db-max-pool-size) "10"))]
    (hikari/make-datasource
     {:jdbc-url          (str "jdbc:postgresql://" host ":" port "/" db-name)
      :username          user
      :password          password
      :minimum-idle      min-idle
      :maximum-pool-size max-pool})))

(defn close-datasource [ds]
  (hikari/close-datasource ds))

(defn wrap-db [handler ds]
  (fn [req]
    (handler (assoc req :db ds))))