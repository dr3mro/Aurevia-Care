#!/usr/bin/env bash
# Build and test the whole project locally with the gates that can run here.
# A green run is strong evidence, not a guarantee: Docker image smoke is skipped
# when Docker is absent and the local JDK may differ from CI. Read the summary
# for skipped steps before treating a local green as release evidence.
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
# Each step's full output is kept in build/logs/<step>.log, and build/build-and-test-failures.txt
# lists every failed step in a fixed, machine-readable layout (see its header) so an LLM or a
# script can read the failures and propose fixes without scrolling the whole run.
# A missing optional tool (shellcheck, actionlint, doxygen, docker) is reported as
# SKIPPED, and --strict turns every skip into a failure, as in CI.
#
# Usage: bash scripts/build-and-test.sh [--skip-docker] [--skip-frontend] [--skip-java]
#                                       [--skip-docs] [--fail-fast] [--strict]
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_DIR="build"
SUMMARY="$BUILD_DIR/build-and-test-summary.txt"
FAILURES="$BUILD_DIR/build-and-test-failures.txt"
LOG_DIR="$BUILD_DIR/logs"
# Preserve the previous run's logs: the failure file names them as evidence and
# an agent fixing that file must be able to read the tail it quotes.
if [[ -d "$LOG_DIR" ]]; then
  mv "$LOG_DIR" "$BUILD_DIR/logs-$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "$BUILD_DIR" "$LOG_DIR"

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
# One entry per failed step, kept in parallel arrays: name, command line, exit code, log file.
fail_names=()
fail_cmds=()
fail_codes=()
fail_logs=()

# record STATUS NAME SECONDS: remember one line of the summary.
record() {
  results+=("$(printf '%-8s %-34s %ss' "$1" "$2" "$3")")
}

# slugify TEXT: a file-name-safe form of a step name.
slugify() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9\n' '-' | tr -s '-' | sed 's/^-//; s/-$//'
}

# run_step NAME COMMAND [ARG...]: run one gate, show its output live, keep it in a log,
# time it and record the outcome (including the exit code and log of a failure).
run_step() {
  local name="$1"
  shift
  local started=$SECONDS
  local log
  log="$LOG_DIR/$(slugify "$name").log"
  local code=0
  printf '\n==> %s\n' "$name"
  set +e
  # Keep policy calls visible to ShellCheck and stop a failed check before a
  # later success can hide it. The parent still captures the producer status.
  (
    set -e
    case "$1" in
      check_shell) check_shell ;;
      check_policy) check_policy ;;
      *) "$@" ;;
    esac
  ) 2>&1 | tee "$log"
  code=${PIPESTATUS[0]}
  set -e
  if [[ "$code" == "0" ]]; then
    record PASS "$name" $((SECONDS - started))
  else
    record FAIL "$name" $((SECONDS - started))
    failed=1
    fail_names+=("$name")
    fail_cmds+=("$*")
    fail_codes+=("$code")
    fail_logs+=("$log")
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
    fail_names+=("$1")
    fail_cmds+=("(not run)")
    fail_codes+=("skipped")
    fail_logs+=("")
  else
    record SKIPPED "$1 ($2)" 0
  fi
}

# strip_ansi: remove terminal colour and cursor codes from a stream.
strip_ansi() {
  LC_ALL=C sed $'s/\033\\[[0-9;?]*[A-Za-z]//g'
}

