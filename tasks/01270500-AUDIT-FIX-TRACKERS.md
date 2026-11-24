- [-] AUDIT-FIX-TRACKERS · Reconcile trackers with code and CI evidence
  - agent: orca
  - requirements: OPS-06
  - scope: .opencode/config/WORKSPACE.md, .opencode/config/MEMORY.md, .opencode/config/BUGS.md, .opencode/config/PROJECT.md, tasks/01270500-AUDIT-FIX-TRACKERS.md
  - details:
    Fix three conflicting sync SHAs, the wrong active-story ID, stale [-] tasks,
    a [x] task without evidence, BUGS rows calling fixed defects open and "fixed"
    rows without commit or CI evidence, and stale .github/swarm and setup-java
    path/version claims. Keep notes short and cite exact paths and evidence
    source (tool, reviewer, simulated user or human).

  - agent: orca