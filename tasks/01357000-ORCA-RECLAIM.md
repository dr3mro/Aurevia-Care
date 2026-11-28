- [x] ORCA-RECLAIM · Release stale interactive orca claims with completed evidence
  - agent: orca
  - attempts: 0
  - pr: #1835
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py, tasks/01357000-ORCA-RECLAIM.md
  - details:
    Issue #1816 reported that `reclaim()` releases only claims whose agent starts
    with `swarm` or `opencode` (scripts/swarm.py:3133-3135), so `[-] agent: orca`
    entries with completed evidence hold their scope indefinitely while the queue
    waits. `live_workers()` has no marker for an interactive ORCA, so an unguarded
    release could race a running task.
    Bounded fix: treat an `orca` claim as reclaimable only when the task entry has
    a non-empty `evidence:` field or a matched merged PR. Otherwise leave it held.
    Keep `swarm`/`opencode`, `live`, open-PR and `[!]` behavior unchanged.
    Tests first in scripts/tests/test_swarm.py beside the existing reclaim tests:
    orca+evidence -> todo; orca+merged PR -> done with #n; orca+open PR -> taken;
    orca+no evidence -> taken; orca+live -> taken; incomplete orca -> unchanged.
    Review verdicts: code-reviewer GO, coverage-auditor GO, devsecops GO.
    Gates: full unittest, changed-line coverage, check-constraints/docs/secrets,
    reviewers, exact-SHA CI. No workflow, permission or concurrency change.
