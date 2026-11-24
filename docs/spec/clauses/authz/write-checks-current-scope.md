# IAM-01 clause 6 — write checks current scope

## Clause

Every write checks current scope.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.authz.clauses.WriteChecksCurrentScopeTest`
(`apps/api/src/test/java/com/aurevia/authz/clauses/WriteChecksCurrentScopeTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants:

- `writeSucceedsWhenActorHasActiveMembershipInCurrentScope` — a `tenant_admin` of
  the active organization creates a facility and reads it back in that organization (201/200).
- `writeByActorWithoutMembershipInCurrentScopeIsDenied` — a `tenant_admin` of a
  different organization sending the first organization as the active scope is refused (403)
  and no row is created (404 on read-back).
- `writeByActorWithRevokedMembershipInCurrentScopeIsDenied` — a still-granted but
  revoked membership is refused on the next write (403) and no row is created.
- `writeWithoutRequiredRoleInCurrentScopeIsDenied` — an active member without the
  write permission is refused (403) and no row is created.

Production classes exercised by the entry point:
`com.aurevia.web.TenantContextInterceptor` (binds the current scope only
from `X-Active-Organization` plus the authenticated subject),
`com.aurevia.tenant.TenantContext`,
`com.aurevia.web.FacilityController`,
`com.aurevia.tenancy.FacilityService` (re-checks active membership and
action permission inside the tenant-guarded transaction on every write),
`com.aurevia.tenancy.FacilityRepository` (RLS-bounded store) and
`com.aurevia.authz.ActionPolicy`, with tenant RLS/FORCE RLS supplied by
the Flyway migrations under `db/migrations`.

Discovery also found the same current-scope re-check in
`com.aurevia.staff.StaffService`,
`com.aurevia.staff.StaffInvitationService`,
`com.aurevia.configuration.ConfigurationService`, the other domain
`*Service.authorize(...)` methods and the central command gate
`com.aurevia.api.authorization.CommandAuthorization`; those are
exercised by their own suites and are not re-tested here (this story proves the
clause through one representative write entry point).

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=WriteChecksCurrentScopeTest test`
— `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
