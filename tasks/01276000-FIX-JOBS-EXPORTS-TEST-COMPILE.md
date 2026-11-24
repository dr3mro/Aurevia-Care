- [-] FIX-JOBS-EXPORTS-TEST-COMPILE · Restore Java test compilation for the ARC-02 tenant clause suite
  - agent: orca
  - requirements: ARC-02, OPS-06
  - scope: apps/api/src/test/java/com/aurevia/tenancy/clauses/JobsExportsSearchAttachmentsIntegrationsApplySameTest.java, tasks/01276000-FIX-JOBS-EXPORTS-TEST-COMPILE.md
  - depends: none
  - details:
    PR 1613 Delivery run 45337601399 failed Java and database verification
    with `cannot find symbol: variable status` at
    `JobsExportsSearchAttachmentsIntegrationsApplySameTest.java:397,419`.
    The two refusal helpers call `status.setRollbackOnly()` inside a
    `Supplier` lambda passed to `as()`, which only binds TenantContext and has
    no transaction status in scope; the surrounding comments also claim an
    outer transaction that does not exist. Remove the invalid statements and
    correct the comments, then prove test compilation and the targeted suite.
