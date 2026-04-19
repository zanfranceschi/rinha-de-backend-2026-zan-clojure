#!/bin/bash
set -e

docker build -t "rinha-de-backend-2026-clojure-exemplo:latest" -f Dockerfile ..

echo "image built"
