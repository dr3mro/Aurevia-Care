# IAM-02 clause 1 — staff lifecycle and location configuration

## Clause

Invite and deactivate staff, assign bounded roles, configure locations and retain immutable membership history.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.authz.clauses.InviteDeactivateStaffAssignBoundedRolesConfigureTest`
(path: `apps/api/src/test/java/com/aurevia/authz/clauses/InviteDeactivateStaffAssignBoundedRolesConfigureTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic tenants, following the
fixtures and database setup of the neighbouring clause tests and
`com.aurevia.it.StaffLifecycleIsolationTest`. All four tests are active
(none are disabled) and pass with synthetic data only.

Test methods (one positive rule proof, three negative refusals):

- `tenantAdminInvitesDeactivatesAssignsBoundedRolesAndConfiguresLocationsWithImmutableHistory`
  — positive proof. An authenticated `tenant_admin` creates a location (facility),
  invites a staff member with one bounded `reception` role, reads the grantor and
  effective dates back from the immutable history, deactivates the member, then
  reads a two-event membership history containing both an `active` and a `revoked`
  event with a single unchanged role grant. The durable `user_identity`, `membership`, `role_assignment`
  and `facility` rows are verified under forced row-level security in the active
  organization, and the invited identity is invisible from the other tenant.
- `receptionIsRefusedStaffAndLocationAdministration` — wrong role is refused (403)
  for facility creation, staff invitation, staff deactivation and staff-history reads.
- `unboundedRoleDuplicateAndForbiddenStateAreRefused` — an unbounded role key is
  rejected (422), a duplicate subject is refused (409), a missing deactivation target
  is refused (404), and deactivating an already deactivated member is refused (409).
- `membershipAndRoleHistoryRejectMutation` — the append-only invariant is enforced at
  two database boundaries. The application role has no `UPDATE`/`DELETE` grant (V2/V4),
  so both mutations raise `SQLException`. Attempting the same mutations as the table
  owner, which keeps its privileges but remains subject to forced RLS, raises
  `SQLException` with SQLSTATE `44000` from the V4 append-only trigger.

Production classes exercised through the normal HTTP entry points:

- Controllers: `com.aurevia.web.FacilityController` and
  `com.aurevia.web.StaffController`.
- Domain services and boundaries: `com.aurevia.tenancy.FacilityService`,
  `com.aurevia.staff.StaffService`, `com.aurevia.staff.StaffRepository`,
  `com.aurevia.staff.StaffHistory`, `com.aurevia.authz.ActionPolicy`,
  `com.aurevia.tenant.TenantContext` and the
  `com.aurevia.web.TenantContextInterceptor`.

Where the clause is implemented today (source survey result):

- Schema: `db/migrations/V1__tenant_schema.sql` defines `organization`, `facility`,
  `user_identity`, `membership` and `role_assignment`;
  `db/migrations/V2__rls_roles_policies.sql` adds forced row-level security and the
  tenant-isolation policy on every tenant table;
  `db/migrations/V4__staff_lifecycle.sql` makes membership and role grants append-only,
  folds current status from the latest effective event, records the actor as grantor
  and stamps `membership.created_at`; `valid_from` comes from the V1 column defaults and
  the grant insert in `com.aurevia.staff.StaffRepository`.
- Contract: `contracts/openapi.yaml` declares `/api/v1/facilities`,
  `/api/v1/facilities/{code}`, `/api/v1/staff`, `/api/v1/staff/{subject}`,
  `/api/v1/staff/{subject}/deactivate` and `/api/v1/staff/{subject}/history`.
- Bounded role keys are held by `com.aurevia.staff.StaffService` and the
  action-to-role decisions by `com.aurevia.authz.ActionPolicy`.

## Gap

None.