# findings LOG: print the lines of a step log that name a concrete problem, one per line,
# as "<kind> | <location> | <detail>". Recognises ESLint, tsc, Vitest/Jest, Maven/JUnit/JaCoCo,
# Python unittest, shellcheck and the repository policy scripts; anything else is covered
# by the log excerpt that follows the findings.
findings() {
  strip_ansi <"$1" | awk '
    /^[^ ].*\.(ts|tsx|js|jsx|mjs|cjs)$/ { file = $0; next }
    $1 ~ /^[0-9]+:[0-9]+$/ && ($2 == "error" || $2 == "warning") {
      msg = ""
      for (i = 3; i < NF; i++) msg = msg " " $i
      printf "eslint | %s:%s | %s |%s\n", file, $1, $NF, msg; next
    }
    /error TS[0-9]+/ { print "tsc | " $0; next }
    /^ *(FAIL|✗|×|✖) / { print "test-failed | " $0; next }
    /(AssertionError|AssertionFailedError|expected .* to (be|equal|have|contain|match)|Unable to find|TestingLibraryElementError)/ { print "assertion | " $0; next }
    /Tests run:.*(Failures: [1-9]|Errors: [1-9])/ { print "junit | " $0; next }
    /<<< (FAILURE|ERROR)!/ { print "junit | " $0; next }
    /Rule violated|coverage checks have not been met|COVERAGE|below the threshold|below threshold|uncovered|not covered/ { print "coverage | " $0; next }
    /^(FAIL|ERROR): / { print "python-test | " $0; next }
    /^In .* line [0-9]+:/ { print "shellcheck | " $0; next }
    /\[ERROR\]/ { print "maven | " $0; next }
    /^npm ERR!|^npm error/ { print "npm | " $0; next }
    /"violations": \[\{/ { print "policy | " $0; next }
  ' | awk '!seen[$0]++' | head -400
}

# write_failures: write build/build-and-test-failures.txt. The layout is fixed so a model can
# parse it: a header, then one block per failed step delimited by "=== FAILURE n/N ===" and
# "=== END FAILURE n ===", each holding key: value lines, a FINDINGS section and a LOG TAIL.
write_failures() {
  local total=${#fail_names[@]} i
  {
    printf 'BUILD AND TEST FAILURES\n'
    printf 'generated: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'commit: %s\n' "$(git rev-parse --short HEAD 2>/dev/null || printf unknown)"
    printf 'branch: %s\n' "$(git rev-parse --abbrev-ref HEAD 2>/dev/null || printf unknown)"
    printf 'failed_steps: %s\n' "$total"
    if [[ "$total" == "0" ]]; then
      printf 'status: PASS (nothing to fix)\n'
      return 0
    fi
    printf 'status: FAIL\n\n'
    printf 'HOW TO USE THIS FILE\n'
    printf -- '- Fix the cause in the code or tests. Never disable a rule, skip or weaken a test, lower a\n'
    printf -- '  threshold, or add a suppression comment (see AGENTS.md).\n'
    printf -- '- Each block names the failing step, the exact command to re-run it (from the repo root),\n'
    printf -- '  FINDINGS (kind | location | detail) and the last lines of the log. The full output is in\n'
    printf -- '  the log file named in each block.\n'
    printf -- '- Re-run only the failing step, then bash scripts/build-and-test.sh before pushing.\n\n'
    for ((i = 0; i < total; i++)); do
      printf '=== FAILURE %s/%s ===\n' "$((i + 1))" "$total"
      printf 'step: %s\n' "${fail_names[$i]}"
      printf 'exit_code: %s\n' "${fail_codes[$i]}"
      printf 'rerun: %s\n' "${fail_cmds[$i]}"
      if [[ -n "${fail_logs[$i]}" ]]; then
        printf 'log: %s\n' "${fail_logs[$i]}"
        printf '%s\n' '--- FINDINGS (kind | location | detail) ---'
        findings "${fail_logs[$i]}"
        printf '%s\n' '--- LOG TAIL (last 60 lines) ---'
        strip_ansi <"${fail_logs[$i]}" | tail -n 60
      else
        printf '%s\n' '--- FINDINGS ---'
        printf 'step was skipped because a required tool is missing (--strict)\n'
      fi
      printf '=== END FAILURE %s ===\n\n' "$((i + 1))"
    done
  } >"$FAILURES"
}

# finish: print and save the summary and the failures file, then exit with the verdict.
finish() {
  write_failures
  {
    printf '\n==== build-and-test summary ====\n'
    printf '%s\n' "${results[@]}"
    if [[ "$failed" == "0" ]]; then
      printf 'RESULT: all gates passed\n'
    else
      printf 'RESULT: FAILED, do not push\n'
      printf 'Failures for an LLM to fix: %s\n' "$FAILURES"
    fi
  } | tee "$SUMMARY"
  exit "$failed"
}

# --- policy -----------------------------------------------------------------
shell_files=(scripts/*.sh infra/bootstrap/start.sh apps/api/check.sh db/check.sh)

# shellcheck disable=SC2329,SC2317
check_shell() {
  local file
  for file in "${shell_files[@]}"; do bash -n "$file"; done
  shellcheck "${shell_files[@]}"
}

# check-constraints diffs against origin/main; CI passes --require-base, so do the same
# whenever the base can be resolved and warn when it cannot.
# shellcheck disable=SC2329,SC2317
check_policy() {
  if git rev-parse --verify --quiet origin/main >/dev/null; then
    set -- --require-base
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
  python3 -I scripts/check-constraints.py "$@"
  python3 -I scripts/check-migrations.py --require-baseline
}

if command -v shellcheck >/dev/null; then
  run_step "policy: shell syntax and shellcheck" check_shell
else
  skip_step "policy: shell syntax and shellcheck" "shellcheck not installed"
fi
if command -v actionlint >/dev/null; then
  # actionlint 1.7.12 predates GitHub's concurrency `queue` key; the queue
  # contract itself is asserted by scripts/tests/test_workflow_concurrency.py.
  run_step "policy: workflow lint (actionlint)" command actionlint \
    -ignore 'unexpected key "queue" for "concurrency" section'
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
    run_step "docker image build and smoke test" env OREVIA_SKIP_CHECKS=1 bash scripts/verify.sh
  else
    skip_step "docker image build and smoke test" "docker not available"
  fi
fi

finish
