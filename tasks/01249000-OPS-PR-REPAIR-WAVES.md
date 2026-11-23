- [ ] OPS-PR-REPAIR-WAVES · Batch PR test repairs and hand off Delivery evidence
  - requirements: OPS-06
  - scope: .github/workflows/swarm-worker.yml, scripts/swarm.py, scripts/agent-run.sh, scripts/tests, .opencode/skills, docs/SWARM.md
  - evidence: Local regressions verify one fix wave, one debugger dispatch, exact-head evidence and an older-branch conflict resolution. REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh passed 1201 tests with every changed script line covered. GitHub verification pending.
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `scripts/tests/helpers.py`.
    Start with the existing assertions in `scripts/tests/test_swarm_delivery_state.py`, `scripts/tests/test_swarm.py` and add a failing case for the acceptance gap.
    Route protected tooling edits through a maintainer.

    Queue straightforward Delivery test failures in one repair wave, carry exact-head diagnostic evidence into the next fix worker, and load a dedicated skill for merge conflicts. Preserve bounded escalation and red-main priority.
