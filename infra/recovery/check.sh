#!/usr/bin/env bash
# Canonical OPS-04 recovery-drill gate: unit tests, build-failing 100% line
# coverage and positive/negative CLI runs on synthetic fixture snapshots.
# The CLI runs use --restored-snapshot only, so no database is ever contacted.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I tests/coverage_gate.py

report_dir="$(mktemp -d)"
trap 'rm -rf "$report_dir"' EXIT

pass_report="$report_dir/restore-drill-20261121T083000Z.md"
python3 -I restore_drill.py \
  --source-snapshot fixtures/synthetic_source_snapshot.json \
  --restored-snapshot fixtures/synthetic_restored_snapshot.json \
  --report-dir "$report_dir" \
  --now 2026-11-21T08:30:00Z >/dev/null
test -s "$pass_report"
grep -q 'Verdict: PASS' "$pass_report"
grep -q 'Kind: synthetic_rehearsal' "$pass_report"
grep -q 'NOT a conducted drill' "$pass_report"

status=0
python3 -I restore_drill.py \
  --source-snapshot fixtures/synthetic_source_snapshot.json \
  --restored-snapshot fixtures/violation_restored_snapshot.json \
  --report-dir "$report_dir" \
  --now 2026-11-21T08:31:00Z >"$report_dir/violation.log" 2>&1 || status=$?
if [[ "$status" -ne 1 ]]; then
  printf 'recovery drill: violation fixture returned %s, expected 1\n' "$status" >&2
  cat "$report_dir/violation.log" >&2
  exit 1
fi
fail_report="$report_dir/restore-drill-20261121T083100Z.md"
test -s "$fail_report"
grep -q 'Verdict: FAIL' "$fail_report"

printf 'recovery drill: OPS-04 checksum reconciliation tests, 100%% coverage and fixture checks passed\n'
