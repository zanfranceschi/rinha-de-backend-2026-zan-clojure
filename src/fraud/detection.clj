(ns fraud.detection
  (:import [java.time Instant ZoneOffset ZonedDateTime DayOfWeek]))

(defn clamp ^double [^double x] (Math/max 0.0 (Math/min 1.0 x)))

(defn minutes-between ^double [^String ts-before ^String ts-after]
  (let [t1 (Instant/parse ts-before)
        t2 (Instant/parse ts-after)]
    (/ (Math/abs (- (.getEpochSecond t2) (.getEpochSecond t1))) 60.0)))

(defn vectorize ^doubles [payload norm mcc-risk]
  (let [tx              (get payload "transaction")
        cust            (get payload "customer")
        merch           (get payload "merchant")
        term            (get payload "terminal")
        last-tx         (get payload "last_transaction")
        amount          (double (get tx "amount"))
        installments    (double (get tx "installments"))
        requested-at    ^String (get tx "requested_at")
        avg-amount      (double (get cust "avg_amount"))
        tx-count-24h    (double (get cust "tx_count_24h"))
        known-merchants (set (get cust "known_merchants"))
        merchant-id     (get merch "id")
        mcc             (get merch "mcc")
        merch-avg       (double (get merch "avg_amount"))
        is-online       (get term "is_online")
        card-present    (get term "card_present")
        km-home         (double (get term "km_from_home"))
        ^Instant inst   (Instant/parse requested-at)
        ^ZonedDateTime dt (.atZone inst ZoneOffset/UTC)
        hour            (double (.getHour dt))
        ^DayOfWeek dow-obj (.getDayOfWeek dt)
        dow             (double (dec (.getValue dow-obj)))
        max-amount      (double (get norm "max_amount"))
        max-inst        (double (get norm "max_installments"))
        avg-ratio       (double (get norm "amount_vs_avg_ratio"))
        max-min         (double (get norm "max_minutes"))
        max-km          (double (get norm "max_km"))
        max-tx24        (double (get norm "max_tx_count_24h"))
        max-m-avg       (double (get norm "max_merchant_avg_amount"))]
    (double-array
     [(clamp (/ amount max-amount))
      (clamp (/ installments max-inst))
      (clamp (/ (/ amount avg-amount) avg-ratio))
      (/ hour 23.0)
      (/ dow 6.0)
      (if last-tx
        (clamp (/ (minutes-between ^String (get last-tx "timestamp") requested-at) max-min))
        -1.0)
      (if last-tx
        (clamp (/ (double (get last-tx "km_from_current")) max-km))
        -1.0)
      (clamp (/ km-home max-km))
      (clamp (/ tx-count-24h max-tx24))
      (if is-online 1.0 0.0)
      (if card-present 1.0 0.0)
      (if (contains? known-merchants merchant-id) 0.0 1.0)
      (double (get mcc-risk mcc 0.5))
      (clamp (/ merch-avg max-m-avg))])))

(defn euclidean-dist-sq ^double [^doubles a ^doubles b]
  (let [n (alength a)]
    (loop [i 0, sum 0.0]
      (if (< i n)
        (let [d (- (aget a i) (aget b i))]
          (recur (inc i) (+ sum (* d d))))
        sum))))

(defn knn [^objects ref-vectors ^objects ref-labels ^doubles query-vec k]
  (let [n (alength ref-vectors)
        dists (double-array n)]
    (dotimes [i n]
      (aset dists i (euclidean-dist-sq ^doubles (aget ref-vectors i) query-vec)))
    (let [indices (int-array n)]
      (dotimes [i n] (aset indices i i))
      (dotimes [j k]
        (let [j (int j)]
          (loop [min-idx j, min-val (aget dists j), i (inc j)]
            (if (< i n)
              (let [v (aget dists i)]
                (if (< v min-val)
                  (recur i v (inc i))
                  (recur min-idx min-val (inc i))))
              (let [tmp-d (aget dists j)
                    tmp-i (aget indices j)]
                (aset dists j (aget dists min-idx))
                (aset indices j (aget indices min-idx))
                (aset dists min-idx tmp-d)
                (aset indices min-idx tmp-i))))))
      (let [fraud-count (loop [j 0, cnt 0]
                          (if (< j k)
                            (recur (inc j)
                                   (if (= "fraud" (aget ref-labels (aget indices j)))
                                     (inc cnt)
                                     cnt))
                            cnt))]
        (/ (double fraud-count) (double k))))))

(defn fraud-score [ref-vectors ref-labels norm mcc-risk payload]
  (let [vec      (vectorize payload norm mcc-risk)
        score    (knn ref-vectors ref-labels vec 5)
        approved (< score 0.6)]
    {"approved" approved "fraud_score" score}))
