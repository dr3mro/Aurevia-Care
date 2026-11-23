# IAM-06 clause 1 — record business purpose, role, facility and user for sensitive actions

## Clause

Record business purpose, role, facility and user for sensitive actions.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.audit.clauses.RecordBusinessPurposeRoleFacilityUserSensitiveTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/audit/clauses/RecordBusinessPurposeRoleFacilityUserSensitiveTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic tenants, following the fixtures and database setup of the
neighbouring clause tests (`com.aurevia.smartclinic.it.authz.AccessIntegrationTest`,
`com.aurevia.smartclinic.authz.clauses.RecordGrantedRoleEffectiveDatesTest`).
All three tests are active (none disabled) and use synthetic data only.

Observed result: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.

Test methods (one positive rule proof, two negative refusals):

- `sensitiveGrantRecordsPurposeRoleFacilityAndUser` — positive proof. A qualified
  clinician opens a reasoned emergency grant via `POST /api/v1/access/emergency` with an
  explicit correlation id. Direct SQL reads the durable `emergency_access_grant` row and
  confirms `purpose = EMERGENCY_ACCESS`, `role_key = clinician`, `facility_code = MAIN`
  and `subject = clinician-a`. The append-only `audit_event` row for the same
  correlation and `EMERGENCY_GRANT` action carries `purpose = EMERGENCY_ACCESS`,
  `scope = clinician:MAIN`, `actor_id` equal to the acting clinician's identity and
  `outcome = SUCCESS`. Both assertions use the durable RLS-scoped values, not just the
  HTTP response.
- `nonClinicianGrantIsRefusedAndDenialRecordsPurposeRoleFacilityAndActor` — a caller
  without the qualifying `clinician` role is refused with 403, no grant row is written,
  and the committed `EMERGENCY_GRANT` denial audit row still records purpose, scope and
  the acting user (`outcome = DENIED`).
- `recordedSensitiveContextIsConfinedToItsOwnTenant` — under forced row-level security
  the audit row and grant written by one tenant are invisible from another tenant that
  shares the same local facility code (`MAIN`), so one organization's recorded context
  cannot be read to unlock another's data.

Production classes and objects exercised through the normal HTTP entry point:

- Controller: `com.aurevia.smartclinic.authz.access.AccessController`
  (`POST /api/v1/access/emergency`).
- Domain and boundary: `com.aurevia.smartclinic.authz.access.EmergencyAccessService`,
  `com.aurevia.smartclinic.authz.access.AccessRepository`,
  `com.aurevia.smartclinic.authz.access.AccessContracts`,
  `com.aurevia.smartclinic.authz.access.AccessRules`,
  `com.aurevia.smartclinic.tenant.TenantContext` and
  `com.aurevia.smartclinic.web.TenantContextInterceptor`.
- Audit recording: `com.aurevia.smartclinic.audit.AuditAppender` and
  `com.aurevia.smartclinic.audit.AuditRepository` (shared append-only audit helpers);
  the sensitive-action path writes through `AccessRepository.appendAudit`, which uses the
  same `audit_event` schema.

Where the clause is implemented today (source survey result):

- Schema: `db/migrations/V11__emergency_and_support_access.sql` creates
  `public.emergency_access_grant` with non-null `purpose`, `role_key`, `facility_code`
  and `subject` columns, and `public.support_access_incident`; both tables have forced
  RLS. `db/migrations/V1__tenant_schema.sql` defines the `audit_event` table with
  `purpose`, `scope`, `actor_id` and `correlation_id`; `db/migrations/V2__rls_roles_policies.sql`
  forces RLS on it; `db/migrations/V3__audit_chain.sql` makes it append-only and derives
  `actor_id` from the transaction-local `app.actor` binding so a caller cannot attribute
  an action to another user. `db/migrations/V93__membership_facility_scope.sql` adds the
  facility-scope fold.
- Code: the `com.aurevia.smartclinic.authz.access` package holds the controller, service,
  repository and contracts; `EmergencyAccessService.grant`/`active`/`revoke`/`review`
  append `purpose`, a bounded `role:facility` scope, correlation id and outcome to the
  audit chain inside the guarded transaction, including on denial. The `audit` package
  holds the shared append-only audit helpers.
- Contract: the `POST /api/v1/access/emergency` route is implemented in
  `com.aurevia.smartclinic.authz.access.AccessController` but is **not** declared in
  `contracts/openapi.yaml`; it is recorded as route-parity debt in
  `contracts/openapi.yaml` alongside the other `/api/v1/access/*` routes.
  That is a pre-existing contract gap outside this task's scope and does not affect
  whether the clause itself is built. The recording behaviour is enforced in the schema,
  service and repository, not by the contract file.

## Gap

None.
