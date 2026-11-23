- [ ] OPS-SWARM-PRIORITY-CAPACITY · Fill the swarm by lane priority
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests, .github/workflows, docs/SWARM.md
  - evidence: Local tooling verified 20 distinct task identities, conflict-aware PR fixes, independent integration dispatch and scheduled-agent admission. All 1304 Python tests and changed-script coverage passed. actionlint and docs checks passed. GitHub validation pending.
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `docs/SWARM.md`, `scripts/swarm.py`.
    Start with the existing assertions in `scripts/tests/test_swarm.py`, `scripts/tests/test_swarm_drafts.py` and add a failing case for the acceptance gap.
    Route protected tooling edits through a maintainer.

    Admit red-main repairs first, then PR fixes, PR integration, backlog tasks and scheduled daily agents. Sustain up to 20 eligible workers without bypassing dependency, overlap or runner guards.
