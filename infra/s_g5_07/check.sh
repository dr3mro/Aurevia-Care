#!/usr/bin/env bash
# Canonical S-G5-07 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I delivery_safety_evidence.py fixtures/synthetic_evidence.json >/dev/null

if python3 -I delivery_safety_evidence.py fixtures/violation_evidence.json >/dev/null 2>&1; then
  printf 'S-G5-07: violation fixture unexpectedly passed\n' >&2
  exit 1
else
  violation_status=$?
  if [ "$violation_status" -ne 1 ]; then
    printf 'S-G5-07: violation fixture must exit 1, got %s\n' "$violation_status" >&2
    exit 1
  fi
fi

# A relabelled synthetic rehearsal (kind + source only, no run provenance) must
# never be accepted as final evidence. Assert the exact negative exit status.
if python3 -I delivery_safety_evidence.py fixtures/relabelled_violation_evidence.json >/dev/null 2>&1; then
  printf 'S-G5-07: relabelled rehearsal fixture unexpectedly passed\n' >&2
  exit 1
else
  relabelled_status=$?
  if [ "$relabelled_status" -ne 1 ]; then
    printf 'S-G5-07: relabelled rehearsal fixture must exit 1, got %s\n' "$relabelled_status" >&2
    exit 1
  fi
fi

# The repository's authoritative change-safety gate is run for real here rather
# than trusted from an artifact's declared migration command.
python3 -I ../../scripts/check-migrations.py --require-baseline

printf 'S-G5-07: OPS-06 evaluator unit tests and fixture checks passed\n'
