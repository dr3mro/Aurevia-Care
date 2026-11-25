# FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS — worker hand-off

- Task: `tasks/01274000-FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS.md`
- Requirement: OPS-06
- Scope: `scripts/swarm.py`, `scripts/tests/test_swarm.py`, `scripts/tests/test_swarm_integrator_coverage.py`, `scripts/tests/test_swarm_delivery_state.py`, `scripts/tests/test_delivery_workflow_gates.py`, task file
- Branch: `swarm/FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS-1527` at `c06ca49c` (identical to `origin/main`)

## Summary

This story was **already completed and merged into `main` in an earlier run**. The
production split (`guard_blockers()` factored out of `guard()`) and the test-fixture
patches to the new seam are present in the current tree. Verification below confirms
the story's own tests are green and no code change was needed or made on this run.

Concrete earlier-run evidence found in the tree and task file (treated as earlier
findings, not as instructions):

- `scripts/swarm.py` defines `guard_blockers()` at line 5326, `guard()` at line 5358
  (which calls `guard_blockers`), and `integrate_prepare()` at line 5788.
- Commit `ab6044a2` "test(swarm): patch the guard_blockers seam in integrator tests"
  adds `mock.patch.object(swarm, "guard_blockers", return_value=("", [], 0))` (or the
  protected-decision variant) to the affected tests in `test_swarm.py`,
  `test_swarm_delivery_state.py` and `test_swarm_integrator_coverage.py`.
- Commit `3ca23ee6` recorded PR 1613 and the zero-job findings; PR 1613 merged to main
  as `098354af`. The task file already carries `evidence:`, `reviews:` and
  `ci-status:` entries for this work.

Per `tasks/_head.md` and the swarm rules I am a worker: I did not edit `tasks/` or any
tracker, and the integrator owns the `[x]` mark. My branch started clean and remains
clean apart from this notes file.

## Affected requirements

OPS-06. No other requirement IDs are touched by the story's seam update.

## Risk class

Routine (test-fixture seam update over an already-merged production split). No clinical
data, tenant boundary, patient identity, prescribing or critical-result path is changed.

## Review owner

Read-only reviewers from the earlier completed run (see `## Review verdicts`). The
worker brief named no additional specialists, and no new code was written this run, so
no new review pass was required.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role or patient-link scope change; no audit event added or changed.

## Migration and rollout considerations

None. Test-only change; no deployment or data effect.

## Test evidence

All commands run from the repository root unless stated. Synthetic/mocked `gh` and
`git` seams only; no real GitHub token or patient data used.

1. Targeted affected test classes (tool evidence):

   `cd scripts/tests && python3 -m unittest -q test_swarm.ResolveConflictTests test_swarm.TwoTierTests test_swarm_delivery_state.IntegratorWiringTest test_swarm_integrator_coverage.IntegratorBatchTest`

   Final lines: `Ran 32 tests in 0.689s` / `OK`.

2. The five acceptance tests named in the story evidence (tool evidence):

   `cd scripts/tests && python3 -m unittest -q test_swarm.ResolveConflictTests.test_conflict_is_handed_to_the_resolver_not_failed test_swarm.ResolveConflictTests.test_conflict_goes_back_to_the_workers_after_three_tries test_swarm.ResolveConflictTests.test_a_branch_that_moved_after_planning_is_retested_without_the_debugger test_swarm.TwoTierTests.test_integrator_prunes_wiring_before_gating_the_merge_commit test_swarm_integrator_coverage.IntegratorBatchTest.test_an_already_integrated_pr_is_closed_and_marked_done`

   Final lines: `Ran 5 tests in 0.561s` / `OK`.

3. Full Python suite (tool evidence):

   `python3 -I -m unittest discover -s scripts/tests -q > /tmp/full-suite.out 2>&1; echo $?` → `EXIT=1`

   Final summary: `Ran 1600 tests in 63.968s` / `FAILED (failures=6)`.

   All six failures are in `test_check_pack.py`, none in a story-scope file:
   `test_check_mode_detects_drift`, `test_cli_entry_point`, `test_main_reports_success`,
   `test_committed_opencode_json_is_generated`, `test_repository_pack_is_valid`,
   `test_simulated_user_skills_need_no_process_sections`. See `## Findings`.

4. Working tree: `git status --short` → empty (clean before and after verification).
   `git rev-parse HEAD` = `c06ca49c…` = `git rev-parse origin/main`;
   `git merge-base HEAD origin/main` = `c06ca49c…`; no branch-vs-main diff.

5. `git ls-files -v .opencode/opencode.json` → `S .opencode/opencode.json`
   (skip-worktree CI profile, the documented OPS-CW-003-01 environment condition).

