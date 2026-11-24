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

  - agent: orca