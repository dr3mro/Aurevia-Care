- [x] DEFECT-FIX-BATCH · Fix unrelated CI defects found in the 2026-11-27 sweep
  - agent: orca
  - attempts: 1
  - pr: #1799
  - scope: scripts/swarm_gate.py, scripts/tests/test_swarm_gate.py, scripts/tests/test_notes_files.py, tasks/01347000-DEFECT-FIX-BATCH.md
  - evidence:
    Test evidence: `python3 -I -m unittest discover -s scripts/tests -p test_swarm_gate.py` -> `Ran 79 tests ... OK`; `COVERAGE_BASE=1e6e33be REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> `Ran 2431 tests ... OK`, `python coverage: every changed script line is covered`. Tested revision: 1e6e33be (HEAD when checks ran).
    Coverage: trusted repair classification, autofix-label repair, untrusted repair fail-closed, worker branch precedence, protected-path exemption, archive test shape.
    Review verdicts: code-reviewer GO, coverage-auditor GO, devsecops GO.
    Fix branch fix/pr-classification; PR to be recorded after push.
  - details:
    At the end of the current queue (tasks 01343000, 01345000, 01346000, 01344000),
    open one fix branch and fix only the items marked `DEFERRED` below. Items marked
    `IN CURRENT TASK` or `FIXED` are excluded so the batch does not duplicate work.
    Evidence source for every row: tool (`gh run view --log-failed`, `gh api`, git/file
    reads) or read-only reviewer; no simulated-user or human feedback.

    Defect register (2026-11-27, runs since 20:00 EST plus earlier sweeps):
    1. DEFERRED — Swarm gate advisory misclassification: a `FIX-` repair PR (#1783)
       was classified as a worker and rejected for editing protected `docs/swarm/*`;
       run 45931809131. See tasks/01339000. Fix classification/routing without
       weakening protected-path policy.
    2. IN CURRENT TASK 01343000 — merged-PR evidence truncated at the 500-row window
       (run 45945053333 and 45951351880); paginated fetch implemented.
    3. IN CURRENT TASK 01343000 — Pages attempt-2 artifact metadata race
       (run 45933603710); bounded poll implemented.
    4. IN CURRENT TASK 01345000 — `test_notes_files.py` rejected policy-mandated
       `.agents/notes/archive/*.zip` (runs 45960317263, 45960810123).
    5. IN CURRENT TASK 01346000 — issue-monitor triage inputs placed inside the
       checkout so `require_clean_worktree` refused to start (eight runs 45961293757
       … 45961460090); CR-marker neutralisation gap fixed in the same branch.
    6. IN CURRENT TASK 01344000 — integrator non-fast-forward push race
       (run 45938117633 job 140358967676); bounded agent-assisted recovery.
    7. FIXED — `scripts/gen-opencode.py` executable without shebang
       (runs 45933991877, 45933603652); commit d44b4fe7.
    8. FIXED — `docs/swarm/QA-SOURCE-04.md` app-relative script citation
       (runs 45920446109, 45920447774, 45920883249 and three older runs);
       commit 4198dec8 plus checker hardening 732e4fa7.
    9. NOT A DEFECT — Swarm gate `tested_revision` on PR head `abf0437f`
       (run 45920883029): the PR head moved; fail-closed and superseded by a newer run.
    10. NOT A DEFECT — Delivery gate skipped-job cascade: correct aggregation after
        an upstream tooling failure; root reporting already tested.
    11. FIXED in 01343000 — Pages poll `grep -q .` could accept a partial line;
        replaced with a captured `--jq` result and an explicit non-empty check.
    12. EVIDENCE GAP — pagination is proven by mocked subprocess tests, not a live
        multi-page `gh api` traversal; confirm on the next exact-SHA Delivery run.
