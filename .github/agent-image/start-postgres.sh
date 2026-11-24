#!/usr/bin/env bash
# Start a throwaway PostgreSQL 17 on 127.0.0.1:5432 for the swarm worker's
# integration tests. Trust authentication and no fsync: the data lives and dies
# with the job. Safe to run twice.
set -euo pipefail
bin=/usr/lib/postgresql/17/bin
data=${PGDATA_DIR:-/tmp/orevia-pgdata}
as_postgres() {
  if [[ "$(id -u)" -eq 0 ]]; then runuser -u postgres -- "$@"; else "$@"; fi
}
if "$bin/pg_isready" -q -h 127.0.0.1 -p 5432; then
  exit 0
fi
if [[ "$(id -u)" -eq 0 ]]; then
  install -d -o postgres -g postgres "$data"
else
  mkdir -p "$data"
fi
if [[ ! -s "$data/PG_VERSION" ]]; then
  as_postgres "$bin/initdb" -D "$data" -A trust -U postgres >/dev/null
fi
as_postgres "$bin/pg_ctl" -D "$data" -l /tmp/orevia-pg.log -w start \
  -o "-c listen_addresses=127.0.0.1 -c port=5432 -c fsync=off -c synchronous_commit=off -c full_page_writes=off"
"$bin/pg_isready" -h 127.0.0.1 -p 5432
