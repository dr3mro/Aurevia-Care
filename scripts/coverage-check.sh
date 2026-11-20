#!/usr/bin/env bash
# Coverage gate for the areas a change touches. Delivery fails on one uncovered
# line or branch, so a worker runs this before its work is committed. Usage:
#   scripts/coverage-check.sh [base-ref]   (default: origin/main)
# Java: JaCoCo at 100% through mvn verify. Frontends: their own check.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
base="${1:-origin/main}"
if ! base_commit=$(git rev-parse --verify --end-of-options "${base}^{commit}" 2>/dev/null); then
  printf 'coverage check: invalid base ref: %s\n' "$base" >&2
  exit 1
fi
tracked=$(git diff --name-only "$base_commit" --)
untracked=$(git ls-files --others --exclude-standard)
changed=$(printf '%s\n' "$tracked" "$untracked" | sort -u)
status=0
if grep -q '^apps/api/' <<<"$changed" || grep -q '^db/' <<<"$changed"; then
  export SMARTCLINIC_PG_ADMIN="${SMARTCLINIC_PG_ADMIN:-postgres}"
  if ! pg_isready -q -h 127.0.0.1 -p 5432 && command -v start-postgres >/dev/null; then start-postgres; fi
  printf 'coverage check: java (JaCoCo 100%%)\n'
  mvn -q -B -f apps/api/pom.xml -Pno-security verify || status=1
fi
if grep -q '^apps/patient-mobile/' <<<"$changed"; then
  printf 'coverage check: patient-mobile (Jest 100%%)\n'
  bash apps/patient-mobile/check.sh || status=1
fi
if grep -q '^apps/staff-web/' <<<"$changed"; then
  printf 'coverage check: staff-web (Vitest thresholds)\n'
  bash apps/staff-web/check.sh || status=1
fi
[[ $status -eq 0 ]] && printf 'coverage check: passed\n'
exit $status
