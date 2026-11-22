# IAM-02 clause 3 — permission changes take effect on the next protected request

## Clause

Permission changes take effect on the next protected request.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.authz.clauses.PermissionChangesTakeEffectNextProtectedRequestTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/authz/clauses/PermissionChangesTakeEffectNextProtectedRequestTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the `synthetic-auth`
profile, two synthetic tenants and the same fixtures and database setup as the
neighbouring `authz.clauses` tests. All four tests are active (none are disabled)
and pass with synthetic data only. The positive test follows the entry-point-to-test
trace `POST /api/v1/staff` → `POST /api/v1/staff/{subject}/deactivate` →
`GET /api/v1/staff/{subject}` and reads the authoritative database fold between steps.

Test methods (one positive rule proof, three negative refusals):

- `deactivationDeniesTheNextProtectedRequestWithoutAnySessionChange` — positive proof.
  A `tenant_admin` invited through the HTTP entry point can read its own staff record
  (HTTP 200) while active, and `public.has_active_membership` reports active. An
  administrator then appends a revoked membership event through
  `POST /api/v1/staff/{subject}/deactivate`; the database fold immediately reports
  inactive. The very next `GET /api/v1/staff/{subject}` from the same subject, with an
  unchanged synthetic subject header and no session change, is refused with HTTP 403
  `FORBIDDEN` whose body states "no active membership". The denial therefore comes
  from re-deriving authority on the request, not from token or session expiry.
- `wrongRoleCannotChangeAnotherSubjectsPermission` — negative proof. A `reception`
  member is refused (403) when attempting both the deactivation command and the
  protected read of another subject, and the target's membership fold is still active
  afterwards: a wrong role cannot change the permission or bypass the gate.
- `anotherTenantCannotChangeOrSeeTheSubject` — negative proof. An administrator of
  the other tenant is refused (403) when attempting the deactivation in the victim's
  organization, and reading that subject under the other tenant returns 404, so the
  victim's existence and permission change are concealed across tenants.
- `missingAndForbiddenDeactivationStatesAreRefused` — negative proof. Deactivating a
  nonexistent subject returns 404 and deactivating an already deactivated subject
  returns 409, so the permission-change command refuses missing and forbidden states.

Production classes exercised through the normal HTTP entry points:

- Controllers: `com.aurevia.smartclinic.web.StaffController` (the invite, deactivate
  and read commands).
- Domain services and boundaries: `com.aurevia.smartclinic.staff.StaffService`, whose
  `authorize` helper calls `hasActiveMembership` and `activeRoles` on every command so
  a deactivation or expired grant denies the next request without waiting for a token
  to expire; `com.aurevia.smartclinic.staff.StaffRepository`;
  `com.aurevia.smartclinic.authz.ActionPolicy`;
  `com.aurevia.smartclinic.tenant.TenantContext`;
  `com.aurevia.smartclinic.web.TenantContextInterceptor` and
  `com.aurevia.smartclinic.tenancy.FacilityService` (the same request-time check on a
  second protected route family).

Where the clause is implemented today (source survey result):

- `db/migrations/V4__staff_lifecycle.sql` defines `public.current_membership()`,
  `public.has_active_membership(text)` and `public.active_roles(text)`. Membership and
  role grants are append-only; the current membership is the latest event whose
  `valid_from <= now()`, and both functions check `status = 'active'` with
  `valid_from`/`valid_to` effective-date gating. Deactivation appends a `revoked`
  event, so the fold turns inactive immediately. The functions are `SECURITY INVOKER`
  under forced row-level security, so a missing tenant context fails closed (ARC-02).
- `apps/api/src/main/java/com/aurevia/smartclinic/staff/StaffService.java` re-resolves
  membership and roles in each guarded transaction: `invite`, `deactivate`, `get`,
  `history` and `list` all call `authorize` before acting, and `deactivate` appends the
  revoked membership event. `StaffRepository` reads the two fold functions above.
- `db/migrations/V1__tenant_schema.sql` and `db/migrations/V2__rls_roles_policies.sql`
  define the tenant tables and forced row-level security; `db/migrations/V12__checkin_worklist.sql`
  also calls `public.has_active_membership` at write time so assignment fails closed.
- Contract: `contracts/openapi.yaml` declares `/api/v1/staff`,
  `/api/v1/staff/{subject}` and `/api/v1/staff/{subject}/deactivate`, which are the
  entry points the test drives.
- The nearby `com.aurevia.smartclinic.authz.history.RoleHistoryIntegrationTest.permissionChangeDeniesTheNextProtectedRequest`
  independently exercises the same rule through the role-history route.

## Gap

None.

The in-scope G0 permission change is deactivation; arbitrary role reassignment and
permission-matrix edits are a separate later slice and are not part of this clause's
minimum rule. This task added proof only: it did not change production code, an
existing test, the OpenAPI contract or a migration.
