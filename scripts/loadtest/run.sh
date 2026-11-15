#!/usr/bin/env bash
# Runs the k6 load test against a running Compose stack, from a container on the stack's network
# (so the numbers include nginx, the backend and PostgreSQL, but not the host's port forwarding).
#
#   COMPOSE_PROJECT=esm ./scripts/loadtest/run.sh            # reads ESM_DEMO_PASSWORD from .env
#
# Seed volume first (scripts/loadtest/seed-volume.sql) for meaningful queue and report timings.
# The summary is written to scripts/loadtest/results/summary.json (git-ignored).
set -euo pipefail
cd "$(dirname "$0")/../.."

project="${COMPOSE_PROJECT:-esm}"
if [[ -z "${ESM_DEMO_PASSWORD:-}" ]]; then
  ESM_DEMO_PASSWORD=$(sed -n 's/^ESM_DEMO_PASSWORD=//p' .env | tail -n 1)
fi
mkdir -p scripts/loadtest/results

docker run --rm --network "${project}_default" \
  -e ESM_DEMO_PASSWORD="$ESM_DEMO_PASSWORD" \
  -e DURATION="${DURATION:-3m}" -e AGENTS="${AGENTS:-20}" -e REQUESTERS="${REQUESTERS:-10}" \
  -v "$PWD/scripts/loadtest:/scripts" \
  grafana/k6:0.57.0 run --summary-export /scripts/results/summary.json /scripts/service-desk.js
