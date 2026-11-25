#!/usr/bin/env bash
# One-command demo: start the stack, let a few sync cycles run, print the support report.
#   ./scripts/demo.sh            # pulls published images; waits 45 s
#   WAIT=90 ./scripts/demo.sh
#   BUILD=1 ./scripts/demo.sh    # build images locally first (see scripts/build-images.sh)
set -euo pipefail
cd "$(dirname "$0")/.."

WAIT="${WAIT:-45}"

if [[ "${BUILD:-0}" == "1" ]]; then
  ./scripts/build-images.sh
fi

echo "==> Starting postgres, acme-crm-mock, reliable-sync"
docker compose up -d

echo "==> Letting the sync run for ${WAIT}s (the mock injects 5xx, 429s and bad records meanwhile)"
sleep "$WAIT"

./scripts/report.sh

cat <<'EOF'

Next steps:
  docker compose logs -f reliable-sync     # watch retries, dead letters and alerts live
  ./scripts/psql.sh                        # explore acme.* and sync.* tables
  docker compose down -v                   # stop and delete everything
EOF
