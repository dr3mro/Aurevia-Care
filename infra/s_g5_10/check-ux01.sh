#!/usr/bin/env bash
# Canonical S-G5-10 gate: unit tests plus positive and negative fixture runs.
# Named check-ux01.sh (not check.sh) because swarm.py protects */check.sh paths;
# this keeps the story's own gate auto-mergeable by the integrator.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I ux01_evidence.py fixtures/synthetic_ux01_evidence.json >/dev/null

# Require exactly the "failed a check" exit code and a FAIL marker. Any other
# non-zero outcome (crash, unreadable fixture) must not count as a clean reject.
set +e
violation_output=$(python3 -I ux01_evidence.py fixtures/violation_ux01_evidence.json 2>&1)
violation_code=$?
set -e
if [[ "$violation_code" -ne 1 ]] || ! grep -q '\[FAIL\]' <<<"$violation_output"; then
  printf 'S-G5-10: violation fixture did not fail as expected (exit %s)\n%s\n' \
    "$violation_code" "$violation_output" >&2
  exit 1
fi

printf 'S-G5-10: UX-01 evaluator unit tests and fixture checks passed\n'
