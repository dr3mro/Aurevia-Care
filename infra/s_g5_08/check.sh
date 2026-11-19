#!/usr/bin/env bash
# Canonical S-G5-08 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I tenant_lifecycle_evidence.py fixtures/synthetic_lifecycle_evidence.json >/dev/null
python3 -I tenant_lifecycle_evidence.py fixtures/measured_lifecycle_evidence.json >/dev/null

if python3 -I tenant_lifecycle_evidence.py fixtures/violation_lifecycle_evidence.json >/dev/null 2>&1; then
  printf 'S-G5-08: violation fixture unexpectedly passed\n' >&2
  exit 1
fi

printf 'S-G5-08: OPS-07 evaluator unit tests and fixture checks passed\n'
