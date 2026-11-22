# COVPY-SWARM-CLI · fix pass · scripts/swarm.py coverage, cli slice (2450-2800)

## Summary

Fix pass over the earlier worker's committed change (`863b8bb1`). That change added a
single new synthetic test file, `scripts/tests/test_swarm_cli_coverage.py`, covering the
uncovered statements and branches of `scripts/swarm.py` in the assigned numeric range
2450-2800: `delivery_listing()` (both outcomes of its list-normalizing ternary) and
`repair_task()` (the worker-only failure early return). This pass re-verified the change
after the `origin/main` merge (swarm.py grew 3973 -> 3986 lines), confirmed the range is
still at zero missing lines and branches, ran the mandatory quality-loop reviewers and
recorded their verdicts. No production code, existing test, threshold, exclusion or
suppression was changed, and no test was skipped.

## Affected requirements

OPS-06 (swarm tooling). Test-only change; no product requirement behavior changed.

## Risk class

Routine — one new synthetic test file, no runtime, contract, schema or gate change.

## Review owner

smartclinic-senior-python and smartclinic-test-engineer (advisory; no production change).

## Data changes

None. No migration, backfill, lock or volume impact, no rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit-event code was touched.

## Migration and rollout considerations

None.

## Test evidence

Commands run from the repository root (Python 3.11.2, coverage.py 6.5.0). This worker
image has no `pip`; `python3 -m pip` and `ensurepip` are absent, so coverage was installed
with `apt-get update -qq && apt-get install -y python3-coverage` (6.5.0), the same
workaround recorded by the earlier worker and the sibling COVPY-SWARM-SCHEDULE worker.

- `git merge --no-edit origin/main` -> `Already up to date.` at the start (re-run before
  finishing and before this commit). No conflicts; `git status --short` clean.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_cli_coverage.py' -v`
  -> `Ran 3 tests in 0.001s` / `OK`.
- Full suite under branch coverage, final lines:
  `Ran 1175 tests in 32.161s` / `FAILED (failures=6, errors=2)`.
  The 8 failures are identical before and after this change and are the documented
  worker-environment false positives in AGENTS.md: six `test_check_pack` failures whose
  first line is `opencode.json is stale: run scripts/gen-opencode.py`
  (OPS-CW-003-01; `git ls-files -v opencode.json` -> `S`, CI profile under skip-worktree;
  `git show HEAD:opencode.json` is the correct committed profile) and two
  `test_worker_gate` errors (`FileNotFoundError: 'apps/api/target/surefire-reports'`,
  a clean-checkout sandbox-fixture limit). No protected file was edited for them.
- `bash scripts/python-coverage.sh` -> exit 1, stopping at the full unittest stage with
  the same 8 pre-existing failures (`Ran 1175 tests in 29.060s` / `FAILED (failures=6,
  errors=2)`). Coverage analysis was therefore run explicitly with the commands below.
- `python3 -I scripts/check-docs.py` -> exit 0 (no output).
- `python3 -I scripts/check-constraints.py --base 43a417a2 --require-base` -> exit 0.

Entry point -> test trace (public function -> test):
- `delivery_listing()` ->
  `DeliveryListingCoverageTests.test_a_list_answer_is_returned_with_the_shared_query`
  and `test_a_non_list_answer_becomes_an_empty_list`
- `repair_task()` ->
  `RepairTaskWorkerOnlyCoverageTests.test_worker_only_failure_returns_none_without_fetching_the_log`

`scripts/check.sh` was intentionally not run: AGENTS.md forbids a swarm worker from
running it because it delegates to `apps/api/check.sh` (full `mvn verify`, >10 minutes)
and would end this run as a timeout. The integrator and Delivery own that gate.

## Coverage

Command and result (re-measured on this fix pass after the `origin/main` merge):

```
COVERAGE_FILE=/tmp/covcli_fix.cov python3 -m coverage run --branch --source=scripts \
  --omit='scripts/tests/*' -m unittest discover -s scripts/tests -q
COVERAGE_FILE=/tmp/covcli_fix.cov python3 -m coverage json -q \
  --include='scripts/swarm.py' -o /tmp/covcli_fix.json
```

Filtered to `2450 <= line <= 2800`, missing statements and missing branches are both
**empty**. The whole file remains 93.25% because the other split tasks own the other
ranges; only the assigned range is in scope.

Each previously uncovered line/branch and the test that takes it:

