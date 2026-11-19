#!/usr/bin/env bash
# Canonical API check: unit tests, integration tests and enforced coverage.
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -B verify "$@"
