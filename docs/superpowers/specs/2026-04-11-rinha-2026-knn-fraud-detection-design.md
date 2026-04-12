# Rinha de Backend 2026 — Fraud Detection with KNN

## Overview

Participants build an HTTP API that receives transaction authorization requests and classifies them as fraud or legit using K-Nearest Neighbors (KNN) against a pre-loaded reference dataset.

```
                    +---------------------------+
                    |    Reference Dataset       |
                    |    (loaded at startup)     |
                    |    [{vector, label}, ...]  |
                    +-------------+-------------+
                                  |
POST /authorizations              |
  raw transaction --> normalize --+--> KNN (K=5, cosine) --> { approved, fraud_score }
  (14 fields)       (14-dim                (find K nearest,
                     0..1 vector)            majority vote)
```

- Single endpoint: `POST /authorizations`
- Stateless — no database required, no shared state between instances
- Reference dataset + MCC risk scores + normalization constants loaded from files at startup
- Participants are free to use any technology: in-memory brute force, vector databases (pgvector, Qdrant, SQLite-vss, etc.), spatial indexes, or any other approach

## What is KNN? (Educational)

### What is a vector?

An array of numbers that describes a "thing." For example, a wine could be described as:

```
[price, sweetness, bitterness, acidity, body]
Malbec  = [0.7, 0.2, 0.6, 0.5, 0.9]
Moscato = [0.3, 0.9, 0.1, 0.3, 0.2]
```

Wines with similar characteristics have similar vectors — they're "close" to each other.

In fraud detection, each transaction becomes a vector describing its "shape" — amount, time of day, distance from home, etc.

### What is KNN?

K-Nearest Neighbors. You have a bunch of labeled examples (fraud or legit). A new transaction comes in. You find the **K closest** labeled examples and let them vote.

```
K = how many neighbors to ask

Reference data:
  [0.9, 0.85, ...] fraud
  [0.1, 0.20, ...] legit
  [0.8, 0.90, ...] fraud
  [0.2, 0.15, ...] legit
  [0.7, 0.80, ...] fraud

New transaction vector: [0.85, 0.88, ...]

5 nearest neighbors: 3 fraud, 2 legit
fraud_score = 3/5 = 0.60
Result: fraud (majority vote)
```

That's the entire algorithm. No training, no model — just "find the most similar things you've seen, and be whatever most of them are."

### What is normalization?

Making numbers comparable by putting them on the same scale (0.0 to 1.0).

Without normalization, dimensions are all over the place:

```
amount:     R$ 0.50  to  R$ 50,000
hour:       0        to  24
distance:   0 km     to  20,000 km
```

If you calculate distance using raw values, **amount dominates everything** just because its numbers are bigger. A R$ 20 difference would outweigh a 12-hour difference.

Normalization squashes everything to 0.0-1.0 so every dimension gets a fair vote in the distance calculation.

**Note:** normalized values must always be between 0.0 and 1.0. If a raw value exceeds the normalization constant, clamp it.

Example:
```
max_amount = 10000

amount = 8000   --> 8000 / 10000  = 0.80  (ok)
amount = 15000  --> 15000 / 10000 = 1.50  --> clamp to 1.0
amount = 0      --> 0 / 10000     = 0.00  (ok)
```

### The sentinel value

When `last_transaction` is null (no previous transaction data), two dimensions lose their source. Instead of guessing a default, we use `-1` — a value clearly outside the 0.0-1.0 range that means "data not available." The reference dataset uses the same convention, so KNN naturally matches "no history" transactions against other "no history" transactions.

## API Contract

### Request

```
POST /authorizations
Content-Type: application/json
```

```json
{
  "id": "3fd455a5-e78f-4951-a5c9-254c4cd394ef",
  "transaction": {
    "amount": 1500.90,
    "installments": 12,
    "requested_at": "2026-03-14T12:15:00Z"
  },
  "customer": {
    "avg_amount": 250.00,
    "tx_count_24h": 5,
    "known_merchants": ["MERC-001", "MERC-042"]
  },
  "merchant": {
    "id": "MERC-099",
    "mcc": "5411",
    "avg_amount": 180.00
  },
  "terminal": {
    "is_online": false,
    "card_present": true,
    "km_from_home": 357.0
  },
  "last_transaction": {
    "timestamp": "2026-03-14T11:30:00Z",
    "km_from_current": 72.3
  }
}
```

**Field rules:**
- `last_transaction` is **nullable**. When null, the affected dimensions (`minutes_since_last_tx` and `km_from_last_tx`) use the sentinel value `-1`.
- All other fields are always present.

### Response

```json
{
  "approved": false,
  "fraud_score": 0.80
}
```

- `fraud_score` — fraction of K nearest neighbors labeled as fraud (0.0 to 1.0)
- `approved` — `true` if `fraud_score < threshold`, `false` otherwise

**HTTP status:** 200 for all valid requests.

## Dimensions

Participants convert the raw payload into a 14-dimension vector. Each value is normalized to 0.0-1.0, except sentinel values which are -1.

