# COVPY-SWARM-INTEGRATOR — integrator band coverage, fix pass

## Summary

The earlier fix attempts treated task step 1's **line band** (1850-2450) as authoritative and
concluded that no test code was needed, because that band already had zero missed lines and zero
missed branches. The same step also names the functions to cover — `guard`, merge, gate,
fix/repair, conflict resolution, delivery dispatch and the kill switch — and those functions were
**moved by later work to roughly lines 3473-3995 of `scripts/swarm.py`**, where they still had real
uncovered lines and branches. This pass adds 13 synthetic tests to the scoped file for those moved
functions. No production script, existing test, tracker, workflow or other protected file changed.
The stale line-band interpretation is recorded under `## Findings`.

## Affected requirements

- OPS-06 — synthetic CI tooling and checked delivery pipeline. Acceptance mapping:
  - Task step 1 (read `swarm.py` and existing swarm tests; reuse the fake `gh` approach by copying
    the helper): done. `FakeGh` and `scratch()` in the scoped file are reused, not imported.
  - Task step 2 (measure first; trust the report over the stale list): done — baseline
    `scripts/swarm.py 2636 94 1358 11 97%`, missing lines listed under `## Coverage`.
  - Task step 3 (new tests only in `scripts/tests/test_swarm_integrator_coverage.py`, no script
    or existing-test edit, no `# pragma`, synthetic data, mocked `gh`/git/subprocess, no network,
    both sides of every added branch): done; 13 new test methods.
  - Task step 4 (paste the coverage lines of the range): done under `## Coverage`, including the
    moved function range and the stale band.

## Risk class

Routine — test-only CI tooling change. No runtime behaviour, contract, tenant boundary or clinical
workflow is touched.

## Review owner

`orevia-senior-python` is the area routing for Python tooling. A bounded design proposal was
taken from it before coding. Read-only reviewers were run (all simulated/static subagents):
`orevia-plan-reviewer` (before coding and again after fixing its Required gaps), then
`orevia-code-reviewer`, `orevia-coverage-auditor` and `orevia-devsecops` after the
code and tests. Their final verdicts are copied under `## Review verdicts`.

## Data changes

None. No migration, schema, backfill, lock or volume impact. All fixtures are synthetic
(`CW-001`, `ISS-5`, PR `7`, `abc123`, `2026-11-19`) in temporary directories under `/tmp`.

## Authorization boundaries and audit events

None added. The new tests exercise and assert existing defensive behaviour: a protected-file PR is
blocked before any merge, a branch that moved after gating is not pushed, a failed integrator commit
is reported with status and staged diff, and the issue-handoff kill switch closes no issue. All
`gh`, git and subprocess calls are mocked; no token, network or real repository is used. No audit
event or patient scope changed.

## Migration and rollout considerations

None. The integrator still runs the full gate on the merge result.

## Test evidence

Environment: Python 3.11.2, coverage 6.5.0 (already present; no dependency added). All commands
below were executed in this worker (tool evidence). `git merge --no-edit origin/main` reported
`Already up to date.` at HEAD `d385c4a3` (branch `swarm/COVPY-SWARM-INTEGRATOR-1176`,
`origin/main` `f2588a4b`); `git grep -nE '^(<<<<<<<|>>>>>>>) '` reported no conflict markers; a
temporary `COVERAGE_FILE` under `/tmp/opencode` kept the tracked `.coverage` byte-identical.

- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_integrator_coverage.py' -v`
  → `Ran 34 tests in 0.026s` / `OK` (21 existing + 13 new; scoped suite green).
- `COVERAGE_FILE=/tmp/opencode/integrator-fix2.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  → `Ran 1320 tests in 27.532s` / `FAILED (failures=6)`; `COVERAGE_RUN_EXIT=1`. All six failures are
  `test_check_pack` (`test_check_mode_detects_drift`, `test_cli_entry_point`,
  `test_main_reports_success`, `test_committed_opencode_json_is_generated`,
  `test_repository_pack_is_valid`, `test_simulated_user_skills_need_no_process_sections`) — the
  documented `opencode.json` CI-profile `skip-worktree` false positive (OPS-CW-003-01). Confirmed
  once with `git ls-files -v opencode.json` → `S opencode.json`; no protected file regenerated and
  the artifact was not re-diagnosed on a later pass.
- `python3 -I -m unittest discover -s scripts/tests`
  → the same 6 failures, `Ran 1320 tests` / `FAILED (failures=6)`, `UNIT_EXIT=1`.
- `COVERAGE_FILE=/tmp/opencode/integrator-fix2.coverage python3 -m coverage json -o /tmp/opencode/integrator-fix2.json -q`
  then a source-and-destination line filter → target results under `## Coverage`.
