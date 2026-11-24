- [-] AUDIT-FIX-DEPENDABOT-GATE · Dependabot approval gate fails open
  - agent: orca
  - requirements: OPS-06
  - scope: .github/workflows/dependabot-auto-merge.yml, scripts/tests/test_workflow_concurrency.py, tasks/01270000-AUDIT-FIX-DEPENDABOT-GATE.md
  - details:
    The "Require an explicit human approval" step prints a warning and exits 0
    when dependency-approved is absent; the next step enables auto-merge
    unconditionally. Fix so a missing label fails the job with exit 1. Add a
    regression test that reads the workflow and asserts the fail-closed branch
    and the conditional/explicit guard on the merge step. No secret, token or
    patient data in fixtures.

  - agent: orca- [-] AUDIT-FIX-DELIVERY-COVERAGE · Required Delivery check skips frontend and Python coverage
  - agent: orca
  - requirements: OPS-06
  - scope: .github/workflows/delivery.yml, scripts/frontend-check.sh, scripts/tests/test_workflow_concurrency.py, tasks/01270100-AUDIT-FIX-DELIVERY-COVERAGE.md
  - details:
    Delivery runs plain npm test and unittest discover, so the 100% frontend
    and changed-script coverage gates never run in the sole required check.
    Wire the canonical module check.sh scripts and scripts/python-coverage.sh
    into Delivery, keep the docs claim truthful, and cover the wiring with a
    regression test. Do not lower any threshold or add an exclusion.

  - agent: orca- [-] AUDIT-FIX-SWARM-ISOLATION · Candidate code executes in jobs holding write tokens
  - agent: orca
  - requirements: OPS-06
  - scope: .github/workflows/swarm-integrator.yml, .github/workflows/swarm-worker.yml, .github/workflows/fix-pr-gate.yml, scripts/swarm.py, scripts/tests/test_swarm.py, tasks/01270200-AUDIT-FIX-SWARM-ISOLATION.md
  - details:
    Apply the reviewed candidate-validation isolation split described in
    docs/operations/workflow-changes/candidate-validation-isolation.md:
    validation jobs hold no write token or secret; publication jobs run trusted
    scripts with SHA binding between validation and handoff. Keep all existing
    queue/concurrency and required-check semantics. Cover changed Python with
    tests and keep 100% coverage.

  - agent: orca- [-] AUDIT-FIX-DB-AUDIT · Audit append-only test is a false positive and actor derivation lacks tenant predicate
  - agent: orca
  - requirements: OPS-02, ARC-02
  - scope: db/check.sh, db/migrations/V3__audit_chain.sql, scripts/tests/test_check_migrations.py, tasks/01270300-AUDIT-FIX-DB-AUDIT.md
  - details:
    db/check.sh probes audit UPDATE only as the app role, which already lacks
    the privilege, so the trigger is never exercised; include audit_event in
    the owner-role immutability loop. Add the tenant predicate to the actor
    lookup in V3. Use a new Flyway version for the SQL change rather than
    editing a released migration, and cover the gate script change with tests.

  - agent: orca- [-] AUDIT-FIX-STALE-GAPS · Disabled gap tests no longer match implemented capability
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

  - agent: orca- [-] AUDIT-FIX-TRACKERS · Reconcile trackers with code and CI evidence
  - agent: orca
  - requirements: OPS-06
  - scope: .opencode/config/WORKSPACE.md, .opencode/config/MEMORY.md, .opencode/config/BUGS.md, .opencode/config/PROJECT.md, tasks/01270500-AUDIT-FIX-TRACKERS.md
  - details:
    Fix three conflicting sync SHAs, the wrong active-story ID, stale [-] tasks,
    a [x] task without evidence, BUGS rows calling fixed defects open and "fixed"
    rows without commit or CI evidence, and stale .github/swarm and setup-java
    path/version claims. Keep notes short and cite exact paths and evidence
    source (tool, reviewer, simulated user or human).

  - agent: orca- [-] AUDIT-FIX-VERDICTS · Verdict parser treats "not resolved" as resolved
  - agent: orca
  - requirements: OPS-06
  - scope: scripts/check-verdicts.py, scripts/tests/test_check_verdicts.py, tasks/01270600-AUDIT-FIX-VERDICTS.md
  - details:
    The regex (?<!un)\bresolved\b ignores only "unresolved" but matches "not
    resolved", so a finding line can be silently dropped. Fail closed on any
    negated resolved token and cover both sides with unit tests.

  - agent: orca- [-] AUDIT-FIX-TEMPLATE-STORE · CLN-04 template store is not durable and bypasses tenant GUC
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

  - agent: orca- [-] AUDIT-FIX-FRONTEND-TIMEOUTS · Staff web spec tests time out under full-suite load
  - agent: orca
  - requirements: OPS-06
  - scope: apps/staff-web, tasks/01270800-AUDIT-FIX-FRONTEND-TIMEOUTS.md
  - details:
    The canonical staff-web check failed with three TestTimeoutError (5000ms)
    failures in tests/spec/platform/verify-state-component-test.test.ts and a
    booking-draft test when the full 278-file suite ran. Give the slow async
    component tests explicit per-test timeouts, keep 100% coverage, and prove
    with a full bash apps/staff-web/check.sh run. Do not lower a threshold or
    skip a test.
