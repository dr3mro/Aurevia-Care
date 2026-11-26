# FIX-TEST-WORKSPACE-RESET — Prevent tooling tests from resetting the real workspace

## Summary

The full Python tooling suite reset the real checkout twice at 14:26:36 -0500. The root cause was
conflict-path integrator unit tests calling the real `integrate_batch`; production rollback in
`integrate_prepare` calls `git reset --hard <base>` through `swarm.git`, which spawns a subprocess in
the current directory. The affected unit tests now mock every Git boundary, and a regression runs
those scenarios with `subprocess.run`, `check_output` and `Popen` replaced by a guard that raises
before any child process starts. This worker did not change production code and wrote no new code:
the fix was already present in the worktree from earlier runs, so this pass verified it, strengthened
the evidence record, and completed the review loop.

## Affected requirements

- OPS-06: separate development/test/production resources and synthetic fixtures; tooling tests must
  never mutate the real checkout.
- Defect OPS-TEST-WORKSPACE-RESET in `.opencode/config/BUGS.md`; tracked in
  `tasks/01278000-FIX-TEST-WORKSPACE-RESET.md`.

## Risk class

Cross-boundary (tooling/security): a test-suite defect can destroy uncommitted work in the real
checkout. No patient data, credentials or clinical behavior are involved.

## Review owner

Orchestrator / trusted integrator; read-only reviews recorded under `## Review verdicts`.

## Data changes

None. No migration, fixture, database row or production artifact changed.

## Authorization boundaries and audit events

No application tenant, role or patient scope is touched. The only boundary exercised is the test
process boundary: no Git or `gh` subprocess may start from the 13 guarded integrator scenarios.
No audit event type changed.

## Migration and rollout considerations

None. Production `swarm.git` rollback behavior is unchanged; only test seams are mocked. No rollout
or rollback step is needed for the source change.

## Test evidence

All commands ran in `/__w/Aurevia-Care/Aurevia-Care` with no Git or `gh` command. Evidence sources
are tool runs, read-only reviewers and this worker's synthetic checks; no simulated user or human
participant was involved. The regression's inner tests are the existing acceptance scenarios from
`test_swarm.py` and `test_swarm_delivery_state.py`.

- Red state was recorded by an earlier run: `python3 -I -m unittest discover -s scripts/tests -p 'test_workspace_git_isolation.py' -v` failed at the guarded subprocess call before any Git process started (task file evidence).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_workspace_git_isolation.py' -v` → `Ran 1 test in 0.106s`, `OK`. The single outer test executes 13 inner scenarios and asserts `testsRun == 13`, no skips or expected failures, `wasSuccessful()` and zero calls on all three guarded subprocess entry points.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py' -k IntegratorWiringTest -v` → `Ran 6 tests in 0.100s`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k test_conflict_is_handed_to_the_resolver_not_failed -v` → `Ran 1 test`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k test_conflict_goes_back_to_the_workers_after_three_tries -v` → `Ran 1 test`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k test_a_branch_that_moved_after_planning_is_retested_without_the_debugger -v` → `Ran 1 test`, `OK`.
- `python3 -I -m py_compile scripts/tests/test_swarm.py scripts/tests/test_swarm_delivery_state.py scripts/tests/test_workspace_git_isolation.py` → `PY_COMPILE_OK`.
- `python3 -I scripts/check-docs.py` → `CHECK_DOCS_OK`; `python3 -I scripts/check-names.py` → `CHECK_NAMES_OK`; `python3 -I scripts/check-constraints.py` → `CHECK_CONSTRAINTS_OK`; `python3 -I scripts/check-secrets.py` → `CHECK_SECRETS_OK`.
- Guard proof (Python snippet, no process spawn): with `swarm.subprocess.run` patched to raise, `swarm.git("reset", "--hard", "base-sha")` → `GUARD_TRIGGERED: integrator unit test started a real subprocess` and `SUBPROCESS_CALLS: 1` (the mock call itself; no Git child). This shows an unmocked rollback boundary would be caught.
- Non-Git workspace snapshot: SHA-256 of `scripts/tests/test_swarm.py`, `scripts/tests/test_swarm_delivery_state.py`, `scripts/tests/test_workspace_git_isolation.py`, `.opencode/config/WORKSPACE.md` and `tasks/01278000-FIX-TEST-WORKSPACE-RESET.md` before and after the regression → `INNER_SUITE_OK: True tests: 1`, `WORKSPACE_UNCHANGED: True`, `CHANGED_PATHS: []`.
- `grep -nE '^(<<<<<<<|>>>>>>>) ' scripts/tests` (tool grep) → no matches; no conflict markers remain in the scoped tests.
- Final gate check: `python3 -I scripts/check-verdicts.py .agents/notes/FIX-TEST-WORKSPACE-RESET-20261125-003024.md` → `CHECK_VERDICTS_OK` with all four GO lines recorded below; policy scripts re-run green after the notes edit; scoped coverage report re-read at 100%.

Entry-point-to-test trace: `unittest discover -p test_workspace_git_isolation.py` →
`WorkspaceGitIsolationTests.test_integrator_scenarios_do_not_start_subprocesses` → 13 existing
scenarios in `ResolveConflictTests`, the `restore_trackers` `IntegrationTests` and
`IntegratorWiringTest` → `swarm.integrate_batch` / `swarm.restore_trackers` → mocked `swarm.git`,
`swarm.pr_already_in_main` and all subprocess entry points → zero real processes and all original
behavioral assertions still pass. The moved-head scenario still asserts the intended
`git("reset", "--hard", "base-sha")` call on the mock, so the rollback intent is preserved.

