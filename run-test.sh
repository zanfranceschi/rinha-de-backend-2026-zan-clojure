#!/usr/bin/env bash
set -euo pipefail

BUILD_FLAG=""
if [[ "${1:-}" == "--build" ]]; then
  BUILD_FLAG="--build"
fi

echo "Starting API..."
docker compose up $BUILD_FLAG -d

echo "Waiting for API to be ready..."
until curl -sf http://localhost:9999/health > /dev/null 2>&1; do
  sleep 1
done
echo "API is ready!"

echo "Running k6 test..."
K6_WEB_DASHBOARD=true \
# K6_WEB_DASHBOARD_OPEN=true \
K6_WEB_DASHBOARD_EXPORT=test-scripts/k6/report.html \
k6 run test-scripts/k6/test.js

echo ""
echo "Results: test-scripts/k6/results.json"
echo "Report:  test-scripts/k6/report.html"
echo ""

echo ""
echo "Stopping API..."
docker compose down

cat test-scripts/k6/results.json | jq