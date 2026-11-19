#!/usr/bin/env bash
# Canonical S-G5-06 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
# Build-failing 100% line-coverage gate for the evaluator (stdlib trace).
python3 -I tests/coverage_gate.py
python3 -I recovery_evidence.py fixtures/synthetic_recovery_evidence.json >/dev/null

if python3 -I recovery_evidence.py fixtures/violation_recovery_evidence.json >/dev/null 2>&1; then
  printf 'S-G5-06: violation fixture unexpectedly passed\n' >&2
  exit 1
fi

printf 'S-G5-06: OPS-04/OPS-05 recovery evidence evaluator unit tests and fixture checks passed\n'
