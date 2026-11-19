#!/usr/bin/env bash
# Canonical PostgreSQL check: apply Flyway migrations and assert ARC-02/OPS-02 policy.
# Uses an ephemeral synthetic database; never reads .env or real data.
set -euo pipefail
cd "$(dirname "$0")/.."

host=${SMARTCLINIC_PG_HOST:-127.0.0.1}
port=${SMARTCLINIC_PG_PORT:-5432}
admin=${SMARTCLINIC_PG_ADMIN:-$(whoami)}
db="smartclinic_check_$$"
base=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U "$admin" -d postgres -Atq)
mig=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U smartclinic_migrate -d "$db" -Atq)
app=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U smartclinic_app -d "$db" -Atq)
org_a=11111111-1111-1111-1111-111111111111
org_b=22222222-2222-2222-2222-222222222222

cleanup() { "${base[@]}" -c "DROP DATABASE IF EXISTS $db" >/dev/null 2>&1 || true; }
trap cleanup EXIT

"${base[@]}" -c "DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN
    EXECUTE 'CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN
    EXECUTE 'CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE';
  END IF;
END \$\$;" >/dev/null
"${base[@]}" -c "DROP DATABASE IF EXISTS $db" >/dev/null
"${base[@]}" -c "CREATE DATABASE $db OWNER smartclinic_migrate" >/dev/null

for migration in db/migrations/V*.sql; do
  "${mig[@]}" -f "$migration" >/dev/null
done

# 1. Every tenant table has ENABLE + FORCE RLS.
"${mig[@]}" <<'SQL' >/dev/null
DO $$
DECLARE t text; n integer;
BEGIN
  FOREACH t IN ARRAY ARRAY['organization','user_identity','facility',
                           'membership','role_assignment','audit_event']
  LOOP
    SELECT count(*) INTO n FROM pg_class
     WHERE oid = ('public.' || t)::regclass AND relrowsecurity AND relforcerowsecurity;
    IF n <> 1 THEN RAISE EXCEPTION 'missing FORCE RLS on %', t; END IF;
  END LOOP;
END $$;
SQL

# 2. Runtime role cannot bypass RLS and owns no tenant table.
"${mig[@]}" <<'SQL' >/dev/null
DO $$
BEGIN
  IF (SELECT rolbypassrls FROM pg_roles WHERE rolname = 'smartclinic_app') THEN
    RAISE EXCEPTION 'smartclinic_app has BYPASSRLS';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
     WHERE r.rolname = 'smartclinic_app'
       AND c.relname IN ('organization','user_identity','facility',
                         'membership','role_assignment','audit_event')) THEN
    RAISE EXCEPTION 'smartclinic_app owns a tenant table';
  END IF;
  IF public.app_org() IS NOT NULL THEN
    RAISE EXCEPTION 'unset context must yield NULL';
  END IF;
END $$;
SQL

# 3. Missing context returns no rows for the runtime role.
rows=$("${app[@]}" -c 'SELECT count(*) FROM public.facility')
[[ "$rows" == "0" ]] || { printf 'fail: unset context exposed %s facilities\n' "$rows" >&2; exit 1; }

# 4. Two tenants share the local code MAIN; each is isolated by RLS.
"${mig[@]}" -v a="$org_a" -v b="$org_b" <<'SQL' >/dev/null
BEGIN;
SET LOCAL app.current_org = :'a';
INSERT INTO public.organization (organization_id, display_name) VALUES (:'a', 'Alpha');
INSERT INTO public.user_identity (organization_id, subject, display_name)
  VALUES (:'a', 'admin-a', 'Alpha Admin');
INSERT INTO public.facility (organization_id, code, name) VALUES (:'a', 'MAIN', 'Alpha Main');
SET LOCAL app.current_org = :'b';
INSERT INTO public.organization (organization_id, display_name) VALUES (:'b', 'Beta');
INSERT INTO public.user_identity (organization_id, subject, display_name)
  VALUES (:'b', 'admin-b', 'Beta Admin');
INSERT INTO public.facility (organization_id, code, name) VALUES (:'b', 'MAIN', 'Beta Main');
COMMIT;
SQL

rows=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT organization_id::text FROM public.facility WHERE code = 'MAIN';
SQL
)
[[ "$rows" == "$org_a" ]] || { printf 'fail: tenant A saw %s\n' "$rows" >&2; exit 1; }

rows=$("${app[@]}" -v b="$org_b" <<'SQL'
SET app.current_org = :'b';
SELECT organization_id::text FROM public.facility WHERE code = 'MAIN';
SQL
)
[[ "$rows" == "$org_b" ]] || { printf 'fail: tenant B saw %s\n' "$rows" >&2; exit 1; }

# 5. WITH CHECK blocks a cross-tenant insert.
if "${app[@]}" -v a="$org_a" -v b="$org_b" <<'SQL' >/dev/null 2>&1
SET app.current_org = :'a';
INSERT INTO public.facility (organization_id, code, name) VALUES (:'b', 'X', 'Cross tenant');
SQL
then
  printf 'fail: cross-tenant insert succeeded\n' >&2
  exit 1
fi

