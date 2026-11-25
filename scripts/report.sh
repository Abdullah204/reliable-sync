#!/usr/bin/env bash
# Print recent sync runs and open dead letters from the running compose stack.
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose run --rm --no-deps -e SYNC_INTERVAL_SECONDS=0 reliable-sync report 2>/dev/null
