- [-] AUDIT-FIX-SWARM-ISOLATION · Candidate code executes in jobs holding write tokens
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

  - agent: orca