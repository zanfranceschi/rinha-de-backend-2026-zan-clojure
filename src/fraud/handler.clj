(ns fraud.handler
  (:require [charred.api :as json]
            [fraud.detection :as detection]))

(defn make-handler [ref-vectors ref-labels norm mcc-risk]
  (fn [req]
    (case (:uri req)
      "/ready"
      {:status 200 :headers {"content-type" "text/plain"} :body "janet and zan are ready 👌"}

      "/fraud-score"
      (if (= :post (:request-method req))
        (let [payload (json/read-json (:body req))
              result  (detection/fraud-score ref-vectors ref-labels norm mcc-risk payload)]
          {:status 200
           :headers {"content-type" "application/json"}
           :body (json/write-json-str result)})
        {:status 405 :body "Method not allowed"})

      {:status 404 :body "Not found"})))
