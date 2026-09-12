#!/usr/bin/env bash
# Drives fail-open.js and kills Redis underneath it.
#
# The kill has to happen while load is in flight: an outage discovered between
# runs proves nothing about what a request in progress does. Timings here must
# match KILL_AT / RESTORE_AT in fail-open.js.
set -euo pipefail

KILL_AT=20
RESTORE_AT=40

echo "starting load; redis dies at t+${KILL_AT}s, returns at t+${RESTORE_AT}s"

docker compose --profile load run --rm k6 run fail-open.js &
K6_PID=$!

sleep "${KILL_AT}"
echo "t+${KILL_AT}s: stopping redis"
docker compose stop redis >/dev/null

sleep "$((RESTORE_AT - KILL_AT))"
echo "t+${RESTORE_AT}s: starting redis"
docker compose start redis >/dev/null

wait "${K6_PID}"
echo
echo "summary written to k6/fail-open-summary.txt"
