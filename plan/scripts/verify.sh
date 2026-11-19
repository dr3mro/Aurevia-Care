#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/check.sh
version=${1:-dev}
revision=$(git rev-parse HEAD 2>/dev/null || printf unknown)
project_name=$(python3 scripts/project-name.py displayName)
docker build --build-arg "PROJECT_NAME=$project_name" --build-arg "VERSION=$version" --build-arg "REVISION=$revision" -t smartclinic:test .
scripts/smoke-image.sh smartclinic:test "$version"