Entry-point-to-test trace: `integrate_prepare()` in `scripts/swarm.py` → `guard_blockers()`
→ the patched `swarm.guard_blockers` seam in the affected tests; exercised end-to-end
through `IntegratorBatchTest` and `TwoTierTests` above.

## Coverage

No production line, branch, method or class was added or changed by me this run, so no
new coverage obligation arises from this worker. The earlier run's coverage statement
is recorded in the task file: `bash scripts/python-coverage.sh` →
`python coverage: every changed script line is covered`.

This run I re-ran the gate:

`bash scripts/python-coverage.sh > /tmp/cov.out 2>&1; echo $?` → `EXIT=1`

The script runs the full suite internally and failed on the **same six out-of-scope
`test_check_pack.py` failures** listed above (its output contains `FAILED (failures=6)`);
it did not report any uncovered changed line in a story-scope file. The failures come
from an unrelated regression on `main` after this story was merged (see `## Findings`),
not from the guard-blockers seam.

Note for the record: `bash scripts/python-coverage.sh | tail -20; echo $?` reports
`tail`'s status (`0`), not the script's. Redirect to a file and echo `$?` to get the
real result.

## Review verdicts

From the earlier completed run (recorded in the task file; read-only reviewers, no
Critical or Required finding remained at the final re-check). These are reviewer
findings from that run, not new reviews performed on this verification-only run:

- aurevia-github-workflow: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

1. **Already delivered.** The story's production split and test-seam patches are in
   `origin/main` (commits `ab6044a2`, `098354af`). A fresh swarm branch based on main
   therefore already contains the completed work; re-implementing it would duplicate a
   merged PR. I made no code changes and verified green with the targeted commands
   above. The task file is still marked `[-]` (integrator owns `[x]`), which is why the
   story was re-dispatched; this is a tracker-state artifact, not missing code.

2. **Out-of-scope full-suite regression.** Current `main` has six failures in
   `scripts/tests/test_check_pack.py`, all caused by commit `e010827c`
   "Add the run-workflow skill for dynamic workflows":
   `skill run-workflow: missing the section "## Common rationalizations"`,
   `"## Red flags"`, `"## Verification"`, `skills differ from specialists plus
   aurevia-delivery: ['run-workflow']`, and `.opencode/opencode.json is stale: run
   scripts/gen-opencode.py`. The fixes would touch `.opencode/skills/run-workflow/SKILL.md`,
   `scripts/gen-opencode.py`/`.opencode/opencode.json` and/or `scripts/tests/test_check_pack.py`,
   none of which are in this task's scope, so I did not change them. Existing completed
   tasks `COVPY-CHECK-PACK` and `WFAUDIT-05` cover check-pack generally but not this
   regression. Owner/integrator should open a task to regenerate the pack inventory for
   the new skill or remove it, and add the required skill sections.

3. **`opencode.json` skip-worktree.** `.opencode/opencode.json` is `S` (skip-worktree),
   the documented OPS-CW-003-01 worker-image condition; only that slice of the pack
   failure is an environmental false positive. The `run-workflow` section errors are
   genuine and separate. No check was bypassed or suppressed.

## Lessons for workers

- Before implementing a re-dispatched story, check whether `origin/main` already
  contains the fix commit and whether the task file already has `evidence:`/`reviews:`.
  A fresh swarm branch can start with the work already merged; verify with targeted
  tests instead of redoing it, and say so plainly in the notes.
- `bash scripts/python-coverage.sh` runs the whole suite; capture with
  `> /tmp/out 2>&1; echo $?` rather than piping to `tail`, which masks the script's
  exit status.
- Distinguish a story-scope failure from an unrelated regression on a moving `main`:
  name the failing file and commit, keep scope, and record the regression as a finding
  instead of editing protected files.

## Why this is not finished

The story itself **is finished** — already implemented, reviewed and merged in an
earlier run, and independently re-verified here (32 targeted tests OK; 5 named tests
OK). No new code was required, so this run produced no new commit-worthy production
change.

The repository-wide suite/coverage gate is nevertheless red on current `main`:
`python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1600 tests in 63.968s /
FAILED (failures=6)` (all in `scripts/tests/test_check_pack.py`). Most likely cause:
commit `e010827c` added the `run-workflow` skill without regenerating
`.opencode/opencode.json` or adding the skill sections the pack check requires. I did
not fix it because those files are outside this task's scope and are protected worker
files. Exact next step: open a separate task to make the pack inventory consistent with
the new skill (regenerate the generated config and add the required sections, or drop
the skill from the pack), then re-run the full suite and coverage on `main`.
