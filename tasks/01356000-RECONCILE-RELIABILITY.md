- [x] RECONCILE-RELIABILITY · Unblock and harden task reconciliation after post-21:00EST failures
  - agent: orca
  - attempts: 0
  - pr: #1829
  - requirements: OPS-06
  - scope: tasks/00095000-AUTH-WIRE-02.md, tasks/01270000-AUDIT-FIX-DEPENDABOT-GATE.md, scripts/swarm.py, scripts/swarm_gate.py, scripts/check.sh, scripts/quick-check.sh, scripts/tests/test_swarm.py, scripts/tests/test_swarm_schedule_coverage.py, scripts/tests/test_swarm_gate.py, .github/workflows/swarm-integrator.yml, tasks/01356000-RECONCILE-RELIABILITY.md
  - details:
    Owner directive: fix all failure classes observed since 21:00 EST Nov 27.
    Seven runs failed at `swarm.py reconcile-tasks` because
    tasks/01270000-AUDIT-FIX-DEPENDABOT-GATE.md has a prose `pr:` field that
    fails `re.fullmatch("#?[1-9][0-9]*")` at scripts/swarm.py:1558. One run failed
    on a transient `gh api --paginate` read in `paginated_merged_pull_requests`.

    Bounded plan:
    1. Remove the prose `pr:` line from the Dependabot task and fold the statement
       into `details:`/`evidence:`. No merged PR names that task, so do not invent
       a number. Prove `python3 -I scripts/swarm.py reconcile-tasks --dir tasks`
       exits 0.
    2. Resolve the AUTH-WIRE-02 ambiguity without guessing: all four merged PRs
       (1666/1673/1683/1684) are unfinished drafts and the task is open with 43
       attempts. Hold it as `[!]` with the reason and next action under `details:`
       so it neither dispatches nor produces a warning; an owner can later name
       the authoritative PR or close it. No `pr:` field is fabricated.
    3. Add `swarm_gate.py validate-tasks --dir tasks` that catches a non-numeric or
       repeated `pr:` field and a duplicate task id before a wave publishes
       branches. Wire it into scripts/check.sh, scripts/quick-check.sh and as a
       pre-step before swarm-integrator.yml reconcile-tasks. Exit 0 valid, 2 on a
       malformed tracker; offline and advisory like validate-note.
    4. Reuse the `gh()` transient predicate (scripts/swarm.py:209-213) in
       `paginated_merged_pull_requests` with at most three attempts, keep
       `child_environment(read_only=True)`, surface stderr, and still raise
       ReconciliationError on persistent failure. Patch time.sleep in tests.
    5. Keep fail-closed semantics: no quarantine of malformed tasks, no weakened
       threshold, no cancelled PR-head runs, no write-token in agent steps.

    Required plan-review corrections (adopted):
    - Clean baseline established before wiring: tasks 01270000 and 01270900 had
      prose pr: fields, tasks/00095000 AUTH-WIRE-02 was moved to [!] with a
      reason, and tasks/01278000 had an embedded `- [ ] Independent ...` line
      that reconcile's re.search read as a task id. All three data defects were
      fixed; `python3 -I scripts/swarm.py reconcile-tasks --dir tasks` now exits 0.
      A tree scan found 33 malformed pr: fields on already-done tasks; they are
      outside reconcile's active-task scope and must not make the validator fail.
    - Parser contract: mirror reconcile exactly. Only the first `^- \[[- ]\]`
      line of a file is the task; `[x]`/`[!]` files are skipped unless they
      contain a later open/taken checkbox, which is reported as a latent false
      id. pr: fields are checked only on active tasks, with
      `#?[1-9][0-9]*` and at most one field; duplicate ids are reported.
    - Risk order: validate-tasks standalone on the clean tree, then targeted
      red/green tests, then reconcile retry, then broad three-gate wiring.
    - Test seam kept: paginated_merged_pull_requests still calls
      subprocess.check_output with the same argv, so existing patching works;
      only retry, stderr capture and printing are added. This is an intentional
      behavior change, not a weakened test.
    - Workflow placement corrected: reconcile runs after publish, so a step at
      reconcile time cannot guard publication. validate-tasks goes into the
      publish job immediately before "Push only the exact validated commit
      SHAs"; the reconcile step keeps its own copy as defence in depth. No new
      permission, no token in an agent step, per-run groups untouched.
    - At most three attempts means three calls total (attempts 0,1,2), distinct
      from gh()'s three retries.

    Tests first in scripts/tests/test_swarm.py, test_swarm_schedule_coverage.py
    and test_swarm_gate.py; full unittest suite, changed-line coverage, actionlint,
    shellcheck, policy checks, reviewers and exact-SHA CI before marking done.
    Review verdicts: code-reviewer GO, coverage-auditor GO, devsecops GO, software-architect GO.
