- [-] AUDIT-FIX-STALE-GAPS · Disabled gap tests no longer match implemented capability
  - agent: orca
  - requirements: OPS-07, CLN-01
  - scope: apps/api/src/test/java/com/aurevia/tenancy/clauses/IsolationAcrossSurfacesTest.java, tasks/01270400-AUDIT-FIX-STALE-GAPS.md
  - details:
    The OPS-07 tenant export gap test posts to /api/v1/tenant-exports while the
    implemented route is /api/v1/tenant/exports with migration V89 and OpenAPI
    entries. Retarget and enable it or document precisely why it stays
    disabled. Leave the three genuine gaps (CLN-03 chart sections, two-identifier
    confirmation, signed artifacts) disabled with accurate reasons and tracked
    stories.

  - agent: orca