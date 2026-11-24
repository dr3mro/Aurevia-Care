# IAM-01 clause 1 — identity and organization maintenance

## Clause

Maintain Organization, Facility, UserIdentity, Membership and RoleAssignment.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.authz.clauses.MaintainOrganizationFacilityUseridentityMembershipRoleassignmentTest`
(`apps/api/src/test/java/com/aurevia/authz/clauses/MaintainOrganizationFacilityUseridentityMembershipRoleassignmentTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic tenants with identical local identifiers, following the
fixtures and database setup of the neighbouring `StaffLifecycleIsolationTest` and
`FacilityIsolationTest`.

Test methods (one positive rule proof, four negative refusals):

- `tenantAdminMaintainsAllFiveEntitiesInsideTheActiveOrganization` — an authenticated
  `tenant_admin` creates a Facility and invites a staff member through the HTTP API,
  reads the member and immutable role-grant history back, and the durable
  `user_identity`, `membership`, `role_assignment` and `facility` rows are verified
  under forced row-level security with the correct organization and grantor. The
  invited identity is invisible from the other tenant.
- `receptionCannotMaintainFacilitiesOrStaff` — wrong role is refused (403) for
  facility creation, staff invitation and staff listing.
- `anotherTenantIsDeniedAndItsRecordsStayConcealed` — cross-tenant staff and facility
  reads are concealed (404), an actor with no membership in the selected
  organization is denied (403), and a missing active-organization selection is
  rejected (422).
- `invalidMaintenanceInputIsRejected` — invalid facility code, unknown role, too-short
  subject and blank display name are refused (422).
- `invitingAnExistingSubjectIsRejected` — a duplicate subject is refused (409).

Production classes the test exercises:

- HTTP entry points: `com.aurevia.web.FacilityController`,
  `com.aurevia.web.StaffController`,
  `com.aurevia.staff.StaffInvitationController`,
  `com.aurevia.authz.history.RoleHistoryController`.
- Domain services and boundaries: `com.aurevia.tenancy.FacilityService`,
  `com.aurevia.tenancy.FacilityRepository`,
  `com.aurevia.staff.StaffService`,
  `com.aurevia.staff.StaffRepository`,
  `com.aurevia.staff.StaffInvitationService`,
  `com.aurevia.authz.ActionPolicy`,
  `com.aurevia.authz.history.RoleHistoryService`,
  `com.aurevia.authz.history.JdbcRoleHistoryStore`.
- Tenant scope: `com.aurevia.tenant.TenantContext`,
  `com.aurevia.tenant.TenantGuardTransactionManager`,
  `com.aurevia.web.TenantContextInterceptor`,
  `com.aurevia.web.ClaimsTenantResolver`.

Where the clause is implemented today (survey result):

- Schema: `db/migrations/V1__tenant_schema.sql` defines `organization`, `facility`,
  `user_identity`, `membership` and `role_assignment`;
  `db/migrations/V2__rls_roles_policies.sql` adds forced row-level security and the
  tenant-isolation policy on every tenant table;
  `db/migrations/V4__staff_lifecycle.sql` makes membership and role grants
  append-only and folds current authorization on read.
- Contract: `contracts/openapi.yaml` declares `/api/v1/facilities`, `/api/v1/staff`,
  `/api/v1/staff/{subject}`, `/api/v1/staff/{subject}/deactivate`,
  `/api/v1/staff/{subject}/history` and the `X-Active-Organization` header.

## Gap

None
