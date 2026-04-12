#!/usr/bin/env bash
set -euo pipefail

REF_SIZE=${1:-200}
PAYLOAD_SIZE=${2:-200}

echo "Generating $REF_SIZE reference vectors and $PAYLOAD_SIZE test payloads..."

SECONDS=0

lein trampoline run -m clojure.main -e "
  (require '[rinha-de-backend-2026-exemplo.data-generator :as gen])
  (gen/generate-all! $REF_SIZE $PAYLOAD_SIZE)
"
echo "Done in ${SECONDS}s"
