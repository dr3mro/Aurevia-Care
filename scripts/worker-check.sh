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
    printf 'worker check: WARNING PostgreSQL is not available in this image; integration tests and db/check.sh were NOT run\n' >&2
    exit 0
  fi
fi

# Only the suites that need PostgreSQL; the unit tests already ran above.
mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test
bash db/check.sh

printf 'worker check: quick gate, integration tests and database policy passed\n'
