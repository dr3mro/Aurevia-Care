#!/usr/bin/env bash
# Canonical S-G5-05 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I capacity_evidence.py fixtures/synthetic_pilot_evidence.json >/dev/null

if python3 -I capacity_evidence.py fixtures/violation_pilot_evidence.json >/dev/null 2>&1; then
  printf 'S-G5-05: violation fixture unexpectedly passed\n' >&2
  exit 1
fi

printf 'S-G5-05: OPS-03 evaluator unit tests and fixture checks passed\n'
