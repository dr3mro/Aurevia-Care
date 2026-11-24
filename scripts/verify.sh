#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Dedicated CI jobs may already have run the checks; the image job then only
# builds and smoke-tests, keeping each unit small and independently cacheable.
if [[ "${AUREVIA_SKIP_CHECKS:-0}" != "1" ]]; then
  scripts/check.sh
fi
version=${1:-dev}
revision=$(git rev-parse HEAD 2>/dev/null || printf unknown)
project_name=$(python3 scripts/project-name.py displayName)
docker build --build-arg "PROJECT_NAME=$project_name" --build-arg "VERSION=$version" --build-arg "REVISION=$revision" -t aurevia:test .
scripts/smoke-image.sh aurevia:test "$version"
