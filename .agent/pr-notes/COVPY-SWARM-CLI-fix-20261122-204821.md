# COVPY-SWARM-CLI · fix pass · scripts/swarm.py coverage, CLI slice (2450-2800)

## Summary

Final fix pass for story COVPY-SWARM-CLI (task `00407000`). The change under review is a
single new synthetic test file, `scripts/tests/test_swarm_cli_coverage.py`, added by the
first worker (commit `863b8bb1`) and re-verified by an earlier fix pass. It covers the
previously uncovered statements and branches of `scripts/swarm.py` in the assigned numeric
range 2450-2800:

- `delivery_listing()` (`scripts/swarm.py:2566-2570`): both outcomes of the
  `runs if isinstance(runs, list) else []` normalizing ternary.
- `repair_task()` (`scripts/swarm.py:2702-2715`): the worker-only-failure early return
  that avoids fetching the failing log.

This pass re-ran the targeted tests and the branch-coverage measurement on the current
branch, confirmed the assigned range is at zero missing lines and branches, confirmed the
diff-based script-coverage gate, ran the three post-code quality reviewers, and recorded
their verdicts. No production code, existing test, coverage threshold, exclusion or
suppression was changed and no test was skipped. All fixtures are synthetic and `gh` /
`gh_json` are local fakes, so no network, token or patient data is used.

## Affected requirements

- OPS-06 (swarm tooling, CI and worker gates). Test-only change; no product requirement
  behavior changed.
- Acceptance mapping (task `00407000`, details 1-4):
  1. Reused the existing fake-`gh` approach by copying a small local fake into the single
     new test file; no import from another test file.
  2. Measured coverage first with branch measurement; used the report, not the stale
     uncovered-line list from 2026-11-21.
  3. Wrote only new test code in `scripts/tests/test_swarm_cli_coverage.py`; no change to
     the script under test or to any existing test; coverage reached both outcomes of the
     ternary and the early-return path; no unreachable line was found in the range.
  4. Pasted the covered lines of the range under `## Coverage` below.

## Risk class

Routine — one new synthetic test file, no runtime, contract, schema, gate or dependency
change (docs/WORKFLOW.md).

## Review owner

smartclinic-senior-python and smartclinic-test-engineer (advisory). Post-code reviews were
performed by smartclinic-code-reviewer, smartclinic-coverage-auditor and
smartclinic-devsecops; see `## Review verdicts`.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix. No new
table, so no RLS/FORCE RLS requirement applies.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit-event code was touched. No real identifiers,
PHI or credentials appear in the fixtures.

## Migration and rollout considerations

None. The change is test-only and has no deployment or configuration surface.

## Test evidence

Commands run from the repository root (Python 3.11.2, coverage.py 6.5.0). This worker
image has no `pip` (`python3 -m pip` and `ensurepip` absent), so coverage was installed with
`apt-get update -qq && apt-get install -y python3-coverage` (6.5.0), the workaround recorded
by the first worker and the sibling COVPY-SWARM-SCHEDULE worker. Evidence source: executed
tool commands; reviewer verdicts are simulated read-only reviews, not human acceptance.

- `git merge --no-edit origin/main` -> `Already up to date.` (at the start and again before
  finishing). `git status --short` clean; no conflict markers were introduced by this story.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_cli_coverage.py' -v`
  -> `Ran 3 tests in 0.001s` / `OK` (exit 0).
- Full suite under branch coverage:
  `COVERAGE_FILE=<tmp> python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' -m unittest discover -s scripts/tests -q`
  -> final lines `Ran 1190 tests in 36.512s` / `FAILED (failures=6, errors=2)`.
  The 8 failures are identical before and after this change and are the documented
  worker-environment false positives in AGENTS.md: six `test_check_pack` failures whose
  first line is `opencode.json is stale: run scripts/gen-opencode.py` (OPS-CW-003-01;
  `git ls-files -v opencode.json` -> `S`, the CI profile under `skip-worktree`, while
  `git show HEAD:opencode.json` is the correct committed local profile) and two
  `test_worker_gate` errors from sandbox surefire-report fixtures
  (`integration run failed without fresh, usable test results`). No protected file was
  edited for them.
- `bash scripts/python-coverage.sh` is not a usable gate in this worker image: it stops at
  the full unittest stage with the same 8 pre-existing failures. Coverage was therefore
  measured explicitly with the commands under `## Coverage`, and the diff-based gate was
  run directly on that report.
- `python3 -I scripts/python_coverage.py /tmp/covcli_final.json --base origin/main`
  -> `python coverage: every changed script line is covered` (exit 0).
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` -> exit 0.
- `python3 -I scripts/check-docs.py` -> exit 0 (no output).
- `scripts/check.sh` was intentionally not run: AGENTS.md forbids a swarm worker from
  running it because it delegates to `apps/api/check.sh` (full `mvn verify`, >10 minutes)
  and would end the run as a timeout. The integrator and Delivery own that gate.

Entry point -> test trace (public function -> test):
- `delivery_listing()` ->
  `DeliveryListingCoverageTests.test_a_list_answer_is_returned_with_the_shared_query`
  and `DeliveryListingCoverageTests.test_a_non_list_answer_becomes_an_empty_list`.
- `repair_task()` ->
  `RepairTaskWorkerOnlyCoverageTests.test_worker_only_failure_returns_none_without_fetching_the_log`.

## Coverage

Command and result (re-measured on this fix pass on the current branch):

```
COVERAGE_FILE=<tmp> python3 -m coverage run --branch --source=scripts \
  --omit='scripts/tests/*' -m unittest discover -s scripts/tests -q
python3 -m coverage json -q --omit='scripts/tests/*' \
  --include='scripts/swarm.py' -o /tmp/covcli_final.json
