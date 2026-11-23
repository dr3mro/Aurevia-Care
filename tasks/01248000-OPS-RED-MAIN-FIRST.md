- [ ] OPS-RED-MAIN-FIRST · Pause unrelated swarm dispatch while main is red
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py, docs/SWARM.md
  - evidence: Local tool evidence. New worker, PR-flow, integration and debugger regressions pass. REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh passed 1195 tests with every changed script line covered. GitHub workflow verification pending.
  - details:
    While Delivery on main is red, dispatch only repair work. Give the repair access to the next available slot even when unrelated work already holds overlapping scopes. Keep normal scheduling after Delivery recovers.
