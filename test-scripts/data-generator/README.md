# Test Data Generator

Seeded generator that produces deterministic authorization request datasets.

## Output Format

```json
[
  {
    "request": { ... },
    "expected": {
      "approved": false,
      "rules_violated": ["restricted_area", "anomalous_interval"]
    }
  }
]
```

## TODO

- Implement generator script (Babashka or standalone Clojure)
- Define seed for preview dataset
- Define coverage targets (% of requests per rule, multi-rule violations, clean transactions)
