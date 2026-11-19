#!/usr/bin/env bash
# Canonical S-G5-07 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I delivery_safety_evidence.py fixtures/synthetic_evidence.json >/dev/null

if python3 -I delivery_safety_evidence.py fixtures/violation_evidence.json >/dev/null 2>&1; then
  printf 'S-G5-07: violation fixture unexpectedly passed\n' >&2
  exit 1
fi

# The repository's authoritative change-safety gate is run for real here rather
# than trusted from an artifact's declared migration command.
python3 -I ../../scripts/check-migrations.py --require-baseline

printf 'S-G5-07: OPS-06 evaluator unit tests and fixture checks passed\n'
