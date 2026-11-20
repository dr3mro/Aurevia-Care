#!/usr/bin/env bash
# Worker gate: the quick gate plus the PostgreSQL integration suites and the
# database policy checks. Workers can run these because the agent image carries
# PostgreSQL (start-postgres). OWASP, SpotBugs and JaCoCo stay with the
# integrator, which alone holds the vulnerability-feed key.
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/quick-check.sh

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
mvn_status=0
mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test || mvn_status=$?
python3 -I scripts/worker_gate.py "$mvn_status"
bash db/check.sh

printf 'worker check: quick gate, integration tests and database policy passed\n'
