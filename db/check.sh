#!/usr/bin/env bash
# Canonical PostgreSQL check: apply Flyway migrations and assert ARC-02/OPS-02 policy.
# Uses an ephemeral synthetic database; never reads .env or real data.
set -euo pipefail
cd "$(dirname "$0")/.."

host=${AUREVIA_PG_HOST:-127.0.0.1}
port=${AUREVIA_PG_PORT:-5432}
admin=${AUREVIA_PG_ADMIN:-$(whoami)}
db="aurevia_check_$$"
base=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U "$admin" -d postgres -Atq)
mig=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U aurevia_migrate -d "$db" -Atq)
app=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U aurevia_app -d "$db" -Atq)
org_a=11111111-1111-1111-1111-111111111111
org_b=22222222-2222-2222-2222-222222222222

cleanup() { "${base[@]}" -c "DROP DATABASE IF EXISTS $db" >/dev/null 2>&1 || true; }
trap cleanup EXIT

"${base[@]}" -c "DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN
    EXECUTE 'CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN
    EXECUTE 'CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE';
  END IF;
END \$\$;" >/dev/null
"${base[@]}" -c "DROP DATABASE IF EXISTS $db" >/dev/null
"${base[@]}" -c "CREATE DATABASE $db OWNER aurevia_migrate" >/dev/null

# Apply in Flyway version order, not bash's lexical glob order: with two-digit
# versions, V10 must run after V9 rather than before V1. sort -V gives numeric
# version ordering. (Previously this loop used a bare glob and silently applied
# migrations out of order once versions passed V9.)
while IFS= read -r migration; do
  "${mig[@]}" -f "$migration" >/dev/null
done < <(printf '%s\n' db/migrations/V*.sql | sort -V)

# 1. Every tenant table has ENABLE + FORCE RLS.
"${mig[@]}" <<'SQL' >/dev/null
DO $$
DECLARE t text; n integer;
BEGIN
  FOREACH t IN ARRAY ARRAY['organization','user_identity','facility',
                           'membership','role_assignment','audit_event',
                           'config_entry','config_approval']
  LOOP
    SELECT count(*) INTO n FROM pg_class
     WHERE oid = ('public.' || t)::regclass AND relrowsecurity AND relforcerowsecurity;
    IF n <> 1 THEN RAISE EXCEPTION 'missing FORCE RLS on %', t; END IF;
  END LOOP;
END $$;
SQL

# 1a. Every table that carries an organization_id must ENABLE + FORCE RLS.
# This is the general form of check 1: it derives the table set from the catalog
# so a new tenant table added by a future migration cannot be forgotten by
# updating a hard-coded list (ARC-02). Fails with the offending table names.
no_force_rls=$("${mig[@]}" <<'SQL'
SELECT c.relname
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped
WHERE c.relkind = 'r'
  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
ORDER BY 1;
SQL
)
[[ -z "$no_force_rls" ]] || { printf 'fail: tables with organization_id lacking FORCE RLS: %s\n' "$no_force_rls" >&2; exit 1; }

# 1b. A tenant table with RLS enabled but no policy denies every row and is
# unusable, so require at least one policy on every such table (ARC-02).
# Fails with the offending table names.
no_rls_policy=$("${mig[@]}" <<'SQL'
SELECT c.relname
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped
LEFT JOIN pg_policies p ON p.schemaname = 'public' AND p.tablename = c.relname
WHERE c.relkind = 'r'
  AND c.relrowsecurity
  AND p.policyname IS NULL
GROUP BY c.relname
ORDER BY 1;
SQL
)
[[ -z "$no_rls_policy" ]] || { printf 'fail: tables with organization_id and RLS but no policy: %s\n' "$no_rls_policy" >&2; exit 1; }

