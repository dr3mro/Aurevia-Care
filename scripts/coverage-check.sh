#!/usr/bin/env bash
# Coverage gate for the areas a change touches. Delivery fails on one uncovered
# line or branch, so a worker runs this before its work is committed. Usage:
#   scripts/coverage-check.sh [base-ref]   (default: origin/main)
# Java: JaCoCo at 100% through mvn verify. Frontends: their own check.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
base="${1:-origin/main}"
changed=$( { git diff --name-only "$base" 2>/dev/null; git ls-files --others --exclude-standard; } | sort -u)
status=0
if grep -q '^apps/api/' <<<"$changed" || grep -q '^db/' <<<"$changed"; then
  export OREVIA_PG_ADMIN="${OREVIA_PG_ADMIN:-postgres}"
  if ! pg_isready -q -h 127.0.0.1 -p 5432 && command -v start-postgres >/dev/null; then start-postgres; fi
  printf 'coverage check: java (JaCoCo 100%%)\n'
  mvn -q -B -f apps/api/pom.xml -Pno-security verify || status=1
fi
if grep -q '^apps/patient-mobile/' <<<"$changed"; then
  printf 'coverage check: patient-mobile (Jest 100%%)\n'
  bash apps/patient-mobile/check.sh || status=1
fi
if grep -q '^apps/staff-web/' <<<"$changed"; then
  # Typecheck before coverage: Delivery Frontend standards fails on TS errors even
  # when Vitest is green (the same trap that bit patient-mobile READY-48).
  printf 'coverage check: staff-web (typecheck + Vitest thresholds)\n'
  (cd apps/staff-web && npm run -s typecheck && npm run -s test:coverage) || status=1
fi
[[ $status -eq 0 ]] && printf 'coverage check: passed\n'
exit $status
