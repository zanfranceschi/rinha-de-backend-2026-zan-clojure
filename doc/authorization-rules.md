# Authorization Rules

This document describes the fraud-detection and authorization rules implemented in the `rinha-de-backend-2006-exemplo.authorization` namespace.

---

## 1. Restricted Geographic Area (`in-restricted-area?`)

**Fields:** `environment.terminal.lat`, `environment.terminal.lon`

Denies the transaction if the terminal's geographic coordinates fall inside any polygon defined in `restricted_areas.json` (GeoJSON FeatureCollection).

- Uses the **ray-casting algorithm** to determine point-in-polygon membership.
- Rule only triggers when both `lat` and `lon` are present.

---

## 2. Anomalous Distance / Time (`annomalous-distance-time?`)

**Fields:** `environment.terminal.{lat, lon}`, `transaction.timestamp`, `last-transaction`

Flags fraud when the cardholder appears to have traveled an implausible distance between the current transaction and the last transaction.

All transactions are assumed to be onsite.

**Logic:**
1. If `last-transaction` is present, calculate distance (km) between current and last terminal using the **equirectangular approximation**.
2. Calculate elapsed time (seconds) between the two timestamps.
3. Derive implied speed in km/h.
4. Compare against distance-based speed thresholds:

| Range Name         | Max Distance (km) | Max Speed (km/h) |
|--------------------|--------------------|-------------------|
| micro              | 1                  | 15                |
| urban              | 5                  | 40                |
| suburban           | 50                 | 100               |
| regional           | 300                | 140               |
| domestic-flight    | 1,500              | 450               |
| intercontinental   | 99,999             | 700               |

If the implied speed exceeds the `max-speed` for the matching distance range, the transaction is flagged as fraud.

---

## 3. Anomalous Interval (`annomalous-interval?`)

**Fields:** `transaction.timestamp`, `last-transaction`

Flags fraud when the interval between the current transaction and the last transaction is too short.

**Logic:**
1. If `last-transaction` is present, calculate elapsed time (minutes) between the current and last transaction.
2. If the interval is less than **5 minutes**, flag as suspicious.

---

## 4. MCC Restricted (`mcc-restricted?`)

**Fields:** `transaction.amount`, `context.sale-mcc`

Flags fraud when the transaction amount violates restrictions defined per MCC (Merchant Category Code) in `mccs_restrictions.json`.

**Logic:**
1. Look up restrictions for the sale MCC.
2. If `max_amount` is defined and the transaction amount exceeds it, flag as restricted.
3. If `min_amount` is defined and the transaction amount is below it, flag as restricted.

---

## 5. MCC Relation Restricted (`mcc-relation-restricted?`)

**Fields:** `context.sale-mcc`, `environment.merchant.mcc`

Flags fraud when the sale MCC is not related to the merchant's MCC according to `mcc_relation_restrictions.json`.

**Logic:**
1. Look up the merchant MCC in the relation restrictions.
2. Build a list of allowed MCCs (the merchant's own MCC plus its related MCCs).
3. If the sale MCC is not in the allowed list, flag as restricted.

---

## Rule Summary

| # | Rule                      | Key Fields                          | Condition for Denial/Flag                                    |
|---|---------------------------|-------------------------------------|--------------------------------------------------------------|
| 1 | Restricted Area           | `environment.terminal.{lat, lon}`   | Coordinates fall inside a restricted polygon                 |
| 2 | Anomalous Distance/Time   | Terminal coords + timestamps        | Implied travel speed exceeds threshold for distance range    |
| 3 | Anomalous Interval        | `transaction.timestamp`             | Less than 5 minutes since last transaction                   |
| 4 | MCC Restricted            | `transaction.amount`, `sale-mcc`    | Amount exceeds MCC min/max limits                            |
| 5 | MCC Relation Restricted   | `sale-mcc`, `merchant.mcc`          | Sale MCC not related to merchant MCC                         |