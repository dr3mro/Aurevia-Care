#!/usr/bin/env bash
# Canonical S-G5-09 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I onboarding.py fixtures/synthetic_onboarding_manifest.json >/dev/null

if python3 -I onboarding.py fixtures/violation_onboarding_manifest.json >/dev/null 2>&1; then
  printf 'S-G5-09: violation fixture unexpectedly passed\n' >&2
  exit 1
fi

printf 'S-G5-09: OPS-08 evaluator unit tests and fixture checks passed\n'
