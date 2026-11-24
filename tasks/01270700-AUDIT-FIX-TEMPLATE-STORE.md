- [-] AUDIT-FIX-TEMPLATE-STORE · CLN-04 template store is not durable and bypasses tenant GUC
  - agent: orca
  - requirements: ARC-02, CLN-04
  - scope: apps/api/src/main/java/com/aurevia/clinical/templates/JdbcTemplateStore.java, apps/api/src/main/java/com/aurevia/clinical/templates/InMemoryTemplateStore.java, apps/api/src/main/java/com/aurevia/config, apps/api/src/test, scripts/wiring-baseline.json, tasks/01270700-AUDIT-FIX-TEMPLATE-STORE.md
  - details:
    JdbcTemplateStore calls NamedParameterJdbcTemplate directly without a
    TransactionTemplate, so forced RLS GUCs are never bound in production;
    InMemoryTemplateStore lives in src/main and is the wired implementation.
    Inject a TransactionTemplate, wire the JDBC store into TemplateService,
    move the in-memory fake to src/test, add a real-PostgreSQL integration test,
    and remove the resolved wiring-baseline exemptions. Preserve 100% coverage.

  - agent: orca