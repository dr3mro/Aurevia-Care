#!/usr/bin/env bash
# Canonical S-G5-08 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I tenant_lifecycle_evidence.py fixtures/synthetic_lifecycle_evidence.json >/dev/null
python3 -I tenant_lifecycle_evidence.py fixtures/measured_lifecycle_evidence.json >/dev/null

# The negative fixture must fail a check (exit 1). An unreadable fixture (exit 2)
# would silently disable this control, so require both the exact exit code and
# the named breaches it is meant to demonstrate.
set +e
report=$(python3 -I tenant_lifecycle_evidence.py fixtures/violation_lifecycle_evidence.json 2>/dev/null)
status=$?
set -e
if [ "$status" -ne 1 ]; then
  printf 'S-G5-08: violation fixture must exit 1, got %s\n' "$status" >&2
  exit 1
fi
for expected in evidence_conflict export.record_checksums export.document_authorizations \
                retention.legal_hold_respected retention.backup_hold_respected \
                retention.backup_expiration_entries disputed_access.reverification \
                scenarios; do
  if ! printf '%s\n' "$report" | grep -qF "[FAIL] $expected:"; then
    printf 'S-G5-08: violation fixture did not report %s\n' "$expected" >&2
    exit 1
  fi
done

printf 'S-G5-08: OPS-07 evaluator unit tests and fixture checks passed\n'
