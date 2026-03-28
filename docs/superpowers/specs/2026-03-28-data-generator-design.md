# Data Generator Design

Seeded generator that produces deterministic authorization request datasets for load testing via k6.

Scope: TODO items 1 (implement generator) and 2 (define seed) from `test-scripts/data-generator/README.md`.

## Namespace & API

Single namespace: `rinha-de-backend-2026-exemplo.data-generator`

Two public functions:

- `(generate-dataset)` — returns the dataset as a Clojure vector of maps (EDN) with keyword keys. ~200 entries by default (configurable via a top-level def).
- `(write-dataset! dataset)` — takes the EDN dataset, converts keyword keys to strings, and writes JSON to `test-scripts/data-generator/preview-dataset.json`.

Typical REPL usage:

```clojure
(def ds (generate-dataset))
;; inspect, filter, etc.
(write-dataset! ds)
```

## Seed

Hardcoded seed: `42`. Used to create a `java.util.Random` instance. All randomized values (transaction IDs, amounts, coordinates, timestamps, MCC selection) are derived from this single PRNG so the output is fully deterministic.

## Request Construction

Requests are built by **scenario functions**, each targeting a specific rule outcome. Each function constructs a request payload and knows which rules it intends to violate (or not).

After construction, `authorization/authorize` is called on each request to compute the `expected` field. This ensures the expected output matches the actual rule implementation.

### Scenarios

| Scenario | Target | Construction strategy |
|---|---|---|
| `clean` | All rules pass | Safe coords (far from polygons), compatible MCCs, amount in range, no `last_transaction` or >5 min gap |
| `restricted-area` | `restricted_area` violated | Pick a coordinate inside a known polygon from `restricted_areas.json` |
| `anomalous-interval` | `anomalous_interval` violated | Set `last_transaction.timestamp` < 5 minutes before `transaction.timestamp` |
| `anomalous-travel-speed` | `anomalous_travel_speed` violated | Set `last_transaction` location far away with a short time gap (speed exceeds threshold) |
| `mcc-amount-restriction` | `mcc_amount_restriction` violated | Pick an MCC with limits from `mccs_restrictions.json`, set amount outside min/max |
| `mcc-relation-restriction` | `mcc_relation_restriction` violated | Set `sale_mcc` to an MCC not in the allowed set for the merchant's MCC |
| `multi-rule` | 2-3 rules violated | Combine construction strategies from multiple scenarios in one request |

### Resource files used

- `resources/restricted_areas.json` — GeoJSON polygons for restricted area coordinates
- `resources/mccs_restrictions.json` — MCC amount min/max limits
- `resources/mcc_relation_restrictions.json` — MCC compatibility mappings

### Randomized fields

The seeded PRNG varies non-essential fields across requests to keep data diverse:

- Transaction IDs (e.g., `"tx-42-001"`, `"tx-42-002"`)
- Merchant names and IDs
- Exact amounts (within the violating or non-violating range)
- Specific polygon/MCC/coordinate choices when multiple options exist
- Timestamp base offsets

## Distribution

~200 requests distributed across scenarios. Clean transactions form the majority (~40-50%), with each violation rule getting meaningful representation. Multi-rule scenarios cover a selection of 2-3 rule combinations.

The exact count per scenario is controlled by the hardcoded seed and distribution logic — since the seed is fixed, the distribution is always the same.

## Output Format

### EDN (in-memory)

```clojure
[{:request {:transaction {:id "tx-42-001" :amount 150.0 :currency "BRL" :installments 1
                          :timestamp "2026-03-27T14:30:00Z"}
            :environment {:merchant {:id "m1" :name "Loja" :mcc "5411"}
                          :terminal {:id "t1" :latitude 0.0 :longitude 0.0}}
            :context {:sale_mcc "5411"}
            :last_transaction nil}
  :expected {:approved true}}
 {:request { ... }
  :expected {:approved false :rules_violated ["restricted_area"]}}]
```

### JSON (written to file)

```json
[
  {
    "request": {
      "transaction": { "id": "tx-42-001", "amount": 150.0, ... },
      "environment": { ... },
      "context": { "sale_mcc": "5411" },
      "last_transaction": null
    },
    "expected": {
      "approved": true
    }
  }
]
```

Written to: `test-scripts/data-generator/preview-dataset.json`
