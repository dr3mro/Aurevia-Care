- [ ] OPS-RED-MAIN-DETECTION · Retain red-main priority across cancelled runs
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py, docs/SWARM.md
  - evidence: Synthetic regressions cover exhausted run listings, newer recovery, and an already-open non-Delivery repair. Python tooling and changed-script coverage passed locally. GitHub workflow verification pending.
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Start from the declared scope and establish the real entry point before planning edits.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    Route protected tooling edits through a maintainer.

    Detect the latest definitive Delivery verdict even beyond the recent-run window. Create or continue the Delivery repair before other workflow repairs until a newer successful Delivery clears it.
