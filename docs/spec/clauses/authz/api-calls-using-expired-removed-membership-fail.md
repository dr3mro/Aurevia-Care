# IAM-02 clause 4 — expired or removed membership fails closed

## Clause

API calls using expired or removed membership fail closed.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.authz.clauses.ApiCallsUsingExpiredRemovedMembershipFailTest`
(path: `apps/api/src/test/java/com/aurevia/authz/clauses/ApiCallsUsingExpiredRemovedMembershipFailTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic organizations, following the fixtures and database setup of the
neighbouring clause tests (`InviteDeactivateStaffAssignBoundedRolesConfigureTest`) and
`com.aurevia.it.StaffLifecycleIsolationTest`. All four tests are active (none
are disabled) and pass with synthetic data only.

Test methods (one positive rule proof, three negative refusals):

- `activeMembershipAllowsTheProtectedFacilityRead` — positive proof. A synthetic
  `tenant_admin` whose current membership event is active and unexpired reads a facility
  in the selected organization over HTTP and receives 200 with that tenant's data. The
  same database fold used by the API reports active membership.
- `expiredMembershipFailsClosedOnTheNextProtectedReadAndWrite` — negative proof of
  expiry. A `tenant_admin` whose only membership event has a closed validity window
  (`valid_to` in the past) is refused both `GET /api/v1/facilities/{code}` and
  `POST /api/v1/facilities` with HTTP 403 `FORBIDDEN` ("no active membership"); no
  tenant facility data is returned in the body, and a follow-up read as an active admin
  confirms the refused write created no facility row (404 `NOT_FOUND`).
- `removedMembershipFailsClosedOnTheNextProtectedCall` — negative proof of removal. A
  `tenant_admin` first reads the facility successfully, is then deactivated through the
  real `POST /api/v1/staff/{subject}/deactivate` entry point (which appends a `revoked`
  membership event), and on the very next read and write is refused with HTTP 403
  `FORBIDDEN` ("no active membership") and no facility data; a follow-up read confirms the
  refused write created no facility row. The database fold reports inactive membership
  after the deactivation.
- `membershipInAnotherTenantIsRefusedForTheSelectedOrganization` — negative proof of
  tenant scope. A subject with active membership in organization B reads its own facility
  successfully but is refused with HTTP 403 `FORBIDDEN` when the same subject selects
  organization A, so membership in one tenant cannot authorize a call against another.

Production classes exercised through the normal HTTP entry points:

- Controllers: `com.aurevia.web.FacilityController` and
  `com.aurevia.web.StaffController`.
- Domain services and boundaries: `com.aurevia.tenancy.FacilityService`
  (re-checks membership and permission inside the tenant-guarded transaction via
  `authorize`), `com.aurevia.staff.StaffService`,
  `com.aurevia.staff.StaffRepository`
  (`hasActiveMembership` / `activeRoles`), `com.aurevia.authz.ActionPolicy`,
  `com.aurevia.tenant.TenantContext` and
  `com.aurevia.web.TenantContextInterceptor`.

Where the clause is implemented today (source survey result):

- Schema: `db/migrations/V1__tenant_schema.sql` defines the `membership` table with
  `status`, `valid_from` and `valid_to`; `db/migrations/V2__rls_roles_policies.sql` adds
  forced row-level security; `db/migrations/V4__staff_lifecycle.sql` makes membership
  append-only, folds the current event from the latest `valid_from <= now()`, and defines
  `public.has_active_membership(text)` and `public.active_roles(text)`. Both functions
  require the current event's `status = 'active'` and
  `(valid_to IS NULL OR valid_to > now())`, so expiry or a later `revoked` event yields
  no active membership; `current_membership()` itself fails closed when the tenant GUC
  is unset.
- Contract: `contracts/openapi.yaml` declares `/api/v1/facilities/{code}`,
  `/api/v1/facilities` and `/api/v1/staff/{subject}/deactivate`, the entry points used by
  the proof.
- The membership fold is reused across the API: every owning service re-reads it inside
  the tenant-guarded transaction, so a deactivation or expiry has effect on the next
  protected request without token or session expiry.

## Gap

None.

The IAM-02 clause-4 rule is built and proven by active tests at the normal HTTP entry
points. This task added proof only; it did not change production code, an existing test,
the OpenAPI contract or a migration.
