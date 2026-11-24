- [-] AUDIT-FIX-EXPORT-TRANSACTION · Tenant export HTTP path returns 500 without a guarded transaction
  - agent: orca
  - requirements: OPS-07, ARC-02
  - scope: apps/api/src/main/java/com/aurevia/tenant/export/TenantExportService.java, apps/api/src/test/java/com/aurevia/tenant/export, apps/api/src/test/java/com/aurevia/configuration/clauses/ExportsUseExplicitScopeExpiryAuditTest.java, apps/api/src/test/java/com/aurevia/tenancy/clauses/IsolationAcrossSurfacesTest.java, tasks/01270900-AUDIT-FIX-EXPORT-TRANSACTION.md
  - details:
    Found while enabling the stale OPS-07 gap test: GET /api/v1/tenant/exports/{id}
    returned 500 because the service performed JDBC permission reads and audit
    appends outside a TransactionTemplate, so forced RLS had no GUC and denied
    the row. Inject the shared TransactionTemplate and wrap start, download and
    expire. Cover the HTTP boundary in IsolationAcrossSurfacesTest and keep the
    service unit tests green.
