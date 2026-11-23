# IAM-06 clause 3 — It cannot unlock other organizations’ data

## Clause

It cannot unlock other organizations’ data.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.audit.clauses.CannotUnlockOtherOrganizationsDataTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/audit/clauses/CannotUnlockOtherOrganizationsDataTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry points of the emergency-access feature against two synthetic organizations
(`ORG_A` and `ORG_B`), both with the break-glass configuration approved and a tenant-local
`MAIN` facility.

- `emergencyGrantUnlocksOnlyTheCliniciansOwnOrganization` — a clinician opens an
  emergency grant while their own organization is active, `/api/v1/access/emergency/active`
  reports `allowed: true` with reason `ACTIVE`, forced row-level security shows the grant
  under `ORG_A` and hides it under `ORG_B`.
- `clinicianCannotUnlockAnotherOrganizationBySelectingIt` (negative) — a clinician with
  membership only in `ORG_A` selects `ORG_B` as the active organization and requests an
  emergency grant; the service re-reads membership in the selected organization and
  refuses with HTTP 403, and no grant row is created in `ORG_B` even though that
  organization has emergency access approved.
- `anotherOrganizationCannotSeeOrReviewTheGrant` (negative) — an existing `ORG_A` grant
  is invisible to a direct `emergency_access_grant` query under `ORG_B`, and a privacy
  auditor of `ORG_B` receives HTTP 404 when attempting to review it, leaving the owner's
  grant untouched.

Production classes and files exercised by these requests:

- `com.aurevia.smartclinic.authz.access.AccessController` — thin HTTP adapter for
  `POST /api/v1/access/emergency`, `GET /api/v1/access/emergency/active` and
  `POST /api/v1/access/emergency/{id}/review`; the organization and actor come only
  from request headers, and the body-supplied facility code is validated and scoped
  to the active organization by the domain service and forced RLS.
- `com.aurevia.smartclinic.authz.access.EmergencyAccessService` — re-reads active
  membership, the clinician role, site approval and facility scope inside each guarded
  transaction, and persists the grant under the active organization only.
- `com.aurevia.smartclinic.authz.access.AccessRepository` — `hasActiveMembership`,
  `insertGrant`, `latestGrant`, `findGrant`, `reviewGrant`, `effectiveConfig`,
  `facilityExists`, `outsideAssignedFacilities` and `appendAudit`, all bound by forced
  row-level security.
- `com.aurevia.smartclinic.authz.access.AccessRules` — validates facility, reason and
  duration before any authorization decision.
- `com.aurevia.smartclinic.web.TenantContextInterceptor` — binds the selected
  organization from `X-Active-Organization`; `com.aurevia.smartclinic.tenant.TenantContext`
  and `com.aurevia.smartclinic.tenant.TenantGuardTransactionManager` bind it to
  `app.current_org` inside the transaction.
- `db/migrations/V11__emergency_and_support_access.sql` — tenant-owned
  `emergency_access_grant` with forced row-level security and a composite facility
  foreign key; `db/migrations/V88__access_review.sql` — tenant-owned
  `emergency_access_review` with forced row-level security and a composite
  `(organization_id, grant_id)` foreign key.

The access endpoints are currently listed in `contracts/openapi.yaml`
(baselined as not yet documented in `contracts/openapi.yaml`); this is a documentation
gap on a separate contract task and does not affect the isolation behaviour proven here.

## Gap

None.
