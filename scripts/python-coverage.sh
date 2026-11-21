#!/usr/bin/env bash
# Run the tooling tests under coverage and fail when a script line that this change adds
# is not covered (branches included). Use --full to require 100% for every script.
# Replaces the plain unittest step of quick-check.sh and check.sh, so the tests run once.
#   COVERAGE_BASE         ref to diff against (default origin/main)
#   REQUIRE_PY_COVERAGE=1 fail instead of falling back to plain unittest when coverage is missing
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-}"
if ! python3 -m coverage --version >/dev/null 2>&1; then
  python3 -m pip install --quiet --user --break-system-packages coverage >/dev/null 2>&1 || true
fi
if ! python3 -m coverage --version >/dev/null 2>&1; then
  if [[ -n "${REQUIRE_PY_COVERAGE:-}" ]]; then
    printf 'python coverage: the coverage package is required (pip install coverage)\n' >&2
    exit 1
  fi
  printf 'python coverage: WARNING coverage is not installed; the tests ran without the coverage check\n' >&2
  python3 -I -m unittest discover -s scripts/tests -q
  exit 0
fi
data="$(mktemp)"
report="$(mktemp)"
trap 'rm -f "$data" "$report"' EXIT
export COVERAGE_FILE="$data"
python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' \
  -m unittest discover -s scripts/tests -q
python3 -m coverage json -q --omit='scripts/tests/*' -o "$report"
python3 -I scripts/python_coverage.py "$report" --base "${COVERAGE_BASE:-origin/main}" ${mode:+"$mode"}
