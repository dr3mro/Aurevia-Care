#!/usr/bin/env bash
# Canonical API check: unit tests, integration tests and enforced coverage.
set -euo pipefail
cd "$(dirname "$0")"

# OWASP Dependency-Check 13 aborts when NVD_API_KEY is set to an empty string
# (or absent) instead of falling back to its local data. Without a key, scan the
# restored cache with autoUpdate disabled: the CVSS gate still runs and fails on
# a known vulnerable dependency. A real key keeps fresh NVD data.
if [[ -z "${NVD_API_KEY:-}" ]]; then
  set -- -DautoUpdate=false "$@"
fi

mvn -q -B verify "$@"