# 6. Audit is append-only and its hash chain verifies.
"${app[@]}" -v a="$org_a" <<'SQL' >/dev/null
SET app.current_org = :'a';
INSERT INTO public.audit_event (organization_id, actor_id, action, outcome, correlation_id)
  VALUES (:'a', NULL, 'FACILITY_CREATE', 'SUCCESS', 'check-1');
INSERT INTO public.audit_event (organization_id, actor_id, action, outcome, correlation_id)
  VALUES (:'a', NULL, 'FACILITY_READ', 'SUCCESS', 'check-2');
SQL

if "${app[@]}" -v a="$org_a" <<'SQL' >/dev/null 2>&1
SET app.current_org = :'a';
UPDATE public.audit_event SET outcome = 'DENIED' WHERE correlation_id = 'check-1';
SQL
then
  printf 'fail: audit row was mutable\n' >&2
  exit 1
fi

bad=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT count(*) FROM public.audit_verify_chain(:'a') WHERE NOT ok;
SQL
)
[[ "$bad" == "0" ]] || { printf 'fail: audit chain has %s bad rows\n' "$bad" >&2; exit 1; }

# 7. Staff history is immutable for every role, including the migration owner.
# Seed one grant of each kind so the triggers actually see rows to reject.
"${mig[@]}" -v a="$org_a" <<'SQL' >/dev/null
SET app.current_org = :'a';
SET app.actor = 'admin-a';
INSERT INTO public.membership (organization_id, user_id, status)
  SELECT :'a', id, 'active' FROM public.user_identity WHERE subject = 'admin-a';
INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by)
  SELECT :'a', m.id, 'tenant_admin', u.id
  FROM public.membership m
  JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id
  WHERE u.subject = 'admin-a';
SQL

for t in user_identity membership role_assignment; do
  if "${mig[@]}" -v a="$org_a" <<SQL >/dev/null 2>&1
SET app.current_org = :'a';
UPDATE public.$t SET organization_id = organization_id WHERE organization_id = :'a';
SQL
  then
    printf 'fail: %s allowed UPDATE\n' "$t" >&2
    exit 1
  fi
  if "${mig[@]}" -v a="$org_a" <<SQL >/dev/null 2>&1
SET app.current_org = :'a';
DELETE FROM public.$t WHERE organization_id = :'a';
SQL
  then
    printf 'fail: %s allowed DELETE\n' "$t" >&2
    exit 1
  fi
done

# 8. Runtime role may append history but never mutate it.
"${mig[@]}" <<'SQL' >/dev/null
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['user_identity','membership','role_assignment']
  LOOP
    IF has_table_privilege('smartclinic_app','public.'||t,'UPDATE')
       OR has_table_privilege('smartclinic_app','public.'||t,'DELETE')
       OR has_table_privilege('smartclinic_app','public.'||t,'TRUNCATE') THEN
      RAISE EXCEPTION 'runtime role retains write privilege on %', t;
    END IF;
    IF NOT has_table_privilege('smartclinic_app','public.'||t,'INSERT') THEN
      RAISE EXCEPTION 'runtime role cannot append history on %', t;
    END IF;
  END LOOP;
END $$;
SQL

# 9. Current authorization folds from the latest effective membership event.
"${mig[@]}" -v a="$org_a" <<'SQL' >/dev/null
BEGIN;
SET LOCAL app.current_org = :'a';
SET LOCAL app.actor = 'admin-a';
INSERT INTO public.user_identity (organization_id, subject, display_name)
  VALUES (:'a', 'lifecycle-a', 'Lifecycle A');
INSERT INTO public.membership (organization_id, user_id, status, valid_from)
  SELECT :'a', id, 'active', now() - interval '1 day'
  FROM public.user_identity WHERE subject = 'lifecycle-a';
INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by, valid_from)
  SELECT :'a', m.id, 'tenant_admin', g.id, now() - interval '1 day'
  FROM public.membership m
  JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id
  CROSS JOIN public.user_identity g
  WHERE u.subject = 'lifecycle-a' AND g.organization_id = :'a' AND g.subject = 'admin-a';
COMMIT;
SQL

active=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT public.has_active_membership('lifecycle-a')::text;
SQL
)
[[ "$active" == "true" ]] || { printf 'fail: active grant did not authorize\n' >&2; exit 1; }
roles=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT count(*) FROM public.active_roles('lifecycle-a');
SQL
)
[[ "$roles" == "1" ]] || { printf 'fail: active grant did not yield a role\n' >&2; exit 1; }

"${app[@]}" -v a="$org_a" <<'SQL' >/dev/null
SET app.current_org = :'a';
SET app.actor = 'admin-a';
INSERT INTO public.membership (organization_id, user_id, status)
  SELECT :'a', id, 'revoked' FROM public.user_identity WHERE subject = 'lifecycle-a';
SQL

active=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT public.has_active_membership('lifecycle-a')::text;
SQL
)
[[ "$active" == "false" ]] || { printf 'fail: appended revoke did not deny next check\n' >&2; exit 1; }
roles=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT count(*) FROM public.active_roles('lifecycle-a');
SQL
)
[[ "$roles" == "0" ]] || { printf 'fail: appended revoke left stale roles\n' >&2; exit 1; }

printf 'db: migrations, forced RLS, role separation, isolation, audit and append-only staff history verified\n'
