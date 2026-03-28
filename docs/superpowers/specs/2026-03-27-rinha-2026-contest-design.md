# Rinha de Backend 2026 — Fraud Detection Engine

## Challenge Overview

Participants build a stateless HTTP API that receives payment authorization requests and returns approve/deny decisions based on 5 fraud detection rules. Each request is self-contained — the payload includes the current transaction, environment context, and the last transaction for history-dependent rules.

A single test round evaluates both correctness and performance.

## API Contract

### Endpoint

`POST /authorizations`

### Request Payload

The payload structure is loosely based on the ISO 20022 format.

```json
{
  "transaction": {
    "id": "uuid",
    "amount": 150.00,
    "currency": "BRL",
    "installments": 3,
    "timestamp": "2026-03-27T14:30:00Z"
  },
  "environment": {
    "merchant": {
      "id": "uuid",
      "name": "Store Name",
      "mcc": "5411"
    },
    "terminal": {
      "id": "uuid",
      "latitude": -23.5505,
      "longitude": -46.6333
    }
  },
  "context": {
    "sale_mcc": "5411"
  },
  "last_transaction": {
    "amount": 50.00,
    "timestamp": "2026-03-27T14:20:00Z",
    "terminal": {
      "latitude": -23.5600,
      "longitude": -46.6400
    }
  }
}
```

`last_transaction` may be `null` (first transaction ever). When null, rules that depend on it (anomalous travel speed, anomalous interval) pass automatically.

### Response

**Approved:**
```json
{
  "approved": true
}
```

**Denied:**
```json
{
  "approved": false,
  "rules_violated": ["restricted_area", "anomalous_interval"]
}
```

HTTP status is `200` in both cases.

## Fraud Detection Rules

All 5 rules are evaluated for every request. The response must include all violated rules, not just the first one found.

### Rule 1: Geographic Restricted Zones (`restricted_area`)

A GeoJSON file defines polygons of restricted geographic zones. If the terminal's coordinates (latitude, longitude) fall inside any restricted polygon, the transaction is denied.

Participants must implement or use a point-in-polygon algorithm.

### Rule 2: Anomalous Travel Speed (`anomalous_travel_speed`)

Compares the current terminal location with the terminal location from `last_transaction`. Calculates the distance and implied travel speed based on the time elapsed.

Speed thresholds by distance range:

| Distance (km) | Max Speed (km/h) |
|---|---|
| 0–10 | 15 |
| 10–50 | 60 |
| 50–200 | 120 |
| 200–1000 | 350 |
| 1000+ | 700 |

If implied speed exceeds the threshold for the calculated distance range, deny the transaction.

Skipped when `last_transaction` is null.

### Rule 3: Anomalous Interval (`anomalous_interval`)

Calculates the time elapsed (in minutes) between the current transaction's timestamp and `last_transaction`'s timestamp.

If the interval is less than 5 minutes, deny the transaction.

Skipped when `last_transaction` is null.

### Rule 4: MCC Amount Restrictions (`mcc_amount_restriction`)

A JSON file maps Merchant Category Codes (MCCs) to amount limits (min and/or max). If the transaction amount for the given `sale_mcc` violates these limits, deny the transaction.

If the `sale_mcc` is not in the restrictions file, this rule passes.

### Rule 5: MCC Relation Restriction (`mcc_relation_restriction`)

A JSON file maps merchant MCCs to their compatible sale MCCs. If the `sale_mcc` from the context is not in the allowed set for the merchant's MCC, deny the transaction.

If the merchant's MCC is not in the relations file, this rule passes.

## Static Data Files

Three data files are provided in the contest repository. Participants bundle them into their solution and load at startup:

1. **`restricted_areas.json`** — GeoJSON FeatureCollection with restricted zone polygons
2. **`mccs_restrictions.json`** — Array of MCC amount restrictions (`mcc`, `max_amount`, `min_amount`, `description`)
3. **`mcc_relation_restrictions.json`** — Array of MCC relationship rules (`mcc`, `description`, `related[]`)

## Architecture Restrictions

Like previous editions, submissions must follow infrastructure constraints:

- **Docker Compose** — the entire solution runs via `docker-compose up`
- **CPU and memory limits** — defined per container (exact limits TBD)
- **Minimum 2 API instances** — behind a load balancer
- **Load balancer** — participant's choice (nginx, haproxy, etc.)

## Test Data & Scoring

### Test Runner

Tests are executed using **k6**.

### Test Data Generation

A seeded generator script produces a deterministic dataset of authorization requests with known expected outcomes (approve/deny + which rules are violated).

- **Preview dataset:** Published in the contest repo. Participants use it during development.
- **Final dataset:** Generated from the same tool with a different seed and more complete coverage. Only used during final scoring. Not published in advance.

### Scoring

Scoring criteria (correctness weight vs performance weight) to be defined after the rules are validated through implementation. Both correctness and performance are evaluated in a single test round.

## Repository Structure

This repo serves as the example submission. Test scripts (k6, data generator) will live in a separate directory within this repo for convenience during development, but the official test infrastructure lives elsewhere.

```
/
├── test-scripts/          # k6 scripts and test data generator (development convenience)
│   ├── k6/
│   └── data-generator/
├── src/                   # Example Clojure implementation
├── resources/             # Static data files (restricted zones, MCC restrictions)
└── docker-compose.yml     # Submission infrastructure (API instances + load balancer)
```

## Contest Deliverables

1. **Challenge spec document** — Rules, API contract, payload/response format
2. **Static data files** — Restricted zones GeoJSON, MCC restrictions, MCC relations
3. **Test data generator** — Seeded script that outputs deterministic test datasets
4. **k6 test script** — Sends generated requests and evaluates correctness + performance (lives in separate contest repo)
5. **Example implementation** — This Clojure project as a reference submission
