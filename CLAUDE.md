# Rinha de Backend 2026 — Clojure Example Submission

Transaction authorization API with KNN-based fraud detection. Stateless, no database — all reference data loaded in-memory from JSON resources at startup.

## Stack

Clojure 1.12 · Ring/Compojure · Leiningen · Docker + Nginx

## Commands

```
lein test                        # run all tests
lein run                         # start server (port 3000)
docker compose up --build        # full stack: 2 API instances + nginx on :9999
k6 run test-scripts/k6/test.js   # load test (requires preview-dataset.json)
```

Dev environment: `nix-shell` (loads lein, JDK, k6, jq).

## Architecture

```
POST /fraud-score → Ring/Compojure → normalize → KNN (cosine distance) → {approved, fraud_score}
```

- **No database.** Reference files (`resources/*.json`) are loaded once at startup.
- **Pure functions.** Input is normalized into a 14-dimension vector; KNN votes on fraud using 5 nearest neighbors.
- **Deployment:** 2 stateless instances behind nginx round-robin. Each limited to 0.5 CPU / 256 MB.

## Non-obvious decisions

- Cosine distance is used for KNN — works well for normalized vectors where direction matters more than magnitude.
- Missing fields are encoded as sentinel value `-1` to distinguish from a legitimate `0.0`.
- Profile-based data generation: transactions are generated from behavioral profiles (e.g., high-spender, occasional) to produce realistic fraud/non-fraud distributions.
- Test data generator uses seeds 42 and 4242 for determinism. `preview-dataset.json` is committed to git.

## Testing

- `clojure.test` with parallel source/test namespaces.
- Normalization tests verify each field maps correctly to the [0.0, 1.0] range (or -1 sentinel).
- KNN tests verify fraud_score thresholds and neighbor voting logic.
- Data generator tests cross-check generated payloads against expected fraud labels from profiles.