- `python3 -I scripts/python_coverage.py /tmp/opencode/integrator-fix2.json --base origin/main`
  → `python coverage: every changed script line is covered`, `POLICY_EXIT=0` (no production script
  line changed).
- `bash scripts/coverage-check.sh origin/main` → `coverage check: passed`,
  `COVERAGE_CHECK_EXIT=0`.
- `python3 -I scripts/check-constraints.py --base origin/main` → `CONSTRAINTS_EXIT=0`.
- `python3 -I scripts/check-docs.py` → `DOCS_EXIT=0`.
- `git diff --check` → `DIFF_CHECK_EXIT=0`; no `pragma: no cover`, `@unittest.skip`, `.skip(`,
  `# noqa`, `NOSONAR`, disabled test or threshold change in the changed file.
- `scripts/check.sh` was deliberately **not** run: it delegates to `apps/api/check.sh` (full
  `mvn verify`, over ten minutes) and `bash scripts/python-coverage.sh`, which aborts on the six
  documented CI-profile failures. AGENTS.md reserves the full gate for the integrator; the
  individual policy scripts above were run instead.

Evidence provenance: all commands above are tool evidence executed in this worker. The four
specialist reviews are simulated/static reviewer evidence. No simulated-user walkthrough and no
human or clinical participant was involved. No patient data, credential or raw log is in this note.

Entry-point-to-test trace: `scripts/swarm.py` integrator entry points (`pr_already_in_main`,
`close_integrated_pr`, `integrate_batch`, `resolve_publish`, `close_merged_issues`) →
`scripts/tests/test_swarm_integrator_coverage.py` (`IntegratorMergeDecisionTest`,
`IntegratorBatchTest`, `IntegratorConflictPublishTest`, `IntegratorIssueCloseTest`) → the coverage
arcs listed under `## Coverage`.

## Coverage

Command:
`COVERAGE_FILE=/tmp/opencode/integrator-fix2.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
then `python3 -m coverage json -o /tmp/opencode/integrator-fix2.json -q`, filtered to the moved
function range and to the stale band; plus `bash scripts/coverage-check.sh origin/main`.

Result: `scripts/swarm.py` **98%** overall (2636 statements, 66 missed lines, 5 partial branches),
up from 97% (94 missed lines, 11 partial branches). **Zero missed lines and zero missed branches
with a source or destination in 1850-2450** (the task's stale band) and **zero missed lines and zero
missed branches in 3473-3995** except the one unreachable arc noted below.

| Lines / branch (`swarm.py`) | Test that takes it (with assertion) |
|---|---|
| 3754→3755, 3755 (head is ancestor) | `IntegratorMergeDecisionTest.test_an_ancestor_head_is_already_in_main` — result `True`, exact git call asserted |
| 3758-3764 false path, 3765-3766 (`except`), 3766 | `test_an_empty_merge_tree_is_treated_as_not_integrated` and `test_a_git_io_error_is_treated_as_not_integrated` — result `False` in both |
| 3771-3774, 3780-3781 (close + todo update) | `test_closing_an_integrated_pr_marks_both_open_task_forms_done` — close call and rewritten tracker text asserted |
| 3775-3777 (both `- [-]` / `- [ ]` forms), 3778, 3779 (no-match return) | same test — `update_todo` mock invokes `edit` with both forms and with unrelated text; `rewritten` list asserted |
| 3774 false (no task id) | `test_closing_an_integrated_pr_without_a_task_still_comments` — close recorded, `update_todo` not called |
| 3782-3783 (`except Exception: pass`) | `test_a_tracker_write_failure_does_not_stop_the_close` — no exception escapes, close recorded |
| 3803-3805 (guard blocks) | `IntegratorBatchTest.test_a_protected_pr_is_skipped_before_any_merge` — returns 0, `merge_local` not called |
| 3809 true → 3810-3813 (already in main) | `test_an_already_integrated_pr_is_closed_and_marked_done` — `close_integrated_pr(7, "CW-001")`, stdout, return 0 |
| 3861-3867 (commit failure wrapper) | `test_a_failed_integrator_commit_reports_status_and_staged_diff` — `CalledProcessError.output` contains status and staged sections |
| 3901→-3901, 3902→-3902 (REPAIR_GATE / empty env fallback) | `test_a_reconciled_regate_uses_the_repair_gate_when_no_full_gate_was_used` — `gate_for` mocked to a third gate, push fails once, `run_gate(swarm.REPAIR_GATE, {})` asserted |
| 3953-3954 (bundle fetch failure) | `IntegratorConflictPublishTest.test_an_unreadable_bundle_is_reported_and_not_pushed` — no push, `fail` reason contains "could not be read" |
| 3962-3963 (push failure after resolution) | `test_a_branch_that_moved_during_resolution_is_sent_back` — `fail` reason contains "branch moved" |
| 3987 false → 3988 (issues kill switch) | `IntegratorIssueCloseTest.test_no_issue_is_closed_when_the_issue_handoff_is_off` — no `gh` call at all |

No threshold, exclusion, `# pragma: no cover`, `.skip`, `@unittest.skip` or generated-file marker
was added or weakened. No production line or branch changed, so the changed-line policy check is
vacuous; the moved lines above were measured directly from the JSON report.

