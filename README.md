# Rinha de Backend 2006 - Exemplo em Clojure

POST /authorizations
```json
{
    "id": "3fd455a5-e78f-4951-a5c9-254c4cd394ef",
    "transaction" : {
        "amount": 1500.90,
        "currency": "BRL",
        "numberOfInstallments": 12,
        "timestamp": "2026-03-14T12:12:12.300-03:00"
    },
    "environment" : {
        "merchant": {
            "id": "MERC-001",
            "name": "Lojas Mel",
            "merchantCategoryCode": "5411" // mcc
        },
        "terminal" : {
            "id": "c736ab68-87f9-4a80-9ded-043324950b59",
            // precisa bater com txtChannel
            "type": "POSP", // POSP, VPIP, MPOS, ECTT
            "location": {
                "name": "Loja 12", // ou www.ponto.com para online
                // só para compras físicas
                "geo": {
                    "latitude": -23.5505,
                    "longitude": -46.6333
                }
            },
            // só para compras físicas
            "hardware": {
                "manufacturer": "Ingenico",
                "model": "move/5000",
                "serialNumber": "123123ABC"
            }
        },
        "card": {
            "token": "9876543210987654",
            "pan": "4111********1111",
            "expiration": "2032-04",
            "type": "CREDIT"
        }
    },
    "context" : {
        "payment" : {
            "attendanceIndicator": "UATD", // UATD|ATND
            "cardPresent": false,
            "cardholderPresent": true,
            "txChannel": "INTV", // POSP | INTV | VEND
            "entryMode": "KBDD", // CHIP | CTLS | MGRS | KBDD
            "ipAddress": "189.121.45.210"
        },
        "sale": {
            "id": "099dcc8d-dbcf-439b-94d5-5667b21d882c",
            "merchantCategoryCode": "5411" // mcc
        }
    }
}
```