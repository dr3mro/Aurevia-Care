#!/usr/bin/env bash
# Canonical loadtest gate: unit tests plus build-failing 100% line coverage.
set -euo pipefail
cd "$(dirname "$0")"

python3 -I -m unittest discover -s tests -q
python3 -I tests/coverage_gate.py

printf 'loadtest: OPS-03 load generator unit tests and 100%% line coverage passed\n'