# 2. Runtime role cannot bypass RLS and owns no tenant table.
"${mig[@]}" <<'SQL' >/dev/null
DO $$
BEGIN
  IF (SELECT rolbypassrls FROM pg_roles WHERE rolname = 'aurevia_app') THEN
    RAISE EXCEPTION 'aurevia_app has BYPASSRLS';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
     WHERE r.rolname = 'aurevia_app'
       AND c.relname IN ('organization','user_identity','facility',
                         'membership','role_assignment','audit_event',
                         'config_entry','config_approval')) THEN
    RAISE EXCEPTION 'aurevia_app owns a tenant table';
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
# This app-role probe asserts the V2 privilege boundary only: PostgreSQL denies
# the UPDATE before the reject-mutation trigger can fire, so it is not trigger
# coverage. The owner-role loop in scenario 7 exercises the trigger itself.
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
  printf 'fail: app role retained audit UPDATE privilege\n' >&2
  exit 1
fi

bad=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT count(*) FROM public.audit_verify_chain(:'a') WHERE NOT ok;
SQL
)
[[ "$bad" == "0" ]] || { printf 'fail: audit chain has %s bad rows\n' "$bad" >&2; exit 1; }

# 6a. The derived audit actor is bound to the audit row's tenant, not merely to
# the subject. Seed a subject that exists only in tenant B, then let the owner
# role disable RLS on the lookup table inside a rolled-back probe. Removing RLS
# as a crutch means only the function's tenant predicate can keep B's user off
# an A audit row. The DO block raises when a foreign actor is stamped; a raised
# error is the PASS side of this probe.
"${mig[@]}" -v b="$org_b" <<'SQL' >/dev/null
SET app.current_org = :'b';
INSERT INTO public.user_identity (organization_id, subject, display_name)
  VALUES (:'b', 'foreign-only-actor', 'Foreign Only Actor');
SQL

if ! "${mig[@]}" -v a="$org_a" <<'SQL' >/dev/null 2>&1
BEGIN;
SET LOCAL app.current_org = :'a';
SET LOCAL app.actor = 'foreign-only-actor';
ALTER TABLE public.user_identity DISABLE ROW LEVEL SECURITY;
INSERT INTO public.audit_event (organization_id, action, outcome, correlation_id)
  VALUES (:'a', 'ACTOR_TENANT_PROBE', 'SUCCESS', 'check-actor-tenant');
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.audit_event e
     WHERE e.correlation_id = 'check-actor-tenant'
       AND e.actor_id = (SELECT id FROM public.user_identity
                          WHERE subject = 'foreign-only-actor')
  ) THEN
    RAISE EXCEPTION 'audit actor was derived from a foreign tenant';
  END IF;
END $$;
ROLLBACK;
SQL
then
  printf 'fail: audit actor lookup was not tenant-bound\n' >&2
  exit 1
fi

# An explicit actor_id must never be replaced by the lookup, even when the
# session names a foreign subject.
rows=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SET app.actor = 'foreign-only-actor';
INSERT INTO public.audit_event (organization_id, actor_id, action, outcome, correlation_id)
  SELECT :'a', id, 'ACTOR_EXPLICIT_PROBE', 'SUCCESS', 'check-actor-explicit'
    FROM public.user_identity WHERE organization_id = :'a' AND subject = 'admin-a';
SELECT e.actor_id = u.id
  FROM public.audit_event e
  JOIN public.user_identity u ON u.organization_id = e.organization_id AND u.subject = 'admin-a'
 WHERE e.correlation_id = 'check-actor-explicit';
SQL
)
[[ "$rows" == "t" ]] || { printf 'fail: explicit audit actor was overwritten\n' >&2; exit 1; }

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
INSERT INTO public.config_entry (organization_id, key, version, value)
  VALUES (:'a', 'check.policy', 1, 'seed');
INSERT INTO public.config_approval (organization_id, entry_id, note)
  SELECT :'a', id, 'seed approval' FROM public.config_entry WHERE key = 'check.policy';
-- Seed an audit row so audit_event's reject-mutation trigger sees a row under
-- FORCE RLS when the owner-role loop below runs.
INSERT INTO public.audit_event (organization_id, actor_id, action, outcome, correlation_id)
  VALUES (:'a', NULL, 'IMMUTABILITY_PROBE', 'SUCCESS', 'check-immutability');
