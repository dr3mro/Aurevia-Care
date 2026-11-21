#!/usr/bin/env bash
# Fast frontend typecheck/lint gate for the areas a change touches.
# Delivery's Frontend standards job fails on one TypeScript error, so the worker
# runs this after implement and re-prompts the agent until it is green (or the
# repair budget is exhausted). Jest/Vitest green alone is not enough.
# Usage: scripts/frontend-typecheck-check.sh [base-ref]   (default: origin/main)
set -euo pipefail
cd "$(dirname "$0")/.."
base="${1:-origin/main}"
changed=$( { git diff --name-only "$base" 2>/dev/null; git ls-files --others --exclude-standard; } | sort -u)
status=0

run_module() {
  local module=$1
  if ! grep -q "^${module}/" <<<"$changed"; then
    return 0
  fi
  printf 'frontend typecheck: %s\n' "$module"
  (
    cd "$module"
    if [[ ! -x node_modules/.bin/tsc && ! -d node_modules ]]; then
      npm ci --no-audit --no-fund
    elif [[ ! -x node_modules/.bin/tsc ]]; then
      npm ci --no-audit --no-fund
    fi
    npm run typecheck
    npm run lint
  ) || status=1
}

run_module apps/patient-mobile
run_module apps/staff-web

if [[ $status -eq 0 ]]; then
  printf 'frontend typecheck: passed (or no frontend files changed)\n'
fi
exit $status
