- [-] PR-REFRESH-MERGE · Refresh new PRs by merging main so a worker note's tested revision stays in the branch history
  - agent: orca
  - attempts: 0
  - requirements: OPS-06
  - scope: .github/workflows/pr-rebase.yml, scripts/tests/test_pr_refresh_workflow.py, docs/SWARM.md, tasks/01362000-PR-REFRESH-MERGE.md
  - depends: []
  - details:
    Owner chose the merge option on 2026-11-28 after the open-PR rebind repair.
    Acceptance: the `opened`-only refresh workflow merges the current default
    branch into the PR branch and pushes without force; the old head stays an
    ancestor so `compare(tested, head)` remains `ahead` with `behind_by: 0`;
    already-current branches and merge conflicts are still handled without
    force-push; least-privilege permissions, concurrency and the trusted
    base/head split of the gate are unchanged. Prove the contract with a red
    test first, then actionlint, shellcheck, the Python unit suite and changed
    script-line coverage. No gate rule, threshold or exclusion may change.
