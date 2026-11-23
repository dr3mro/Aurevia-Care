# ADM-02 clause 3 — exports use explicit scope, expiry and audit

## Clause

Exports use explicit scope, expiry and audit.

## Status

Proven by tests

## Evidence

Scope of this status: "Proven by tests" covers the clause behaviour only (explicit
scope, bounded expiry and audit). It does not claim OpenAPI publication or live
deployment; those are separate concerns. The `tenant_admin` permission predicate
itself is proven by `ExportPermissionGuardTest`, and forced-RLS invisibility of
another tenant's job row is proven by
`com.aurevia.smartclinic.tenant.export.TenantExportIntegrationTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/tenant/export/TenantExportIntegrationTest.java`,
`aJobFromAnotherTenantIsInvisibleAndExpiryIsDurable`); this class exercises those
boundaries through the service and HTTP entry points.


Test class `com.aurevia.smartclinic.configuration.clauses.ExportsUseExplicitScopeExpiryAuditTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/configuration/clauses/ExportsUseExplicitScopeExpiryAuditTest.java`).
It reuses the synthetic fixtures of `TenantExportServiceTest` (mocked
`ExportStore`, `ExportPermissionGuard` and `AuditAppender`, a fixed `Clock`,
`FileSystemObjectStore` and `TenantContext.set/clear`) and the standalone MockMvc
plus `ApiErrorHandler` fixture of `ExportControllerTest`. All data is synthetic.

- `exportCarriesExplicitScopePolicyExpiryAndAudit` — an active `tenant_admin`
  `start()` produces a manifest whose JSON binds `organization=org-a` and
  `policy_version=export-policy-1.0`, and a result whose `expiresAt` is the fixed
  `now + 24h` retention; `EXPORT_START` is appended with purpose and scope
  `TENANT_EXPORT`, resource type `EXPORT` and outcome `SUCCESS`.
- `httpStartPublishesExplicitExpiryAtTheEntryPoint` — the same rule driven through
  `POST /api/v1/tenant/exports` on the real service: 200 with an `expires_at`
  equal to the bounded retention instant and an `EXPORT_START` audit fact.
- `startWithoutExportPermissionIsDeniedAndAudited` — a subject without the
  bounded export permission is refused (403 `FORBIDDEN`) and an `EXPORT_DENIED`
  fact with scope `TENANT_EXPORT` and outcome `DENIED` is appended.
- `downloadAfterExpiryIsRefused` and `httpExpiredDownloadIsRenderedAsGone` — a
  clock past the retention instant is refused both at the service (410
  `EXPORT_EXPIRED`) and over `GET /api/v1/tenant/exports/{jobId}` (410 with code
  `EXPORT_EXPIRED`), with no download audit fact appended.
- `downloadOfAnotherTenantsJobIsNotFound` — with `TenantContext` switched to a
  second tenant and `store.find` stubbed to return empty (simulating the
  RLS-filtered lookup), disclosure is refused (404 `EXPORT_NOT_FOUND`) before any
  object read; this shows the service binds the active tenant scope and fails
  closed. The database RLS itself is proven by `TenantExportIntegrationTest`, not
  by this stubbed unit test.
- `downloadOfMissingManifestIsRefused` — a job whose promoted manifest object is
  gone is refused (409 `EXPORT_OBJECT_MISSING`) instead of returning an empty or
  stale artifact.
- `interruptedExpiryRetryDoesNotConflict` — a row already `EXPIRED` whose
  `markExpired` retry returns false completes without a conflict, so the audit
  trail and cleanup are not left in an orphaned state.

Production classes exercised by the entry point:
`com.aurevia.smartclinic.tenant.export.ExportController` (thin injected HTTP
adapter for `/api/v1/tenant/exports`),
`com.aurevia.smartclinic.tenant.export.TenantExportService` (`start`, `download`,
`expire` and the `authorize` guard),
`com.aurevia.smartclinic.tenant.export.ExportPermissionGuard` (`mayExport`,
bounded to an active `tenant_admin`),
`com.aurevia.smartclinic.tenant.export.ExportModel.Manifest` (organization and
policy version), `ExportModel.ExportJob` (`expired`, `isExpired`) and
`ExportModel.ExportResult` (explicit expiry), with tenant RLS/FORCE RLS supplied
by `db/migrations/V89__tenant_export.sql`. Audit events are appended through
`com.aurevia.smartclinic.audit.AuditAppender` under the OPS-02 chain.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ExportsUseExplicitScopeExpiryAuditTest test`
— `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

No gap in clause behaviour; the rule is built and proven by active tests. This
task added proof only: it changed no production code, existing test, OpenAPI
contract or migration. The only outstanding item is separate tracked OpenAPI debt
(described below), which is not part of this clause's sentence.

Note (separate pre-existing debt, outside this clause's sentence): the three real
routes `POST /api/v1/tenant/exports`, `GET /api/v1/tenant/exports/{}` and
`POST /api/v1/tenant/exports/{}/expire` are still listed in
`contracts/openapi/missing-routes.txt` (lines 333, 116 and 334) and have no path
in `contracts/openapi.yaml` or the regenerated client. Publishing that contract
and regenerating the client is already tracked elsewhere; the tests drive the
real controller routes directly. The stale, disabled GAP test at
`tenancy/clauses/IsolationAcrossSurfacesTest:493` targets a different, never-built
fake route (`/api/v1/tenant-exports`) with a request-body `scope` field and was
intentionally not mirrored or enabled — this API derives scope from
`TenantContext`, not from the request body.
