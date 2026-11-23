#!/usr/bin/env bash
# Build and test the whole project locally.
# Build artifacts go to their module default dirs (apps/api/target, apps/staff-web/dist,
# apps/staff-web/coverage), all of which are gitignored. A top-level build/ summary
# is written for convenience.
# Usage: bash scripts/build-and-test.sh [--skip-docker] [--skip-frontend]
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_DIR="build"
mkdir -p "$BUILD_DIR"

SKIP_DOCKER=0
SKIP_FRONTEND=0
for arg in "$@"; do
  case "$arg" in
    --skip-docker) SKIP_DOCKER=1 ;;
    --skip-frontend) SKIP_FRONTEND=1 ;;
    -h|--help)
      echo "Usage: bash scripts/build-and-test.sh [--skip-docker] [--skip-frontend]"
      exit 0 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

echo "==> Running policy/tooling checks (scripts/check.sh)"
bash scripts/check.sh

if [[ "$SKIP_FRONTEND" == "0" ]]; then
  echo "==> Frontend typecheck (staff-web)"
  (cd apps/staff-web && npm run typecheck)
  echo "==> Frontend typecheck (patient-mobile)"
  (cd apps/patient-mobile && npm run typecheck)
fi

if [[ "$SKIP_DOCKER" == "0" ]]; then
  echo "==> Building Docker image"
  bash scripts/verify.sh
fi

echo "==> All checks passed"
