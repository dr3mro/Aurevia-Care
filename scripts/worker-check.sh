#!/usr/bin/env bash
# Worker gate: the quick gate plus the PostgreSQL integration suites, the
# database policy checks, the frontend standards gate and SpotBugs.
# Workers can run these because the agent image carries PostgreSQL (start-postgres)
# and Node/npm toolchains. SpotBugs runs here; OWASP and JaCoCo stay with the
# integrator, which alone holds the vulnerability-feed key.
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/quick-check.sh
bash scripts/frontend-check.sh

# SpotBugs on its own (no tests, no OWASP): it needs no key and takes about a minute, and
# it catches findings such as a constructor that throws before Delivery does.
mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check

export SMARTCLINIC_PG_ADMIN="${SMARTCLINIC_PG_ADMIN:-postgres}"
if ! pg_isready -q -h 127.0.0.1 -p 5432; then
  if command -v start-postgres >/dev/null; then
    start-postgres
  else
    # An agent image built before PostgreSQL was added: say so loudly, skip the
    # database part. The integrator gate still runs it before anything merges.
    # The integrator's repair gate sets REQUIRE_POSTGRES and may not skip it.
    if [[ -n "${REQUIRE_POSTGRES:-}" ]]; then
      printf 'worker check: PostgreSQL is required here and is not available\n' >&2
      exit 1
    fi
    printf 'worker check: WARNING PostgreSQL is not available in this image; integration tests and db/check.sh were NOT run\n' >&2
    exit 0
  fi
fi

# Only the suites that need PostgreSQL; the unit tests already ran above. Maven
# failures are judged by scripts/worker_gate.py, which ignores integration suites
# that were already red on main and unrelated to this change (repair tasks are strict).
#
# Surefire 3.2.5 has no command-line property for reportsDirectory, so isolate
# this invocation instead: drop the shared report files, let Maven write a fresh
# set, copy that set into a private directory and give that directory to the
# report reader. Otherwise failing XML left by an earlier run can decide a
# later verdict, including on a compile or setup failure that wrote no reports.
reports_dir="$(mktemp -d)"
# The path comes from mktemp, so this cleanup cannot follow a user-supplied path.
trap 'rm -rf -- "$reports_dir"' EXIT
shared_reports="$PWD/apps/api/target/surefire-reports"
if [[ -d "$shared_reports" ]]; then
  find "$shared_reports" -maxdepth 1 -type f -name 'TEST-*.xml' -delete
fi
mvn_status=0
mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test || mvn_status=$?
if [[ -d "$shared_reports" ]] && compgen -G "$shared_reports/TEST-*.xml" > /dev/null; then
  cp -- "$shared_reports"/TEST-*.xml "$reports_dir/"
fi
# The pinned base is the SHA recorded before this worker made any commits; the
# coverage and quality gates use the same value. A manual invocation without that
# file falls back to the base branch. Passing the base and the frozen baseline
# lets worker_gate.py block a new undocumented route even when its parity suite
# is not name-related to the changed file.
base_ref="$(cat .agent/base-sha 2>/dev/null || printf 'origin/%s' "${BASE_BRANCH:-main}")"
if [[ -z "$base_ref" ]]; then
  printf 'worker check: the pinned base ref is empty; refusing to skip the route-parity gate\n' >&2
  exit 1
fi
python3 -I scripts/worker_gate.py "$mvn_status" "$reports_dir" "$base_ref" \
  "contracts/openapi/missing-routes.txt"
bash db/check.sh

printf 'worker check: quick gate, frontend standards, integration tests and database policy passed\n'
