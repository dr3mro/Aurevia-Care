- [ ] AUDIT-FIX-INTEGRATOR-ISOLATION · Integrator validates candidate code in a write-token job
  - requirements: OPS-06
  - scope: .github/workflows/swarm-integrator.yml, scripts/swarm.py, scripts/tests/test_swarm.py
  - details:
    The `integrate` job merges PR branches and runs the full gate on candidate
    code while holding a write-capable `GH_TOKEN`; the NVD key has been removed
    from that job, but the token exposure remains. The reviewed split in
    docs/operations/workflow-changes/candidate-validation-isolation.md is still
    proposed only. Closing it needs a validate/publish job split plus new
    `integrate-validate` and `integrate-publish` subcommands in scripts/swarm.py
    with exact-SHA binding, 100% changed-branch coverage and a GitHub walk of
    the conflict-only batch. The worker-side split and fix-PR SHA binding are
    already in place; this is the remaining Critical item from the audit.
    Evidence: devsecops reviewer finding, read-only inspection of integrator
    workflow and scripts/swarm.py, 2026-11-24.