| #  | Dimension              | Source                                           | Formula                                     | 0.0 means        | 1.0 means         |
|----|------------------------|--------------------------------------------------|---------------------------------------------|-------------------|--------------------|
| 1  | amount                 | `transaction.amount`                             | min(amount / max_amount, 1.0)               | low amount        | high amount        |
| 2  | installments           | `transaction.installments`                       | min(installments / max_installments, 1.0)   | few               | many               |
| 3  | amount_vs_avg          | `transaction.amount / customer.avg_amount`       | min(ratio / amount_vs_avg_ratio, 1.0)       | normal spending   | way above average  |
| 4  | hour_of_day            | `transaction.requested_at`                       | hour / 23 (0-23, UTC)                       | midnight          | end of day         |
| 5  | day_of_week            | `transaction.requested_at`                       | day / 6 (mon=0, sun=6)                      | monday            | sunday             |
| 6  | minutes_since_last_tx  | `requested_at - last_transaction.timestamp`      | min(minutes / max_minutes, 1.0) or -1       | just happened     | long time ago      |
| 7  | km_from_last_tx        | `last_transaction.km_from_current`               | min(km / max_km, 1.0) or -1                 | same place        | far away           |
| 8  | km_from_home           | `terminal.km_from_home`                          | min(km / max_km, 1.0)                       | at home           | far from home      |
| 9  | tx_count_24h           | `customer.tx_count_24h`                          | min(count / max_tx_count_24h, 1.0)          | few transactions  | many transactions  |
| 10 | is_online              | `terminal.is_online`                             | 0.0 = physical, 1.0 = online               | physical store    | online             |
| 11 | card_present           | `terminal.card_present`                          | 0.0 = not present, 1.0 = present           | not present       | present            |
| 12 | is_new_merchant        | `merchant.id` not in `customer.known_merchants`  | 0.0 = known, 1.0 = new                     | known merchant    | first time         |
| 13 | mcc_risk               | lookup `merchant.mcc` in `mcc_risk.json`         | direct value (already 0..1)                 | safe category     | risky category     |
| 14 | merchant_avg_amount    | `merchant.avg_amount`                            | min(avg / max_merchant_avg_amount, 1.0)     | low-ticket store  | high-ticket store  |

**Sentinel rules:**
- Dimensions 6 and 7 use `-1` when `last_transaction` is null
- MCC not found in `mcc_risk.json` defaults to `0.5`

## KNN Algorithm

```
1. Receive request
2. Normalize 14 fields into a 0..1 vector
3. Calculate distance to every vector in the reference dataset
4. Pick the K nearest neighbors
5. Count fraud vs legit labels
6. fraud_score = fraud_count / K
7. approved = fraud_score < threshold
```

**Parameters:**
- **K = 5** — number of neighbors
- **Distance metric** — cosine distance
- **Threshold = 0.6** — if fraud_score >= 0.6, transaction is not approved

**Labels:** two values only — `"fraud"` and `"legit"`.

## Files Provided to Participants

Three files are published before the competition. Participants bundle them in their container.

### 1. `references.json` — labeled reference vectors

```json
[
  {
    "vector": [0.08, 0.50, 0.60, 0.52, 0.83, 0.03, 0.07, 0.36, 0.25, 0.0, 1.0, 0.0, 0.15, 0.02],
    "label": "fraud"
  },
  {
    "vector": [0.02, 0.08, 0.10, 0.35, 0.17, 0.52, 0.01, 0.01, 0.15, 0.0, 1.0, 0.0, 0.10, 0.03],
    "label": "legit"
  }
]
```

Each vector has 14 dimensions matching the normalization table. Vectors with sentinel values (`-1`) for dimensions 6 and 7 are included for transactions with no prior history.

### 2. `mcc_risk.json` — MCC risk scores

```json
{
  "5411": 0.15,
  "5812": 0.30,
  "7995": 0.85,
  "5944": 0.45
}
```

MCC code to risk score (0.0 to 1.0). If a request's MCC is not in this file, use `0.5` as default.

### 3. `normalization.json` — normalization constants

```json
{
  "max_amount": 10000,
  "max_installments": 12,
  "amount_vs_avg_ratio": 10,
  "max_minutes": 1440,
  "max_km": 1000,
  "max_tx_count_24h": 20,
  "max_merchant_avg_amount": 10000
}
```

Used in the normalization formulas. Values to be calibrated.

## Infrastructure

Standard Rinha setup:
- 2 API instances behind nginx (round-robin load balancing)
- Resource limits per instance (CPU, memory)
- docker-compose based deployment
- Stateless — no shared state required between instances

## To Be Calibrated

The following parameters will be defined after building the example submission and running tests:

- **Normalization constants** — actual values in `normalization.json`
- **Reference dataset size** — number of labeled vectors in `references.json`
- **Resource limits** — CPU and memory per instance
- **Scoring methodology** — how participants are ranked (distribution-based, individual correctness, performance, or a combination)
- **Test parameters** — duration, requests per second, expected fraud rate
