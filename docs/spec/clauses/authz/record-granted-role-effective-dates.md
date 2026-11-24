# IAM-02 clause 2 — record who granted each role and its effective dates

## Clause

Record who granted each role and its effective dates.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.authz.clauses.RecordGrantedRoleEffectiveDatesTest`
(path: `apps/api/src/test/java/com/aurevia/authz/clauses/RecordGrantedRoleEffectiveDatesTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic tenants, following the fixtures and database setup of the
neighbouring clause tests and `com.aurevia.authz.history.RoleHistoryIntegrationTest`.
All five tests are active (none disabled) and use synthetic data only.

Test methods (one positive rule proof, four negative refusals):

- `grantedRoleRecordsWhoGrantedItAndItsEffectiveDates` — positive proof. A role is
  granted with an explicit `granted_by` granter and an explicit `valid_from`/`valid_to`
  window while the acting-user GUC is cleared, so the V71 trigger must derive the actor
  from the authoritative grant. Reading `/api/v1/authz/role-history/subject-a` as an
  admin returns a `GRANT` change carrying `actor` equal to the granter, a non-null
  `effectiveFrom` and a non-null `effectiveTo`. Direct SQL compares the durable
  `role_change_history.actor`, `effective_from` and `effective_to` instants.
- `crossTenantGrantHistoryIsConcealed` — a subject that exists only in another tenant
  is concealed with 404 under forced row-level security.
- `nonAdminCannotReadGrantHistory` — a `reception` caller is refused with 403 because
  it lacks the `ROLE_HISTORY_READ` action permission.
- `malformedSubjectIsRefused` — an input that violates the subject pattern is refused
  with 422 before any database access.
- `recordedGranterAndDatesCannotBeRewritten` — `UPDATE`, `DELETE` and `TRUNCATE` of the
  ledger are rejected for the migration owner (SQLSTATE `44000` from the append-only
  trigger), so a recorded granter or date cannot be altered.

Production classes and objects exercised through the normal HTTP entry point:

- Controller: `com.aurevia.authz.history.RoleHistoryController`
  (`GET /api/v1/authz/role-history/{subject}`).
- Domain and boundary: `com.aurevia.authz.history.RoleHistoryService`,
  `com.aurevia.authz.history.RoleHistoryStore`,
  `com.aurevia.authz.history.JdbcRoleHistoryStore`,
  `com.aurevia.authz.history.RoleHistory`,
  `com.aurevia.authz.ActionPolicy` and `com.aurevia.tenant.TenantContext`.

Where the clause is implemented today (source survey result):

- Schema: `db/migrations/V1__tenant_schema.sql` defines `role_assignment` with the
  `granted_by`, `valid_from` and `valid_to` columns; `db/migrations/V2__rls_roles_policies.sql`
  forces row-level security on tenant tables; `db/migrations/V4__staff_lifecycle.sql`
  makes membership and role grants append-only; `db/migrations/V71__build_02.sql`
  creates the append-only `role_change_history` ledger with forced RLS, immutability
  triggers and a `capture_role_change()` trigger function that records the actor
  (from `app.actor` or the `granted_by` foreign key) and the effective window for
  every `GRANT`, `CHANGE` and `REVOKE`.
- Code: the `com.aurevia.authz.history` package holds the read model,
  service, `Jdbc*` store and controller; `com.aurevia.authz.ActionPolicy`
  gates the read to `tenant_admin`.
- Contract: the read route `GET /api/v1/authz/role-history/{subject}` is currently
  baselined in `contracts/openapi.yaml` rather than declared in
  `contracts/openapi.yaml`; the recording behaviour itself does not depend on the
  contract file. (Recorded as a finding in the task notes; outside this task's scope.)

## Gap

None.
