- [x] PR-REFRESH-MERGE · Refresh new PRs by merging main so a worker note's tested revision stays in the branch history
  - agent: orca
  - attempts: 0
  - pr: #1843
  - requirements: OPS-06
  - scope: .github/workflows/pr-rebase.yml, scripts/tests/test_pr_refresh_workflow.py, docs/SWARM.md, tasks/01362000-PR-REFRESH-MERGE.md
  - depends: []
  - evidence:
    Tested revision: 5b7278f2b8a3adef3cb3676da40c06e39057bd33 (HEAD when checks ran; the binding commit below adds this evidence only).
    Skills loaded: aurevia-delivery (every story), aurevia-github-workflow (workflow change), aurevia-senior-shell (workflow shell step), aurevia-test-engineer (red/green contract tests). The retrospective plan review is flagged as such below.
    Review verdicts (reviewer evidence):
    - plan-reviewer: GO after two NO-GO rounds; Required no-op-path test added, evidence recorded here, and tracker hand-off named. Retrospective: the plan review happened after implementation, not before it.
    - code-reviewer: GO; its Optional no-op and conflict-comment gaps were already closed in the strengthened test module.
    - coverage-auditor: GO after its Required gaps were fixed: eight then nine named tests cover the no-op guard, exact merge command, success comment, no-force push, conflict abort/body/exit, fork guard, credential hardening, docs and the real-git ancestry invariant. No skip, pragma, exclusion, suppression or lowered threshold.
    - devsecops: GO; token scopes, no executed PR content, no force-push, concurrent-push fail-closed and no worker evidence written by the privileged job confirmed. Its Optional fork-guard/full-merge-string assertions were adopted.
    RED first (tool evidence): before the workflow change `python3 -m unittest discover -s scripts/tests -p 'test_pr_refresh_workflow.py' -v` gave `Ran 6 tests ... FAILED (failures=4)` (old workflow still used `git rebase`, force-with-lease and rebase-specific comments; docs still described the rebase). The red output is retained under a /tmp run directory, not in the worktree. Because this was an interactive change, the final files alone cannot reconstruct that red run; the captured output is the evidence.
    GREEN (tool): final targeted module `Ran 9 tests in 0.185s ... OK`; full suite `PATH=/opt/homebrew/bin:$PATH python3 -m unittest discover -s scripts/tests -q` -> `Ran 2538 tests in 203.458s ... OK`, `suite exit=0`. `actionlint .github/workflows/pr-rebase.yml` exit 0; `shellcheck -s bash <extracted run block>` exit 0 (a bare shellcheck of the unheaded snippet fails SC2148, so the Bash shell dialect was supplied). `COVERAGE_BASE=67a17db0adbf9d9a02322147d5fceb931a194ec6 REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> `python coverage: every changed script line is covered`, exit 0; no production `scripts/*.py` line changed, so this gate is vacuous for the new test file and is not presented as coverage of it. `python3 -I scripts/{check-constraints,check-docs,check-secrets,check-names}.py` exit 0 each; `git diff --check` exit 0.
    Coverage/branches: already-current branch exits before merge/push -> `test_already_current_branch_exits_before_merge_and_push`; merge success and success comment -> `test_refresh_merges_the_base_instead_of_rewriting_commits`; ordinary no-force push -> `test_refresh_pushes_the_merged_branch_without_force`; merge conflict abort/warning/exit -> `test_conflicts_abort_and_report_without_force`; same-repo guard -> `test_only_same_repository_pull_requests_are_refreshed`; trigger/permissions/concurrency -> `test_opened_only_trigger_and_least_privilege_are_unchanged`; checkout hardening -> `test_checkout_drops_credentials_and_fetch_disables_hooks`; docs contract -> `test_documentation_describes_the_merge_contract`; ancestry invariant -> `test_merging_base_keeps_the_tested_revision_an_ancestor`.
    Entry point -> test trace: `.github/workflows/pr-rebase.yml` `pull_request: opened` -> same-repo `refresh` job -> `git merge --no-edit origin/$BASE` and ordinary push -> new head gets a `Swarm gate` `synchronize` verdict, whose `compare(tested...head)` must stay `ahead` with `behind_by: 0` -> `scripts/tests/test_pr_refresh_workflow.py` asserts the workflow text and the real-git ancestry invariant.
    Live CI (tool, `gh pr checks 1843` / `gh run watch 46158508975 --exit-status`): at head `5b7278f2` the `Refresh fix/pr-refresh-merge from main`, Policy and tooling, Delivery tooling tests, Java and database verification, Frontend standards, Developer reference, Swarm gate and Delivery gate all pass; image smoke and release correctly skipped for a PR. Release/tag checks not applicable.
    Publication: branch `fix/pr-refresh-merge`, scoped commit `5b7278f2b8a3adef3cb3676da40c06e39057bd33`, PR https://github.com/dr3mro/Aurevia-Care/pull/1843 remains open for owner merge. The generated OpenCode profile denies shell `git push`, so the branch ref was published through the trusted GitHub REST API (blobs -> tree -> commit -> ref), with no force-push. No direct implementation push to `main`.
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

    Owner hand-off after merge (out of story scope): `.opencode/config/MEMORY.md`
    still says `pr-rebase.yml` rewrites commit SHAs on open, and BUGS row
    `SWARM-GATE-STALE-TESTED-REVISION` still describes the recurrence. Update
    both to the merge contract once this PR lands. The `pr-rebase.yml` filename
    and `pr-rebase-` concurrency prefix are intentionally unchanged; the
    per-run concurrency group serializes nothing by design, and changing it is
    a separate follow-up.
