#!/usr/bin/env bash
set -euo pipefail

TEST_FILE="test-scripts/k6/test.js"

start_rate=$(grep -oP 'startRate:\s*\K\d+' "$TEST_FILE")

durations=($(grep -oP "duration:\s*'\K[0-9]+" "$TEST_FILE"))
targets=($(grep -oP "target:\s*\K\d+" "$TEST_FILE"))

cumulative=0
prev_rate=$start_rate

printf "%-7s %-10s %-6s %-6s %-12s %-12s\n" "stage" "duration" "from" "to" "stage_reqs" "cumulative"
printf "%-7s %-10s %-6s %-6s %-12s %-12s\n" "-----" "--------" "----" "----" "----------" "----------"

for i in "${!durations[@]}"; do
    d=${durations[$i]}
    t=${targets[$i]}
    stage_reqs=$(( (prev_rate + t) * d / 2 ))
    cumulative=$((cumulative + stage_reqs))
    printf "%-7s %-10s %-6s %-6s %-12s %-12s\n" "$((i+1))" "${d}s" "$prev_rate" "$t" "$stage_reqs" "$cumulative"
    prev_rate=$t
done
