# Rinha de Backend 2006 - Exemplo em Clojure

POST /v1/fraud/authorizations/verify
```json
{
    "id": "41b44ad7-37a3-4ddc-b7fb-7c6ea803b07d",
    "status": "pending",
    "card-holder-id": "aada5902-2a2b-4000-b495-1f6aad3400a1",
    "transaction": {
        "timestamp": "2026-03-14T14:00:00.000-03:00",
        "amount": 101.0,
        "currency": "brl",
        "number-of-intallments": 12
    },
    "environment": {
        "type": "onsite",
        "card": {
            "id": "240cb527-92ea-4451-9884-94784bad072a",
            "type": "credit"
        },
        "merchant": {
            "id": "3e106a59-ee63-45b0-a8b9-3416d95ee485",
            "mcc": "7801"
        },
        "domain": "win-big-tonight.net",
        "card-present": true,
        "card-holder-present": true,
        "terminal": {
            "id": "b2395638-fdcf-44ad-bcd1-a27968b84a16",
            "manufacturer": "Ingenico",
            "model": "move\/5000",
            "serial-number": "123123ABC",
            "lat": -23.5505,
            "lon": -46.6333
        }
    },
    "context": {
        "sale-mcc": "5813",
        "payment-ip": "192.168.0.10"
    },
    "last-transactions": [
        {
            "id": "f93b819c-8e23-44b8-b032-5224763656fd",
            "status": "denied",
            "card-holder-id": "aada5902-2a2b-4000-b495-1f6aad3400a1",
            "transaction": {
                "timestamp": "2026-03-14T13:57:00.000-03:00",
                "amount": 1000.0,
                "currency": "brl",
                "number-of-intallments": 12
            },
            "environment": {
                "type": "online",
                "card": {
                    "id": "240cb527-92ea-4451-9884-94784bad072a",
                    "type": "credit"
                },
                "merchant": {
                    "id": "3e106a59-ee63-45b0-a8b9-3416d95ee485",
                    "mcc": "7801"
                },
                "domain": "qqcoisa.com",
                "card-present": true,
                "card-holder-present": true,
                "terminal": null
            },
            "context": {
                "sale-mcc": "7801",
                "payment-ip": "192.168.0.10"
            }
        },
        {
            "id": "98101582-cd05-4ed2-85df-48bd4e11ae76",
            "status": "approved",
            "card-holder-id": "aada5902-2a2b-4000-b495-1f6aad3400a1",
            "transaction": {
                "timestamp": "2026-03-14T10:00:00.000-03:00",
                "amount": 80.0,
                "currency": "brl",
                "number-of-intallments": 12
            },
            "environment": {
                "type": "onsite",
                "card": {
                    "id": "240cb527-92ea-4451-9884-94784bad072a",
                    "type": "credit"
                },
                "merchant": {
                    "id": "381ae548-d660-437d-b46e-a31c2b162d36",
                    "mcc": "7801"
                },
                "domain": null,
                "card-present": true,
                "card-holder-present": true,
                "terminal": {
                    "id": "b2395638-fdcf-44ad-bcd1-a27968b84a16",
                    "manufacturer": "Ingenico",
                    "model": "move\/5000",
                    "serial-number": "123123ABC",
                    "lat": -23.15,
                    "lon": -46.633
                }
            },
            "context": {
                "sale-mcc": "7801",
                "payment-ip": null
            }
        },
        {
            "id": "6e09a1ce-ed7b-40af-a3e8-e9d6cda8a555",
            "status": "approved",
            "card-holder-id": "aada5902-2a2b-4000-b495-1f6aad3400a1",
            "transaction": {
                "timestamp": "2026-03-13T10:00:12.300-03:00",
                "amount": 80.0,
                "currency": "brl",
                "number-of-intallments": 12
            },
            "environment": {
                "type": "onsite",
                "card": {
                    "id": "240cb527-92ea-4451-9884-94784bad072a",
                    "type": "credit"
                },
                "merchant": {
                    "id": "c3c91f5b-00f4-4850-aa5a-363363739a05",
                    "mcc": "7801"
                },
                "domain": null,
                "card-present": true,
                "card-holder-present": true,
                "terminal": {
                    "id": "b2395638-fdcf-44ad-bcd1-a27968b84a16",
                    "manufacturer": "Ingenico",
                    "model": "move\/5000",
                    "serial-number": "123123ABC",
                    "lat": -20.9505,
                    "lon": -40.1333
                }
            },
            "context": {
                "sale-mcc": "7801",
                "payment-ip": "192.168.0.10"
            }
        }
    ]
}    
```