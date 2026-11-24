# IAM-01 clause 2 — Tenant is the organization

## Clause

Tenant is the organization.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.authz.clauses.TenantOrganizationTest`
(`apps/api/src/test/java/com/aurevia/authz/clauses/TenantOrganizationTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry point `GET /api/v1/facilities/{code}` against two synthetic organizations
with the same tenant-local facility code `MAIN`.

- `activeOrganizationDeterminesWhichFacilityIsVisible` — a request whose active
  organization is organization A reads only organization A's facility.
- `identicalLocalFacilityCodeIsScopedToItsOwningOrganization` — two organizations
  with the same local facility code each see only their own organization and name;
  neither sees the other's data.
- `subjectCannotSelectAnotherOrganizationAsItsTenant` (negative) — a subject from
  organization A that selects organization B is refused with HTTP 403 because it
  has no active membership in the selected organization.
- `missingActiveOrganizationSelectionIsRejected` (negative) — a request with no
  `X-Active-Organization` selection is refused with HTTP 422 before any query runs.

Production classes and files exercised by the request path:

- `com.aurevia.web.TenantContextInterceptor` and
  `com.aurevia.web.ClaimsTenantResolver` — bind the selected organization
  as the request tenant context; missing or invalid selection fails closed.
- `com.aurevia.tenant.TenantContext` — the request-scoped context whose
  `organizationId` is the active organization (never inferred from a resource).
- `com.aurevia.tenant.TenantGuardTransactionManager` — binds the selected
  organization to `app.current_org` inside each transaction.
- `com.aurevia.tenancy.FacilityController`, `FacilityService` and
  `FacilityRepository` — re-check membership in the active organization on every
  protected request and read only that organization's rows.
- `db/migrations/V1__tenant_schema.sql` — the `organization` table and composite
  `(organization_id, id)` tenant-owned keys; `db/migrations/V2__rls_roles_policies.sql`
  — forced row-level security keyed on `app.current_org()`;
  `db/migrations/V4__staff_lifecycle.sql` — `has_active_membership` under forced RLS.
- `contracts/openapi.yaml` — the `GET /api/v1/facilities/{code}` entry and its
  `Facility` schema (carrying `organizationId`).

## Gap

None.