## Clinical and architecture review

Not required. The change is confined to tests for CI tooling; no signed data, cross-organization
sharing, patient association, prescribing or critical result is involved.

## Review verdicts

- orevia-plan-reviewer: GO
- orevia-code-reviewer: GO
- orevia-coverage-auditor: GO
- orevia-devsecops: GO

## Findings

- **Task description is stale.** Task step 1 says lines 1850-2450 and lists functions that now sit
  at roughly 3473-3995 of `scripts/swarm.py` (now 4283 physical lines). The previous fix attempts
  followed the line band literally, found it already covered, added no tests and were sent back
  twice as "did not finish cleanly". The function list is the authoritative intent; this pass
  covers those functions and records both ranges in `## Coverage`.
- **Unreachable branch not tested.** `integrate_batch` branch `3874→3911` (normal `for` loop
  exhaustion in the push-retry loop) cannot be reached: every iteration either `break`s on a
  successful push, `raise`s on the last attempt, or `return`s at 3910 after a failed regate.
  It is pre-existing production code and outside this task's scope; no fabricated test was added.
  A person may remove the dead loop-exit arc if whole-file 100% is required.
- **Remaining whole-file gaps are other tasks' bands.** `scripts/swarm.py` still has missing lines
  581/585 (PLAN 1-700), 829-869/875-898 (SCHEDULE 700-1250) and 3084/3118/3294-3296 (budget and
  worker-slot helpers), plus branch 3874→3911 above. None is in this task's scope and none was
  edited. `bash scripts/python-coverage.sh --full` would still report these pre-existing gaps.
- The six `test_check_pack` failures are the documented `opencode.json` CI-profile `skip-worktree`
  artifact (OPS-CW-003-01), confirmed once and not re-diagnosed. They make the full unittest and
  `python-coverage.sh` exit 1; the scoped suite, changed-line policy check and `coverage-check.sh`
  are green. The integrator owns the full gate.

## Lessons for workers

- When a task names both a line range and functions, check where the functions actually live before
  declaring that there is nothing to cover; a stale line band can hide real gaps after the file
  grows. Trust the current coverage JSON, not the task's 2026-11-21 line list.
- `mock.patch.object` of a callback consumer (here `update_todo`) must **invoke** the callback to
  execute the callback's own branch bodies; merely capturing it leaves lines uncovered.
- Inside a cloud worker, treat `test_check_pack` failures as the documented CI-profile artifact:
  confirm with `git ls-files -v opencode.json`, record the command and its final line once, and run
  the remaining gate stages individually instead of regenerating a protected file.

## Why this is not finished

Not applicable; the scoped change is complete with 100% coverage of every line and branch added or
made reachable in the task's named functions. Whole-file 100% for `scripts/swarm.py` is not achieved
by this one band task; the remaining gaps and the one unreachable arc belong to other tasks or to a
person, and are listed under `## Findings` rather than hidden.
