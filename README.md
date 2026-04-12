# Rinha de Backend 2026 — Clojure Example

Example submission for [Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026) — KNN Fraud Detection.

## Running locally

Requires Java 24 and [Leiningen](https://leiningen.org/).

```bash
lein run
```

The API will be available at `http://localhost:3000`.

## Running with Docker

```bash
docker compose -f containerization/docker-compose.yml up --build
```

This starts 2 API instances behind an Nginx load balancer at `http://localhost:9999`.

## Running tests

Unit tests:
```bash
lein test
```

Load test (requires Docker running and [k6](https://k6.io/)):
```bash
./run-test.sh --build
```

## Request examples

Legitimate transaction:
```bash
curl -X POST http://localhost:9999/fraud-score \
  -H "Content-Type: application/json" \
  -d '{
    "id": "tx-001",
    "transaction": {
      "amount": 50.0,
      "installments": 1,
      "requested_at": "2026-03-16T14:00:00Z"
    },
    "customer": {
      "avg_amount": 60.0,
      "tx_count_24h": 2,
      "known_merchants": ["MERC-001"]
    },
    "merchant": {"id": "MERC-001", "mcc": "5411", "avg_amount": 45.0},
    "terminal": {"is_online": false, "card_present": true, "km_from_home": 2.0},
    "last_transaction": {
      "timestamp": "2026-03-16T12:00:00Z",
      "km_from_current": 1.5
    }
  }'
# => {"approved": true, "fraud_score": 0.0}
```

Fraudulent transaction:
```bash
curl -X POST http://localhost:9999/fraud-score \
  -H "Content-Type: application/json" \
  -d '{
    "id": "tx-fraud",
    "transaction": {
      "amount": 9500.0,
      "installments": 12,
      "requested_at": "2026-03-14T03:00:00Z"
    },
    "customer": {
      "avg_amount": 200.0,
      "tx_count_24h": 15,
      "known_merchants": ["MERC-001"]
    },
    "merchant": {"id": "MERC-999", "mcc": "7995", "avg_amount": 8000.0},
    "terminal": {"is_online": true, "card_present": false, "km_from_home": 500.0},
    "last_transaction": {
      "timestamp": "2026-03-14T02:55:00Z",
      "km_from_current": 300.0
    }
  }'
# => {"approved": false, "fraud_score": 1.0}
```
