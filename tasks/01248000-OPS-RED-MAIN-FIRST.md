- [ ] OPS-RED-MAIN-FIRST · Pause unrelated swarm dispatch while main is red
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py, docs/SWARM.md
  - evidence: Local tool evidence. New worker, PR-flow, integration and debugger regressions pass. REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh passed 1195 tests with every changed script line covered. GitHub workflow verification pending.
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `docs/SWARM.md`, `scripts/swarm.py`.
    Start with the existing assertions in `scripts/tests/test_swarm.py` and add a failing case for the acceptance gap.
    Route protected tooling edits through a maintainer.

    While Delivery on main is red, dispatch only repair work. Give the repair access to the next available slot even when unrelated work already holds overlapping scopes. Keep normal scheduling after Delivery recovers.