```

Filtered to `2450 <= line <= 2800`, both `missing_lines` and `missing_branches` are
**empty**. Before this story the same filter returned three statements
(2566/2568/2570 area after line shifts) and one branch. The whole file measures 93.25%
because the other coverage-split tasks own the other ranges; only the assigned range is in
scope for this story.

Each previously uncovered line/branch and the named test that takes it:

| Line / branch | Behaviour | Test |
|---|---|---|
| line 2570, list outcome of `return runs if isinstance(runs, list) else []` (`delivery_listing`, `gh_json` call at 2568) | `gh_json` returns a list -> returned unchanged; the exact shared query arguments are asserted | `test_a_list_answer_is_returned_with_the_shared_query` |
| line 2570, else outcome of the same ternary | `gh_json` returns a non-list object (error envelope) -> normalized to `[]` | `test_a_non_list_answer_becomes_an_empty_list` |
| branch 2714->2715 / line 2715 (`repair_task` worker-only early return; earlier notes cited 2712->2713/2713, which is the separate "not failed on main" return) | run failed only in worker jobs -> returns `None` before the failing-log fetch at 2716 | `test_worker_only_failure_returns_none_without_fetching_the_log` |

No production line or branch was added or changed by this story, so there are no new
production branches beyond the previously uncovered ones mapped above.

## Clinical and architecture review

Not required. Test-only tooling change; no signed data, cross-organization sharing,
patient association, prescribing or critical-result behavior.

## Review verdicts

- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Findings

- **Task prose vs numeric range.** The task text describes this slice as "slot rotation,
  release and audit helpers, the argument parser and every subcommand of `main` with its
  output", but in the current 3986-line `scripts/swarm.py` those functions are not in lines
  2450-2800 (slot rotation is ~2899+, `main` is ~3693+). The assigned numeric range holds
  the Delivery-gate, draft-recovery, integration-planning and repair helpers. I followed
  the explicit task instruction "Cover only the uncovered lines in that range" and the
  coverage report, matching the sibling COVPY-SWARM-SCHEDULE worker. No line in
  2450-2800 was unreachable; all previously uncovered statements and the branch had a
  named test.
- **Coverage gap outside all five split ranges.** The five COVPY-SWARM tasks assign lines
  1-2800 (PLAN 1-700, SCHEDULE 700-1250, WORKERS 1250-1850, INTEGRATOR 1850-2450,
  CLI 2450-2800). Nothing assigns `swarm.py` lines 2801-3986 (including `main`). If
  COVPY-GATE switches `python-coverage.sh` to `--full` before that gap is assigned and
  covered, the full gate will still fail. This cannot be filed as a `task-requests` block:
  the only valid scope would be `scripts/*`, which `scripts/swarm.py::scope_ok` rejects as
  a protected path, so the integrator/owner must create that task directly.
- **Pre-existing committed conflict markers outside this task's scope.** `git grep` finds
  `<<<<<<<` / `>>>>>>>` markers in three committed files under
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/`
  (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). They are present in both
  HEAD and `origin/main`, unrelated to this story and outside scope, so they were not
  edited. Flagged for the integrator.
- The full Python suite has 8 failures that exist identically before and after this
  change (details under `## Test evidence`). Per AGENTS.md these are worker-environment
  false positives; the integrator tooling has PyYAML and cached data and runs the full gate
  authoritatively.
- Earlier runs recorded that a fix worker's local checks were red. The red is the same
  8 pre-existing suite failures above (the full-suite step of `python-coverage.sh`), not a
  defect in this test file; the targeted tests, the range coverage and the diff-based
  coverage gate all pass. No protected file was edited to mask it.
- Reviewer Optional findings, all non-blocking and dispositioned here: the code reviewer
  suggested also asserting the exact `gh_json("run", "view", ...)` arguments in the repair
  test; the current `assertIsNone` plus `assertFalse(fake.said("--log-failed"))` already
  prove the early return, and the reviewed artifact is left byte-for-byte stable. The
  coverage auditor noted the first-pass notes' stale pre-merge line numbers; this pass's
  `## Coverage` table already reconciles them to the current lines (2570 and 2714->2715).
  The code reviewer also noted that test methods lack docstrings, matching the established
  `test_swarm.py` convention; no change.

## Lessons for workers

- When a coverage slice is split by line number, filter the coverage JSON to the numeric
  range; the prose description of a slice can be stale. The measurable acceptance test is
  "zero missing lines and branches in the range", not the prose.
- A whole-pass helper that normalizes a `gh_json` answer
  (`runs if isinstance(runs, list) else []`) has two ternary outcomes; test both with list
  and non-list fakes.
- For an early return that avoids an expensive call (`repair_task` skipping the log
  fetch for worker-only failures), assert the skipped call is *absent*, not only that the
  return value is `None`.
- This worker image has no `pip`; `apt-get update && apt-get install -y python3-coverage`
  is the working way to get branch coverage for the Python suite.
- A fix pass over an already-committed change is still a quality-loop task: run all three
  post-code reviewers and copy their verdicts under `## Review verdicts`, or
  `scripts/check-verdicts.py` fails the task even when coverage is complete.
- A `task-requests` block cannot ask for work whose only valid scope is under `scripts/`:
  `scripts/swarm.py::scope_ok` rejects `scripts/*` as a protected path. Flag such a gap in
  Findings for the integrator/owner to create directly.
- In a cloud worker, the `test_check_pack` "opencode.json is stale" failure is the known
  CI-profile-under-`skip-worktree` artifact (OPS-CW-003-01). Confirm once with
  `git ls-files -v opencode.json` and `git show HEAD:opencode.json`, record it, and do not
  regenerate or commit that protected file on a later pass.
