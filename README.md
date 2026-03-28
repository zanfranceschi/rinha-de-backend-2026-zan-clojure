# Rinha de Backend 2026 — Exemplo em Clojure

Exemplo de submissao para a [Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026) — Fraud Detection Engine.

## Como rodar

```bash
docker compose up --build
```

A API estara disponivel em `http://localhost:9999/authorizations`.

## Exemplo de requisicao

```bash
curl -X POST http://localhost:9999/authorizations \
  -H "Content-Type: application/json" \
  -d '{
    "transaction": {
      "id": "abc-123",
      "amount": 150.00,
      "currency": "BRL",
      "installments": 3,
      "timestamp": "2026-03-27T14:30:00Z"
    },
    "environment": {
      "merchant": {"id": "m1", "name": "Loja", "mcc": "5411"},
      "terminal": {"id": "t1", "latitude": -23.5505, "longitude": -46.6333}
    },
    "context": {"sale_mcc": "5411"},
    "last_transaction": null
  }'
```

## Resposta

Aprovada:
```json
{"approved": true}
```

Negada:
```json
{"approved": false, "rules_violated": ["restricted_area", "anomalous_interval"]}
```
