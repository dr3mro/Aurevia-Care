- [ ] OPS-PR-REPAIR-WAVES · Batch PR test repairs and hand off Delivery evidence
  - requirements: OPS-06
  - scope: .github/workflows/swarm-worker.yml, scripts/swarm.py, scripts/agent-run.sh, scripts/tests, .opencode/skills, docs/SWARM.md
  - evidence: Local regressions verify one fix wave, one debugger dispatch, exact-head evidence and an older-branch conflict resolution. REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh passed 1201 tests with every changed script line covered. GitHub verification pending.
  - details:
    Queue straightforward Delivery test failures in one repair wave, carry exact-head diagnostic evidence into the next fix worker, and load a dedicated skill for merge conflicts. Preserve bounded escalation and red-main priority.
