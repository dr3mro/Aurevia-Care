#!/usr/bin/env bash
# Build and test the whole project locally, with the same gates CI runs, so a green
# run here means a push cannot turn main red.
#
# It mirrors .github/workflows/delivery.yml job for job:
#   policy         shell syntax/lint, actionlint, agent pack, docs, secrets, names,
#                  wiring, constraints and migration checks
#   script-tests   Python tooling tests and their coverage gate
#   frontend       staff-web and patient-mobile typecheck, ESLint (zero warnings),
#                  Prettier, tests with 100% coverage, production build, bundle budget
#   java           apps/api unit and integration tests, JaCoCo, SpotBugs, OWASP,
#                  and the PostgreSQL migration and policy checks in db/
#   docs           developer reference (scripts/docs.sh)
#   image          Docker build and smoke test (scripts/verify.sh, checks not repeated)
#
# Every step runs even if an earlier one failed, so one run shows everything that
# would be red; --fail-fast stops at the first failure. A summary is written to
# build/build-and-test-summary.txt and the exit code is non-zero if any step failed.
# A missing optional tool (shellcheck, actionlint, doxygen, docker) is reported as
# SKIPPED, and --strict turns every skip into a failure, as in CI.
#
# Usage: bash scripts/build-and-test.sh [--skip-docker] [--skip-frontend] [--skip-java]
#                                       [--skip-docs] [--fail-fast] [--strict]
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_DIR="build"
SUMMARY="$BUILD_DIR/build-and-test-summary.txt"
mkdir -p "$BUILD_DIR"

SKIP_DOCKER=0
SKIP_FRONTEND=0
SKIP_JAVA=0
SKIP_DOCS=0
FAIL_FAST=0
STRICT=0
for arg in "$@"; do
  case "$arg" in
    --skip-docker) SKIP_DOCKER=1 ;;
    --skip-frontend) SKIP_FRONTEND=1 ;;
    --skip-java) SKIP_JAVA=1 ;;
    --skip-docs) SKIP_DOCS=1 ;;
    --fail-fast) FAIL_FAST=1 ;;
    --strict) STRICT=1 ;;
    -h|--help)
      sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

results=()
failed=0

# record STATUS NAME SECONDS: remember one line of the summary.
record() {
  results+=("$(printf '%-8s %-34s %ss' "$1" "$2" "$3")")
}

# run_step NAME COMMAND [ARG...]: run one gate, time it and record the outcome.
run_step() {
  local name="$1"
  shift
  local started=$SECONDS
  printf '\n==> %s\n' "$name"
  if "$@"; then
    record PASS "$name" $((SECONDS - started))
  else
    record FAIL "$name" $((SECONDS - started))
    failed=1
    if [[ "$FAIL_FAST" == "1" ]]; then
      finish
    fi
  fi
}

# skip_step NAME REASON: a gate that cannot run here. CI would run it, so --strict fails.
skip_step() {
  printf '\n==> %s: SKIPPED (%s)\n' "$1" "$2" >&2
  if [[ "$STRICT" == "1" ]]; then
    record FAIL "$1 (skipped: $2)" 0
    failed=1
  else
    record SKIPPED "$1 ($2)" 0
  fi
}

# finish: print and save the summary, then exit with the overall verdict.
finish() {
  {
    printf '\n==== build-and-test summary ====\n'
    printf '%s\n' "${results[@]}"
    if [[ "$failed" == "0" ]]; then
      printf 'RESULT: all gates passed\n'
    else
      printf 'RESULT: FAILED, do not push\n'
    fi
  } | tee "$SUMMARY"
  exit "$failed"
}

# --- policy -----------------------------------------------------------------
shell_files=(scripts/*.sh infra/bootstrap/start.sh apps/api/check.sh db/check.sh)

check_shell() {
  local file
  for file in "${shell_files[@]}"; do bash -n "$file"; done
  shellcheck "${shell_files[@]}"
}

check_workflows() {
  actionlint
}

# check-constraints diffs against origin/main; CI passes --require-base, so do the same
# whenever the base can be resolved and warn when it cannot.
check_policy() {
  local base_flag=()
  if git rev-parse --verify --quiet origin/main >/dev/null; then
    base_flag=(--require-base)
  else
    printf 'origin/main not found: run "git fetch origin main" for the full constraint check\n' >&2
  fi
  python3 -I scripts/project-name.py displayName >/dev/null
  python3 -I scripts/project-name.py repositorySlug >/dev/null
  python3 -I scripts/check-pack.py
  python3 -I scripts/check-docs.py
  python3 -I scripts/check-secrets.py
  python3 -I scripts/check-names.py
  python3 -I scripts/check-wiring.py
  python3 -I scripts/check-constraints.py "${base_flag[@]}"
  python3 -I scripts/check-migrations.py --require-baseline
}

if command -v shellcheck >/dev/null; then
  run_step "policy: shell syntax and shellcheck" check_shell
else
  skip_step "policy: shell syntax and shellcheck" "shellcheck not installed"
fi
if command -v actionlint >/dev/null; then
  run_step "policy: workflow lint (actionlint)" check_workflows
else
  skip_step "policy: workflow lint (actionlint)" "actionlint not installed"
fi
run_step "policy: pack, docs, secrets, names, wiring" check_policy

# --- script-tests -----------------------------------------------------------
run_step "script tests (unittest)" python3 -I -m unittest discover -s scripts/tests -q
run_step "script coverage gate" bash scripts/python-coverage.sh

# --- frontend ---------------------------------------------------------------
if [[ "$SKIP_FRONTEND" == "0" ]]; then
  run_step "frontend standards gate" bash scripts/frontend-check.sh
  run_step "staff-web build, budget, coverage" bash apps/staff-web/check.sh
  run_step "patient-mobile check" bash apps/patient-mobile/check.sh
fi

# --- java and database ------------------------------------------------------
if [[ "$SKIP_JAVA" == "0" ]]; then
  run_step "api: verify (tests, JaCoCo, SpotBugs, OWASP)" bash apps/api/check.sh
  if find db/migrations -type f -name '*.sql' -print | awk 'END { exit NR == 0 }'; then
    run_step "db: migrations and policies" bash db/check.sh
  fi
fi

# --- docs -------------------------------------------------------------------
if [[ "$SKIP_DOCS" == "0" ]]; then
  if command -v doxygen >/dev/null; then
    run_step "developer reference (docs.sh)" bash scripts/docs.sh
  else
    skip_step "developer reference (docs.sh)" "doxygen not installed"
  fi
fi

# --- image ------------------------------------------------------------------
if [[ "$SKIP_DOCKER" == "0" ]]; then
  if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
    # The gates above already ran; do not repeat the whole check suite inside verify.sh.
    run_step "docker image build and smoke test" env SMARTCLINIC_SKIP_CHECKS=1 bash scripts/verify.sh
  else
    skip_step "docker image build and smoke test" "docker not available"
  fi
fi

finish