## Coverage

Only test files were changed; no production line or branch was added. Scope note:
`scripts/python-coverage.sh` omits `scripts/tests/*` and runs the full suite with
`COVERAGE_BASE=origin/main`, so it is not a meaningful production-coverage gate for this change and
cannot run under this session's no-Git directive.

- Command: `export COVERAGE_FILE=/tmp/opencode/fix-workspace-reset.coverage; python3 -m coverage erase; python3 -m coverage run --branch --include='scripts/tests/test_workspace_git_isolation.py' -m unittest discover -s scripts/tests -p 'test_workspace_git_isolation.py' -q; python3 -m coverage run --append --branch --include='scripts/tests/test_workspace_git_isolation.py' scripts/tests/test_workspace_git_isolation.py; python3 -m coverage report -m --include='scripts/tests/test_workspace_git_isolation.py'`
- Result: `scripts/tests/test_workspace_git_isolation.py 23 stmts, 0 miss, 4 branches, 0 partial, 100%`.
- Coverage tool: Coverage.py 6.5.0 with C extension (`python3 -m coverage --version`).
- New branches and their tests: none in production code. The four reported branch arcs in the
  regression file (including both sides of `if __name__ == "__main__"`) are all taken: the direct
  module run covers the true side, the `unittest discover` run the false side; `0 partial` in the
  combined report is the proof. The three guarded entry points each have an `assert_not_called`
  assertion; the 13 inner scenarios supply the behavior assertions that prove the mocks did not hide
  a failing test.
- No threshold, exclusion, suppression or skipped test was added; `scripts/check-constraints.py` is green.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or
critical result is involved. This is a tooling isolation fix for OPS-06.

## Review verdicts

- aurevia-plan-reviewer: GO (no Required gap; optional tracker-count drift from 8 to 13 recorded under Findings, trackers are not worker-editable)
- aurevia-code-reviewer: GO (no Critical/Required finding; process-wide guard, full inner-suite assertions, other Git callers safe; residual items are Optional and tracker-owned)
- aurevia-coverage-auditor: GO (test-only change; no new production branch; 100% scoped line/branch on the regression file; canonical script blocked by no-Git directive)
- aurevia-devsecops: GO (no Critical/Required gap; all in-repo process launches use the guarded subprocess attributes; no credentials/PHI or suppression added; production rollback untouched)

## Findings

- The fix was already in the worktree when this worker started; earlier runs had localized the root
  cause, mocked the Git boundaries and expanded the regression from eight to 13 scenarios. This
  worker contributed verification evidence and the review record, not new source code.
- `QA-01279000-01` (inline `details:` parsing in `scripts/swarm.py:2269-2270`) is already fixed in
  the current tree; `scripts/swarm.py` is outside this task's scope and was not edited.
- Recording the non-Git evidence matters: the owner prohibited Git-using scripts and this session's
  durable directive forbids Git/Git-backed operations, so neither the full suite nor
  `scripts/python-coverage.sh` can be run here. The trusted integrator owns that gate.
- Production `git()` still calls `git reset --hard` on rollback when real integrators run; that is
  intended. The regression proves only that *unit tests* cannot reach it.
- Tracker counts drift: `tasks/01278000-FIX-TEST-WORKSPACE-RESET.md`, `MEMORY.md`, `WORKSPACE.md`
  and `BUGS.md` still describe eight scenarios / 8/8, while `test_workspace_git_isolation.py:68`
  asserts `testsRun == 13` (five additional `restore_trackers` scenarios). Trackers are not
  worker-editable; all four reviewers mark this Optional for this change, but the integrator should
  reconcile the counts before marking the task `[x]`.

## Lessons for workers

- Guard the process boundary, not just `swarm.git`: patching `subprocess.run`, `check_output` and
  `Popen` catches a new unmocked Git call even when it reaches the production helper by a different
  path. Verify the guard with an explicit unmocked-call proof before trusting it.
- A no-subprocess assertion alone can hide silently skipped inner tests. Always assert the inner
  test count, no skips/expected failures and `wasSuccessful()` alongside zero subprocess calls.
- When Git execution is forbidden, a SHA-256 snapshot of scoped files across the regression is a
  cheap, honest non-Git evidence trail for "the workspace was not mutated".
- Test-only changes have no production branch to cover; measure the regression file itself with a
  scoped `coverage run --include=...` and say plainly why the canonical script cannot run.

## Why this is not finished

The source fix is verified locally and all scoped non-Git checks are green. Two items are blocked by
authority, not by a code defect:

1. No commit or pull request: this session's durable directive forbids Git and Git-backed
   operations, so the brief's `git merge --no-edit origin/main` and "commit to the current branch"
   steps were not performed. Exact next step for the trusted integrator: reconcile with
   `origin/main`, commit the scoped test files and this notes file, and observe the exact-SHA gate.
2. Full suite and canonical Python coverage: `python3 -I -m unittest discover -s scripts/tests -q`
   and `bash scripts/python-coverage.sh` invoke Git-using tests and require `origin/main` as a diff
   base. The owner prohibited Git-using scripts after the 14:26:36 -0500 reset. Exact next step:
   run both in the trusted integrator environment and record the final lines on the task file.
