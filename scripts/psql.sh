#!/usr/bin/env bash
# Open psql against the compose PostgreSQL. Extra arguments are passed through, e.g.:
#   ./scripts/psql.sh -c "SELECT * FROM sync.run_summary ORDER BY run_id DESC LIMIT 10"
set -euo pipefail
cd "$(dirname "$0")/.."

exec docker compose exec postgres psql -U sync -d sync "$@"
