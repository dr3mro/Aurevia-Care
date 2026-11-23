- [ ] OPS-RED-MAIN-DETECTION · Retain red-main priority across cancelled runs
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py, docs/SWARM.md
  - evidence: Synthetic regressions cover exhausted run listings, newer recovery, and an already-open non-Delivery repair. Python tooling and changed-script coverage passed locally. GitHub workflow verification pending.
  - details:
    Detect the latest definitive Delivery verdict even beyond the recent-run window. Create or continue the Delivery repair before other workflow repairs until a newer successful Delivery clears it.