SQL

for t in user_identity membership role_assignment config_entry config_approval audit_event; do
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
  FOREACH t IN ARRAY ARRAY['user_identity','membership','role_assignment',
                           'config_entry','config_approval','audit_event']
  LOOP
    IF has_table_privilege('aurevia_app','public.'||t,'UPDATE')
       OR has_table_privilege('aurevia_app','public.'||t,'DELETE')
       OR has_table_privilege('aurevia_app','public.'||t,'TRUNCATE') THEN
      RAISE EXCEPTION 'runtime role retains write privilege on %', t;
    END IF;
    IF NOT has_table_privilege('aurevia_app','public.'||t,'INSERT') THEN
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

# 10. Configuration versions are actor-derived, monotonic and folded on approval.
if "${mig[@]}" -v a="$org_a" <<'SQL' >/dev/null 2>&1
SET app.current_org = :'a';
INSERT INTO public.config_entry (organization_id, key, version, value)
  VALUES (:'a', 'missing.actor', 1, 'x');
SQL
then
  printf 'fail: config entry accepted without app.actor\n' >&2
  exit 1
fi

"${mig[@]}" -v a="$org_a" <<'SQL' >/dev/null
SET app.current_org = :'a';
SET app.actor = 'admin-a';
INSERT INTO public.config_entry (organization_id, key, version, value, effective_from)
  VALUES (:'a', 'check.fold', public.next_config_version('check.fold'), 'old', now() - interval '2 days');
INSERT INTO public.config_entry (organization_id, key, version, value, effective_from)
  VALUES (:'a', 'check.fold', public.next_config_version('check.fold'), 'new', now() - interval '1 day');
INSERT INTO public.config_entry (organization_id, key, version, value, effective_from)
  VALUES (:'a', 'check.fold', public.next_config_version('check.fold'), 'future', now() + interval '1 day');
SQL

versions=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT string_agg(version::text, ',' ORDER BY version) FROM public.config_entry WHERE key = 'check.fold';
SQL
)
[[ "$versions" == "1,2,3" ]] || { printf 'fail: config versions %s\n' "$versions" >&2; exit 1; }
fold=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT count(*) FROM public.effective_config('check.fold');
SQL
)
[[ "$fold" == "0" ]] || { printf 'fail: unapproved config leaked into fold\n' >&2; exit 1; }

"${mig[@]}" -v a="$org_a" <<'SQL' >/dev/null
SET app.current_org = :'a';
SET app.actor = 'admin-a';
INSERT INTO public.config_approval (organization_id, entry_id, note)
  SELECT :'a', id, 'approve old' FROM public.config_entry WHERE key = 'check.fold' AND version = 1;
INSERT INTO public.config_approval (organization_id, entry_id, note)
  SELECT :'a', id, 'approve new' FROM public.config_entry WHERE key = 'check.fold' AND version = 2;
SQL

if "${mig[@]}" -v a="$org_a" <<'SQL' >/dev/null 2>&1
SET app.current_org = :'a';
SET app.actor = 'admin-a';
INSERT INTO public.config_approval (organization_id, entry_id, note)
  SELECT :'a', id, 'duplicate' FROM public.config_entry WHERE key = 'check.fold' AND version = 2;
SQL
then
  printf 'fail: duplicate config approval accepted\n' >&2
  exit 1
fi

fold=$("${app[@]}" -v a="$org_a" <<'SQL'
SET app.current_org = :'a';
SELECT value FROM public.effective_config('check.fold');
SQL
)
[[ "$fold" == "new" ]] || { printf 'fail: effective config was %s\n' "$fold" >&2; exit 1; }
fold=$("${app[@]}" -v b="$org_b" <<'SQL'
SET app.current_org = :'b';
SELECT count(*) FROM public.effective_config('check.fold');
SQL
)
[[ "$fold" == "0" ]] || { printf 'fail: foreign tenant saw config\n' >&2; exit 1; }

printf 'db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified\n'
