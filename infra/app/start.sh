#!/usr/bin/env bash
# Starts PostgreSQL (in memory), applies the migrations, starts the API and nginx.
# With AUREVIA_PERSIST=1 the database is dumped to /dumps/persist.sql on a clean stop and
# restored from it on the next start. The previous dump is kept as persist.sql.prev.
set -euo pipefail

PGDATA=${PGDATA_DIR:-/pgdata}
DB=aurevia
DUMPS=/dumps
MIGRATIONS=/opt/aurevia/migrations
psql_admin() { psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres "$@"; }

pg_pid=; api_pid=; web_pid=

# shellcheck disable=SC2317,SC2329
shutdown() {
    trap - TERM INT
    if [[ "${AUREVIA_PERSIST:-0}" == 1 && -n "$pg_pid" ]] && pg_isready -q -h 127.0.0.1 -U postgres; then
        echo "aurevia: saving the database to $DUMPS/persist.sql"
        [[ -f "$DUMPS/persist.sql" ]] && cp "$DUMPS/persist.sql" "$DUMPS/persist.sql.prev"
        if pg_dump -h 127.0.0.1 -U postgres "$DB" > "$DUMPS/persist.sql.tmp"; then
            mv "$DUMPS/persist.sql.tmp" "$DUMPS/persist.sql"
        else
            echo "aurevia: the dump failed; the previous dump is kept" >&2
            rm -f "$DUMPS/persist.sql.tmp"
        fi
    fi
    for pid in "$web_pid" "$api_pid"; do
        if [[ -n "$pid" ]]; then
            kill -TERM "$pid" 2>/dev/null || true
        fi
    done
    if [[ -n "$pg_pid" ]]; then
        kill -INT "$pg_pid" 2>/dev/null || true
    fi
    wait 2>/dev/null || true
    exit 0
}
trap shutdown TERM INT

# Empty data directory: in memory when the tool mounts a tmpfs on /pgdata.
rm -rf "${PGDATA:?}"/* 2>/dev/null || true
initdb -D "$PGDATA" --username=postgres --auth=trust >/dev/null
postgres -D "$PGDATA" -h 127.0.0.1 -k /tmp &
pg_pid=$!
for ((i = 0; i < 60; i++)); do
    pg_isready -q -h 127.0.0.1 -U postgres && break
    kill -0 "$pg_pid"
    sleep 1
done

psql_admin -d postgres -qAt <<'SQL' >/dev/null
CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE DATABASE aurevia OWNER aurevia_migrate;
SQL

applied=/tmp/aurevia-applied
: > "$applied"
if [[ -s "$DUMPS/persist.sql" ]]; then
    echo "aurevia: restoring the saved database"
    psql_admin -d "$DB" -q -f "$DUMPS/persist.sql" >/dev/null
    psql_admin -d "$DB" -Atqc "SELECT filename FROM aurevia_migrations" > "$applied" 2>/dev/null || true
fi

psql_admin -d "$DB" -qc "CREATE TABLE IF NOT EXISTS aurevia_migrations (filename text PRIMARY KEY)" >/dev/null
while IFS= read -r file; do
    name=$(basename "$file")
    grep -qxF "$name" "$applied" && continue
    echo "aurevia: applying $name"
    psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U aurevia_migrate -d "$DB" -q -f "$file" >/dev/null
    psql_admin -d "$DB" -qc "INSERT INTO aurevia_migrations VALUES ('$name')" >/dev/null
done < <(printf '%s\n' "$MIGRATIONS"/V*.sql | sort -V)

effective_profile="${AUREVIA_PROFILE:-synthetic-auth}"
# The image bakes in synthetic-auth, so warn on the effective profile, not only on an unset variable.
if [[ "$effective_profile" == "synthetic-auth" ]]; then
  echo "aurevia: synthetic-auth permits every request and uses header identity; this disposable image is test-only, never expose it" >&2
fi
# shellcheck disable=SC2086
AUREVIA_SERVER_PORT=8080 \
AUREVIA_DB_URL="jdbc:postgresql://127.0.0.1:5432/$DB" \
AUREVIA_DB_USER=aurevia_app \
SPRING_PROFILES_ACTIVE="$effective_profile" \
    java ${JAVA_OPTS:-} -jar /opt/aurevia/api.jar &
api_pid=$!

nginx -c /opt/aurevia/nginx.conf -g 'daemon off;' &
web_pid=$!

echo "aurevia: web http://localhost:3000, API http://localhost:8080, persistence ${AUREVIA_PERSIST:-0}"
wait -n "$pg_pid" "$api_pid" "$web_pid" || true
echo "aurevia: a component exited; stopping" >&2
AUREVIA_PERSIST=0 shutdown
