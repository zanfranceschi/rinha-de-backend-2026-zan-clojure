#!/usr/bin/env bash
set -euo pipefail

IMAGE="ghcr.io/zanfranceschi/rinha-de-backend-2026-janet-zan"
TAG="${1:-latest}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "Building ${IMAGE}:${TAG} ..."
docker build \
  --platform linux/amd64 \
  -f "$SCRIPT_DIR/Dockerfile" \
  -t "${IMAGE}:${TAG}" \
  "$PROJECT_DIR"

echo "Pushing ${IMAGE}:${TAG} ..."
docker push "${IMAGE}:${TAG}"

echo "Done."
