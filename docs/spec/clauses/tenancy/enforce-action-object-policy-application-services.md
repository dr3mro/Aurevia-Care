# ARC-02 clause 2 — enforce action and object policy in application services

## Clause

Enforce action and object policy in application services.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.tenancy.clauses.EnforceActionObjectPolicyApplicationServicesTest`
(`apps/api/src/test/java/com/aurevia/tenancy/clauses/EnforceActionObjectPolicyApplicationServicesTest.java`),
an HTTP-level integration test on a dedicated synthetic PostgreSQL database
`orevia_action_object_it` with two tenants. Every caller has a valid subject,
an explicit active organization and an active membership, so a denial can only
come from the policy under test:

- `actionPolicyAllowsTenantAdminToWriteAndReadItsOwnObject` — baseline. A
  `tenant_admin` of the active organization creates facility `APOL-A` (201 with
  the expected `Location`) and reads it back (200), with the owning organization
  in both bodies. Establishes the normal path the denials must break; on its own
  it is not a detector.
- `writeByRoleWithoutActionPermissionIsDenied` — a `reception` member of the
  active organization is refused `FACILITY_WRITE` (403) and `FACILITY_READ` (403)
  with the reason `missing action permission`; an administrator's read-back of
  the attempted code is 404, so no row was written. The asserted reason pins the
  denial to the action guard in the service, not to the membership guard.
- `objectFromAnotherTenantIsConcealedEvenForAnAdmin` — with in-scope controls
  showing the same administrator can read their own object (200) and the owner
  can read the foreign object (200), the same authorized administrator receives
  404 for the foreign tenant's `APOL-B`. Object scoping is therefore a separate
  decision after a successful action check.
- `applicationServiceEnforcesActionPolicyWithoutHttpClient` — calling the
  injected application service directly (no HTTP client) with a bound
  `TenantContext` reproduces the `403` action denial for `reception` and returns
  the tenant-owned `Facility` for `tenant_admin`. This proves enforcement lives
  below the web layer.

Production classes exercised through the entry point: `web.FacilityController`
(thin `create`/`get` adapter), `web.TenantContextInterceptor` and
`tenant.TenantContext` (bind the current organization from the authenticated
subject plus the explicit organization header), `tenant.TenantGuardTransactionManager`
(transaction-local `app.current_org`), `tenancy.FacilityService` (`get`/`create`
call `authorize(String action)`, which checks `hasActiveMembership` and then
`noneMatch(role -> policy.allows(action, role))`), `authz.ActionPolicy.allows`
(the role/action matrix), and `tenancy.FacilityRepository` (RLS-bounded
`findByCode`/`query`/`insert` plus the membership and role lookups). Tenant
isolation is supplied by the existing Flyway migrations: `V1__tenant_schema.sql`
(tenant-owned facility key), `V2__rls_roles_policies.sql` (ENABLE and FORCE ROW
LEVEL SECURITY with the `tenant_isolation` policy), `V3__audit_chain.sql` and
`V4__staff_lifecycle.sql` (`has_active_membership` and `active_roles`).

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=EnforceActionObjectPolicyApplicationServicesTest test`
— `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`. No test is disabled.

This clause is distinct from the neighbouring evidence: IAM-01 clause 6
(`docs/spec/clauses/authz/write-checks-current-scope.md` and
`WriteChecksCurrentScopeTest`) proves write re-checks of current-scope membership
and role; ARC-02 clause 3 (`IsolationAcrossSurfacesTest`) proves the RLS
mechanism across API surfaces. This class proves that the application service
applies the action matrix and the object's tenant scope on both read and write,
including without the HTTP layer. `api.authorization.CommandAuthorization` and
`CommandCatalog` are not used as evidence: they have no production caller; live
enforcement is in the `*Service.authorize(...)` methods.

Full production-chain reachability (new migration, `Jdbc*` store, OpenAPI entry
and regenerated client) is N/A for this test-only story: no route, store, table
or contract is introduced. The request reaches the existing controller →
service → RLS-bounded repository chain, and the seeded rows persist in the
migrated database for the lifetime of the suite.

## Gap

None.
