#!/usr/bin/env bash
# Canonical S-G5-11 gate: unit tests plus positive and negative fixture runs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I ops_queues.py fixtures/synthetic_ops_queues_evidence.json >/dev/null

if python3 -I ops_queues.py fixtures/violation_ops_queues_evidence.json >/dev/null 2>&1; then
  printf 'S-G5-11: violation fixture unexpectedly passed\n' >&2
  exit 1
fi

printf 'S-G5-11: ADM-02 evaluator unit tests and fixture checks passed\n'
