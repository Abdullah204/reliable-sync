#!/usr/bin/env bash
# Builds the reliable-sync and acme-crm-mock images under the tags docker-compose.yml uses.
#
# The engine comes from the private connector-foundations library:
#   - if ../connector-foundations exists, it is built from that checkout;
#   - otherwise PACKAGES_TOKEN (a GitHub token with read:packages) must be set.
#
#   ./scripts/build-images.sh            # tag :latest
#   TAG=dev ./scripts/build-images.sh
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${TAG:-latest}"
REPO="${IMAGE_REPO:-ghcr.io/abdullah204}"
args=()

if [[ -f ../connector-foundations/settings.gradle.kts && "${USE_LOCAL_FOUNDATIONS:-1}" == "1" ]]; then
  echo "==> Using local ../connector-foundations"
  args+=(--build-context "foundations=../connector-foundations")
elif [[ -n "${PACKAGES_TOKEN:-}" ]]; then
  echo "==> Using connector-foundations from GitHub Packages"
  args+=(--secret "id=packages_token,env=PACKAGES_TOKEN")
else
  echo "error: need ../connector-foundations or PACKAGES_TOKEN (read:packages)." >&2
  echo "       To just run the demo, skip this script: docker compose up pulls the published images." >&2
  exit 1
fi

for target in acme-crm-mock reliable-sync; do
  echo "==> Building ${REPO}/${target}:${TAG}"
  docker build "${args[@]}" --target "$target" -t "${REPO}/${target}:${TAG}" .
done