| Line / branch | Behaviour | Test |
|---|---|---|
| 2570, list outcome of `return runs if isinstance(runs, list) else []` (`delivery_listing`, call at 2568; earlier notes cited 2566/2568 after a line shift) | `gh_json` returns a list -> returned unchanged; also asserts the exact shared query arguments | `test_a_list_answer_is_returned_with_the_shared_query` |
| 2570, else outcome of the same ternary | `gh_json` returns a non-list object -> normalized to `[]` | `test_a_non_list_answer_becomes_an_empty_list` |
| branch 2714->2715 / line 2715 (`repair_task` worker-only early return; earlier notes cited 2712->2713/2713, which is in fact the separate "not failed on main" return) | run failed only in worker jobs -> returns `None` before fetching the failing log at 2716 | `test_worker_only_failure_returns_none_without_fetching_the_log` |

No production line or branch was added or changed by this story, so there are no new
production branches to map beyond the previously uncovered ones above.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Findings

- **Task prose vs numeric range.** The task text describes this slice as "slot
  rotation, release and audit helpers, the argument parser and every subcommand of
  `main` with its output", but in the current 3986-line `scripts/swarm.py` those
  functions are not in lines 2450-2800 (slot rotation is ~2899+, `main` is ~3693+).
  The assigned numeric range actually holds the Delivery-gate, draft-recovery,
  integration-planning and repair helpers. I followed the explicit task instruction
  "Cover only the uncovered lines in that range" and the coverage report, matching the
  sibling COVPY-SWARM-SCHEDULE worker. No line in 2450-2800 was unreachable; all three
  statements and the one branch had a test. A task request for the unassigned remainder
  is recorded at the end of these notes.
- **Coverage gap outside all five split ranges.** The five COVPY-SWARM tasks assign
  lines 1-2800 (PLAN 1-700, SCHEDULE 700-1250, WORKERS 1250-1850, INTEGRATOR 1850-2450,
  CLI 2450-2800). Nothing assigns `swarm.py` lines 2801-3986 (including `main`). If
  COVPY-GATE switches `python-coverage.sh` to `--full` before that gap is assigned and
  covered, the full gate will still fail. Flagged for the integrator/owner. This cannot
  be filed as a `task-requests` block: the only valid scope is `scripts/tests/...`, and
  `scripts/*` is a protected path that `scripts/swarm.py::scope_ok` rejects, so the
  integrator/owner must create that task directly.
- **Pre-existing committed conflict markers outside this task's scope.** `git grep`
  finds `<<<<<<<`/`=======`/`>>>>>>>` markers in three committed files under
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/`
  (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). They are present in
  both HEAD and `origin/main`, unrelated to this story, and outside scope
  `scripts/tests/test_swarm_cli_coverage.py`, so they were not edited. Flagged for the
  integrator.
- The full Python suite has 8 failures that exist identically before and after this
  change (details under `## Test evidence`). Per AGENTS.md these are worker-environment
  false positives; the integrator tooling has PyYAML and cached data and runs the full
  gate authoritatively.
- Reviewer Optional findings were all non-blocking and are dispositioned here:
  the earlier notes' line numbers drifted after the merge, so the `## Coverage` table
  above now cites current lines (2570 for the ternary, 2714->2715/2715 for the
  worker-only return); the `Gh` fake in the test file has some never-exercised branches
  but is harmless and left unchanged (the task forbids editing an existing test for a
  non-defect); the plan and coverage reviewers asked that the full-suite coverage
  command be recorded, which it is under `## Coverage`.

## Lessons for workers

- When a coverage slice is split by line number, filter the coverage JSON to the numeric
  range; the prose description of a slice can be stale. The measurable acceptance test is
  "zero missing lines and branches in the range", not the prose.
- A whole-pass helper that normalizes a `gh_json` answer
  (`runs if isinstance(runs, list) else []`) has two ternary outcomes; test both with
  list and non-list fakes.
- For an early return that avoids an expensive call (`repair_task` skipping the log
  fetch for worker-only failures), assert the skipped call is *absent*, not only that the
  return value is `None`.
- This worker image has no `pip`; `apt-get update && apt-get install -y python3-coverage`
  is the working way to get branch coverage for the Python suite.
- A fix pass over an already-committed change is still a quality-loop task: run all three
  post-code reviewers and copy their verdicts under `## Review verdicts`, or
  `scripts/check-verdicts.py` fails the task even when coverage is complete.
- A `task-requests` block cannot ask for work whose only valid scope is under `scripts/`:
  `scripts/swarm.py::scope_ok` rejects `scripts/*` as a protected path. Flag such a gap
  in Findings for the integrator/owner to create directly.
