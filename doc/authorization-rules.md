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

**Logic:**
1. Calculate distance (km) between current and last terminal using the **Haversine formula**.
2. Calculate elapsed time (milliseconds) between the two timestamps.
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

## Rule Summary

| # | Rule                      | Key Fields                          | Condition for Denial/Flag                                    |
|---|---------------------------|-------------------------------------|--------------------------------------------------------------|
| 1 | Restricted Area           | `environment.terminal.{lat, lon}`   | Coordinates fall inside a restricted polygon                 |
| 2 | Anomalous Distance/Time   | Terminal coords + timestamps        | Implied travel speed exceeds threshold for distance range    |