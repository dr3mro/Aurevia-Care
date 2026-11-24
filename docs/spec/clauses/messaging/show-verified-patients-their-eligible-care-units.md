# MSG-01 clause 1 — Show verified patients their eligible care units

## Clause

Show verified patients their eligible care units in the app with organization, facility, unit name and supported topics.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.messaging.clauses.ShowVerifiedPatientsTheirEligibleCareUnitsTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/ShowVerifiedPatientsTheirEligibleCareUnitsTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives
`GET /api/v1/care-units` over HTTP with two tenants sharing identical local unit,
chart and relationship identifiers. All fixtures are synthetic; no real patient
data or credentials are used.

- `verifiedPatientSeesOnlyOwnEligibleUnitsWithOrganizationFacilityNameAndTopics` —
  a patient with a current `linked` chart sees the unit backed by an active,
  unconsumed, unexpired verified relationship, carrying its `organizationId`,
  `facilityId`, `name` and `topics[]`. Five control units prove the rule is an
  eligibility filter, not a directory dump: an inactive unit, a consumed
  relationship, an expired relationship, an inactive relationship and a unit with
  no relationship are all absent. A unit that exists only in the other tenant is
  also absent.
- `patientWithoutUnitListRoleIsDeniedTheDirectory` (negative) — an active member
  whose only role is `reception` gets HTTP 403 with `code` `FORBIDDEN`, proving the
  `UNIT_LIST` permission gate.
- `patientWithoutAVerifiedChartIsDeniedTheDirectory` (negative) — an active
  `patient` member with no current `linked` chart gets HTTP 403 with `code`
  `NO_VERIFIED_CHART`, proving the verified-chart branch.
- `directoryInOneTenantDoesNotExposeAnotherTenantsUnit` (negative) — a patient in
  tenant B reading the same local unit identifier gets tenant B's directory row
  (tenant B organization and name) plus tenant B's own unit, and never tenant A's
  row, proving the tenant filter. The topics assertion checks the clause field;
  tenant provenance is proven by the organization and name values, which differ.

Production classes and files exercised by the request path:

- `com.aurevia.messaging.units.CareUnitController` — `units()`, the
  thin `GET /api/v1/care-units` adapter that injects the service.
- `com.aurevia.messaging.units.CareUnitDirectoryService` —
  `eligibleUnits()`: context, `UNIT_LIST` authorization, verified-chart fold,
  relationship usability (`active`, not consumed, not expired), active-unit
  filter, tenant-scoped resolution and audit.
- `com.aurevia.messaging.units.MessagingActions` — the bounded
  `UNIT_LIST` action matrix; `patient` is allowed, `reception` is not.
- `com.aurevia.messaging.units.JdbcMessagingStore` —
  `hasActiveMembership`, `activeRoles`, `verifiedPatientCharts`, `relationshipsFor`
  and `findUnit`, the tenant-bound SQL boundary.
- `com.aurevia.messaging.units.MessagingContract` — `UnitDirectory`
  and `CareUnit` (`organizationId`, `facilityId`, `name`, `topics`, `active`,
  `unitKey`), the serialized response contract.
- `com.aurevia.api.ApiErrorHandler` and
  `com.aurevia.web.TenantContextInterceptor` — the `$.code` error body
  and the synthetic identity headers that bind the request tenant.
- `db/migrations/V60__care_units.sql` — the `care_unit` and
  `eligible_relationship` tables with forced row-level security; the isolation
  boundary under test.

Entry-point-to-test trace: `GET /api/v1/care-units` (synthetic subject and active
organization headers) → `TenantContextInterceptor` → `CareUnitController.units()`
→ `CareUnitDirectoryService.eligibleUnits()` → `JdbcMessagingStore` → V60 tables
→ `UnitDirectory` JSON asserted in the positive method; the two denial branches
are asserted by status and `$.code`, and the isolation branch by value and by the
absence of the other tenant's row.

Fault detection was checked with three temporary production mutants, each run
against this test class and then reverted with a clean production diff: ignoring
`consumed` failed on "a consumed relationship confers no eligibility"; ignoring
`expires_at` failed on "an expired relationship confers no eligibility"; removing
the `CareUnit::active` filter failed on "an inactive unit is not in the
directory".

## Gap

No gap in the clause behavior: the rule is implemented and proven through the
HTTP entry point above, so all tests are active and no `@Disabled` is used.
`GET /api/v1/care-units` is listed in
`contracts/openapi.yaml` (lines 12–16), so the route is baselined as
a known parity gap rather than published in `contracts/openapi.yaml`. Documenting
it is a separate repo-wide contract task; it does not change this clause's
runtime behavior or its test evidence. The patient mobile directory client is
also still a synthetic, deterministic fixture
(`apps/patient-mobile/src/features/units/directory.ts`, "Production clients must
use the generated API client"), so this document claims server-side proof only,
not end-to-end mobile client wiring. Both gaps are pre-existing and out of scope
for this test-only story.
