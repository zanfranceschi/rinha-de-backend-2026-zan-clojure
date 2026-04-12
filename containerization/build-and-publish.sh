#!/bin/bash
set -e

IMAGE="zanfranceschi/rinha-de-backend-2026-clojure-exemplo"
TAG=$(date +%Y%m%d%H%M)

docker build -t "$IMAGE:$TAG" -t "rinha-de-backend-2026-clojure-exemplo:latest" -f Dockerfile ..
docker push "$IMAGE:$TAG"

echo "Published $IMAGE:$TAG"
