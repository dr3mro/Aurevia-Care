- [ ] FIX-JOBS-EXPORTS-TEST-COMPILE · Restore Java test compilation for the ARC-02 tenant clause suite
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
  - evidence:
    Tool (red, CI): Delivery run 45337601399, Java and database verification
    job 138258325642, `[ERROR] ... JobsExportsSearchAttachmentsIntegrationsApplySameTest.java:[397,17]
    cannot find symbol: variable status` (and line 419). Tool (green):
    `mvn -q -f apps/api/pom.xml -DskipTests test-compile` → exit 0.
    Tool: `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration
    -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test` → exit 0;
    Surefire report `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.
    No production Java changed; only the broken helper comments and two
    out-of-scope `status.setRollbackOnly()` calls were removed from a test.
    Evidence source: executed tool commands and GitHub Actions job log;
    no reviewer verdict yet.
    Tool: `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q`
    → `Ran 2 tests in 0.172s / OK`.
    Tool (final tree): upstream commit on `main` landed the equivalent removal
    of the two out-of-scope `status.setRollbackOnly()` calls, so
    `git diff --name-only origin/main...HEAD` → only this task file and
    `git diff origin/main...HEAD -- apps/api/src/test/.../JobsExports...Test.java`
    → empty. No Java delta, threshold, pom or migration change remains on the
    branch. The compile fix is already on `main`; this entry records the
    diagnosis and verification only.
    Reviewers: aurevia-senior-java-build GO; aurevia-code-reviewer NO-GO on a
    missing exact Java-conventions command, then GO after it was added above.
  - ci-status: the same compile fix landed on main independently before
    this branch was refreshed; the exact-SHA Delivery run for main is still
    queued behind the runner backlog (see task 01273000).
