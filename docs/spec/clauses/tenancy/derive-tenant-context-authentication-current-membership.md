# ARC-02 clause 1 — Derive tenant context from authentication and current membership

## Clause

Derive tenant context from authentication and current membership.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.tenancy.clauses.DeriveTenantContextAuthenticationCurrentMembershipTest`
(`apps/api/src/test/java/com/aurevia/tenancy/clauses/DeriveTenantContextAuthenticationCurrentMembershipTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry point `GET /api/v1/facilities/{code}` against two synthetic organizations
reusing the same tenant-local facility code `MAIN`. Each organization has one
`tenant_admin` subject with an active membership; a third subject has an active
membership event followed by a later, explicitly dated revoked event, so the
current-membership fold resolves to revoked. The suite creates and drops its own
database `aurevia_derive_tenant_ctx_it`.

- `authenticatedSubjectWithActiveMembershipDerivesItsOwnTenant` — an authenticated
  subject with an active membership in its selected organization gets HTTP 200 and
  reads only that organization's facility (`Alpha Main`, organization A); the peer
  organization's identifier and facility name are absent from the body.
- `subjectWithMembershipOnlyInOrganizationAIsRefusedOrganizationB` (negative) — a
  subject whose membership exists only in organization A that selects organization
  B is refused with HTTP 403 and code `FORBIDDEN` / `no active membership`.
- `missingActiveOrganizationHeaderFailsClosed` (negative) — no
  `X-Active-Organization` selection is refused with HTTP 422 and code
  `INVALID_TENANT_SELECTION` before any tenant-scoped data is returned.
- `missingSubjectFailsClosed` (negative) — no `X-Synthetic-Subject` is refused with
  HTTP 401 and code `UNAUTHENTICATED`.
- `blankSubjectFailsClosed`, `malformedActiveOrganizationHeaderFailsClosed` and
  `missingSubjectIsRefusedBeforeMissingOrganization` (negatives) — a blank subject
  fails with 401, a non-UUID selection fails with 422, and authentication is checked
  before selection when both headers are absent.
- `deactivatedMembershipCannotDeriveTenant` (negative) — a subject whose current
  membership event is revoked is refused with HTTP 403 and `no active membership`,
  even though its `tenant_admin` role grant is attached to that current event. This
  distinguishes the membership guard from a missing-permission denial.

Production path exercised by these HTTP requests:

- `com.aurevia.web.TenantContextInterceptor` and
  `com.aurevia.tenant.TenantContext` — under the `synthetic-auth` test
  profile the interceptor validates the correlation id, requires a subject (401),
  requires a well-formed organization selection (422) and binds the selected
  organization as the request tenant context. It clears the context at completion.
  The production JWT and OIDC browser branches of the same interceptor, which call
  `com.aurevia.web.ClaimsTenantResolver` to check the verified `orgs`
  claim, are **not** traversed by this synthetic-auth suite; that claim half is
  covered by `com.aurevia.web.ClaimsTenantResolverTest`
  (`rejectsClaimsWithoutSubject`, `rejectsBlankSubject`,
  `rejectsOrganizationNotListedInClaims`, `rejectsMalformedActiveOrganizationHeader`,
  `rejectsMissingActiveOrganizationHeader`, and others).
- `com.aurevia.tenancy.FacilityController` — thin adapter calling
  `FacilityService.get`.
- `com.aurevia.tenancy.FacilityService` — `get` opens the tenant-guarded
  transaction and calls `authorize("FACILITY_READ")`, re-evaluating membership and
  permission on every request so revocation takes effect on the next access.
- `com.aurevia.tenancy.FacilityRepository` — `hasActiveMembership` and
  `activeRoles` query the RLS folds; `findByCode` relies on forced RLS and
  `appendAudit` records successful reads inside the same transaction.
- `db/migrations/V1__tenant_schema.sql` — tenant-owned `(organization_id, id)` keys;
  `db/migrations/V2__rls_roles_policies.sql` — forced row-level security keyed on
  `app.current_org`; `db/migrations/V4__staff_lifecycle.sql` — `current_membership()`,
  `has_active_membership` and `active_roles`, SECURITY INVOKER under RLS so an unset
  context fails closed and only an active membership with a live role grant derives
  a tenant.
- `contracts/openapi.yaml` — the `GET /api/v1/facilities/{code}` entry and its
  `Facility` schema carrying `organizationId`.

## Gap

None.
