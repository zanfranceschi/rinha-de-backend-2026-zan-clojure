# Authorization — KNN Fraud Detection

This project implements fraud detection using K-Nearest Neighbors (KNN).

See `docs/superpowers/specs/2026-04-11-rinha-2026-knn-fraud-detection-design.md` for the full specification.

## Quick Summary

- `POST /fraud-score` receives a transaction request
- The request is normalized into a 14-dimension vector (0.0 to 1.0)
- The vector is compared against a reference dataset using cosine distance
- The 5 nearest neighbors vote: fraud_score = fraud_count / 5
- If fraud_score >= 0.6, the transaction is not approved

## Resource Files

- `resources/references.json` — labeled reference vectors
- `resources/mcc_risk.json` — MCC code to risk score
- `resources/normalization.json` — normalization constants
