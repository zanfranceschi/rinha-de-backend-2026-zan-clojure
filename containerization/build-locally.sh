#!/bin/bash
set -e

docker build -t "rinha-de-backend-2026-zan-clojure:latest" -f Dockerfile ..

echo "image built"
