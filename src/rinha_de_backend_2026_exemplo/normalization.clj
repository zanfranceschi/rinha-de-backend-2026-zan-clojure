(ns rinha-de-backend-2026-exemplo.normalization
  (:import [java.time Instant ZoneOffset]
           [java.time.temporal ChronoUnit ChronoField]))

(defn- clamp [v]
  (max 0.0 (min v 1.0)))

(defn normalize
  "Normalize a raw authorization request into a 14-dimension vector.
   - config: normalization constants from normalization.json
   - mcc-risk: map of MCC code string to risk score (0..1)"
  [request config mcc-risk]
  (let [tx             (:transaction request)
        customer       (:customer request)
        merchant       (:merchant request)
        terminal       (:terminal request)
        last-tx        (:last_transaction request)

        ;; Parse timestamp
        requested-at   (Instant/parse (:requested_at tx))
        zdt            (.atZone requested-at ZoneOffset/UTC)

        ;; 1. amount
        amount         (clamp (/ (double (:amount tx))
                                 (:max_amount config)))

        ;; 2. installments
        installments   (clamp (/ (double (:installments tx))
                                 (:max_installments config)))

        ;; 3. amount_vs_avg
        ratio          (/ (double (:amount tx))
                          (double (:avg_amount customer)))
        amount-vs-avg  (clamp (/ ratio (:amount_vs_avg_ratio config)))

        ;; 4. hour_of_day
        hour           (/ (double (.get zdt ChronoField/HOUR_OF_DAY)) 23.0)

        ;; 5. day_of_week (mon=0, sun=6)
        ;; Java DayOfWeek: MONDAY=1 .. SUNDAY=7
        day-of-week    (/ (double (dec (.getValue (.getDayOfWeek zdt)))) 6.0)

        ;; 6. minutes_since_last_tx
        minutes-since  (if last-tx
                         (let [last-ts (Instant/parse (:timestamp last-tx))
                               mins    (.between ChronoUnit/MINUTES last-ts requested-at)]
                           (clamp (/ (double mins) (:max_minutes config))))
                         -1.0)

        ;; 7. km_from_last_tx
        km-from-last   (if last-tx
                         (clamp (/ (double (:km_from_current last-tx))
                                   (:max_km config)))
                         -1.0)

        ;; 8. km_from_home
        km-from-home   (clamp (/ (double (:km_from_home terminal))
                                  (:max_km config)))

        ;; 9. tx_count_24h
        tx-count       (clamp (/ (double (:tx_count_24h customer))
                                  (:max_tx_count_24h config)))

        ;; 10. is_online
        is-online      (if (:is_online terminal) 1.0 0.0)

        ;; 11. card_present
        card-present   (if (:card_present terminal) 1.0 0.0)

        ;; 12. is_new_merchant
        known          (set (:known_merchants customer))
        is-new         (if (contains? known (:id merchant)) 0.0 1.0)

        ;; 13. mcc_risk
        mcc-risk-val   (get mcc-risk (:mcc merchant) 0.5)

        ;; 14. merchant_avg_amount
        merch-avg      (clamp (/ (double (:avg_amount merchant))
                                  (:max_merchant_avg_amount config)))]

    [amount installments amount-vs-avg hour day-of-week
     minutes-since km-from-last km-from-home tx-count
     is-online card-present is-new mcc-risk-val merch-avg]))
