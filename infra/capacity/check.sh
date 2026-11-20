#!/usr/bin/env bash
# Canonical S-G5-05 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I capacity_evidence.py fixtures/synthetic_pilot_evidence.json >/dev/null

# The negative fixture must fail a check (exit 1). An unreadable fixture (exit 2)
# would silently disable this control, so require both the exact exit code and
# the named breaches it is meant to demonstrate.
set +e
report=$(python3 -I capacity_evidence.py fixtures/violation_pilot_evidence.json 2>/dev/null)
status=$?
set -e
if [ "$status" -ne 1 ]; then
  printf 'S-G5-05: violation fixture must exit 1, got %s\n' "$status" >&2
  exit 1
fi
for expected in measurement.excluded availability.window results.p95_read_ms \
                results.p95_command_ms results.sustained_rate; do
  if ! printf '%s\n' "$report" | grep -qF "[FAIL] $expected:"; then
    printf 'S-G5-05: violation fixture did not report %s\n' "$expected" >&2
    exit 1
  fi
done

printf 'S-G5-05: OPS-03 evaluator unit tests and fixture checks passed\n'
