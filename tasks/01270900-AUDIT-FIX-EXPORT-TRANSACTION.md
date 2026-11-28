- [ ] AUDIT-FIX-EXPORT-TRANSACTION · Tenant export HTTP path returns 500 without a guarded transaction
  - requirements: OPS-07, ARC-02
  - scope: apps/api/src/main/java/com/aurevia/tenant/export/TenantExportService.java, apps/api/src/test/java/com/aurevia/tenant/export, apps/api/src/test/java/com/aurevia/configuration/clauses/ExportsUseExplicitScopeExpiryAuditTest.java, apps/api/src/test/java/com/aurevia/tenancy/clauses/IsolationAcrossSurfacesTest.java, tasks/01270900-AUDIT-FIX-EXPORT-TRANSACTION.md
  - details:
    Found while enabling the stale OPS-07 gap test: GET /api/v1/tenant/exports/{id}
    returned 500 because the service performed JDBC permission reads and audit
    appends outside a TransactionTemplate, so forced RLS had no GUC and denied
    the row. Inject the shared TransactionTemplate and wrap start, download and
    expire. Cover the HTTP boundary in IsolationAcrossSurfacesTest and keep the
    service unit tests green.

  - evidence:
    Tool: mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=IsolationAcrossSurfacesTest test -> Tests run: 7, Failures: 0, Errors: 0. Tool: ... -Dtest=ProvideVersionedSoapOperativeObGynPerinatalTest,FacilityIsolationTest test -> Tests run: 19, Failures: 0, Errors: 0; Flyway applied V98 (61 migrations, now v98). Tool: unit suites TenantExportServiceTest, ExportsUseExplicitScopeExpiryAuditTest and JdbcTemplateStoreTest all green. Entry point: GET /api/v1/tenant/exports/{jobId} through ExportController; the rewritten tenantExportSurfaceIsTenantScopedAndFailsClosed proves 403/401/422/404 at HTTP level. Evidence source: executed tool commands and a real PostgreSQL integration run; no simulated user.
    Delivery was by local scoped commits on main; GitHub delivery was not authorized in this request.
