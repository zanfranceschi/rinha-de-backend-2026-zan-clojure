#!/bin/bash
set -e

IMAGE="zanfranceschi/rinha-de-backend-2026-zan-clojure"
TAG="ann-ivf-$(date +%Y%m%d%H%M)"

docker build \
    -t "$IMAGE:$TAG" \
    -t "$IMAGE:latest" \
    -t "rinha-de-backend-2026-zan-clojure:latest" \
    -f Dockerfile ..

docker push "$IMAGE:$TAG"

echo "Published $IMAGE:$TAG"
