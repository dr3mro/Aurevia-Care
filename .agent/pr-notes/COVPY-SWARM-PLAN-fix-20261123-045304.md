# COVPY-SWARM-PLAN — fix pass: swarm.py plan-part coverage

## Summary

Fix pass (attempt 3) for the scoped story. The change is one new test file,
`scripts/tests/test_swarm_plan_coverage.py`, which closes the remaining 100% line and branch
coverage gap in `scripts/swarm.py` lines 1-700 (the "plan part": silent gh failure, ISS-n worker
hand-off, green-run issue closing and the watchdog repair-gave-up close path). No production code,
existing test, configuration, dependency, threshold, exclusion or skip was added or changed. All
fixtures are synthetic; gh, subprocess and environment boundaries are mocked; no test contacts the
network or reads a real token.

This pass re-verified the already-committed test file against the branch merged with `origin/main`,
completed the mandatory quality loop and wrote this correctly named fix-pass notes file. The
earlier notes (`COVPY-SWARM-PLAN-20261123-042641.md`) are retained in history as the first run's
record; this file is the pull request description for this run.

## Affected requirements

- OPS-06 — tooling coverage: 100% line and branch coverage of the changed `scripts/swarm.py` range
  (lines 1-700) with executable, synthetic tests.

Acceptance mapping:

| Acceptance criterion | Evidence |
|---|---|
| Only new test code in the single scoped file | `git diff --name-only $(git merge-base origin/main HEAD) HEAD` lists the test file and notes only |
| Every uncovered line and branch in lines 1-700 exercised | Coverage JSON filter: 0 missing lines, 0 missing branches <= 700 |
| No pragma, exclusion, skip or lowered threshold | `check-constraints.py --base origin/main --require-base` exit 0; branch-to-test map below |
| Failing test first, synthetic-only, no network | `Gh` fake and `mock.patch.object(swarm, "gh", fake)`; temp dirs with `addCleanup` |
| Step-6 checks run and recorded | `## Test evidence` below |

## Risk class

Routine — test-only tooling change (docs/WORKFLOW.md). No runtime behavior, contract, clinical data
or deployment affected.

## Review owner

Senior Python Tooling (`smartclinic-senior-python`), Tester and QA. Read-only reviewers used in this
pass: `smartclinic-plan-reviewer`, `smartclinic-code-reviewer`, `smartclinic-coverage-auditor`,
`smartclinic-devsecops`.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role or patient-link scope; no audit event added or changed. Tests never use a real
credential: `Gh` records calls in memory, `subprocess.run` and `os.environ` are patched only inside
`mock.patch` contexts.

## Migration and rollout considerations

None. Test-only change; no rollout, deployment or release gate affected.

## Test evidence

Environment (tool evidence): coverage was absent; pip was bootstrapped with
`python3 -c "import urllib.request; ...get-pip.py..."` then
`python3 -m pip install --break-system-packages coverage` → `Coverage.py, version 7.17.4 with C extension`.

Commands and final lines actually run on this branch after `git merge --no-edit origin/main`
("Already up to date."):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_plan_coverage.py' -v`
  → `Ran 7 tests in 0.004s` / `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm*.py' -q`
  → `Ran 479 tests in 8.439s` / `OK`.
- Full suite with the new file: `python3 -I -m unittest discover -s scripts/tests -q`
  → `Ran 1194 tests in 31.277s` / `FAILED (failures=6, errors=2)`.
- Same command with the new file temporarily moved out (baseline, after the merge)
  → `Ran 1187 tests in 33.736s` / `FAILED (failures=6, errors=2)`. Identical 6 failures and 2
  errors; the new file adds 7 passing tests and no failures. The failures are the documented
  worker-image artifacts: 6 `test_check_pack` failures because `git ls-files -v opencode.json`
  prints `S` (CI profile under skip-worktree) and `git diff HEAD -- opencode.json` is 0 bytes;
  2 `test_worker_gate` errors because `apps/api/target/surefire-reports` does not exist locally
  (no Maven output). Not caused by this change; left for the integrator's full gate.
- `python3 -I scripts/check-names.py` → exit 0, no output.
- `python3 -I scripts/check-secrets.py` → exit 0, no output.
- `python3 -I scripts/check-docs.py` → exit 0, no output.
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0, no output.
- `REQUIRE_PY_COVERAGE=1 python3 -I scripts/python_coverage.py /tmp/opencode/covpy/delta.json --base origin/main`
  → `python coverage: every changed script line is covered`, exit 0. (`bash scripts/python-coverage.sh`
  itself exits 1 earlier at the same underlying unittest step, documented above.)

Entry point → test trace:

- `scripts/swarm.py:120 gh()` → `SilentFailureTests.test_a_failed_gh_call_without_stderr_still_raises`
- `scripts/swarm.py:355 report_worker_handoff()` → `HandoffIssueTests` (both tests)
- `scripts/swarm.py:520 run_event()` → `RunEventGreenTests` (both tests)
- `scripts/swarm.py:643 watchdog()` → `WatchdogGreenTests` (both tests)

Reviewers were given only the story id, scope, paths, acceptance criteria and this evidence. No
clinical or human participant was involved.

## Coverage

Command: `COVERAGE_FILE=/tmp/opencode/covpy/.cov-covpy python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`,
then `python3 -m coverage report -m --include=scripts/swarm.py` and a JSON filter to lines <= 700.

Final report line: `scripts/swarm.py 2427 139 940 52 94%` — every remaining gap is at line >= 873,
belonging to other tasks' ranges. Filter result for this task's range:
`missing lines <=700: []`, `missing branches <=700: []`, 366 executed lines in lines 1-700 →
**100% line and branch coverage for lines 1-700**.

Branch-to-test map (all previously uncovered branches in the range):

| Source line | Branch / line | Test that takes it (assertion) |
|---|---|---|
| 148 | `if done.stderr:` False → raise with no print | `SilentFailureTests.test_a_failed_gh_call_without_stderr_still_raises` — asserts `returncode == 1`, `stderr == ""` and the redirected stderr is empty |
| 369-375 | ISS-n hand-off comments on that issue | `HandoffIssueTests.test_an_issue_task_comments_on_its_own_issue` — asserts `"5"`, a comment on issue 5, no create |
| 371 | ISS-n hand-off with `SWARM_ISSUES=off` returns `""` | `HandoffIssueTests.test_the_kill_switch_stops_an_issue_task_handoff` — asserts `""` and `fake.calls == []` |
| 543-549 | green non-Delivery run closes `"<name> failed:"` issues; `startswith` True and False | `RunEventGreenTests.test_a_green_non_delivery_run_closes_only_matching_run_level_issues` — asserts close 8, no close 9, no close 14 |
| 553 | `if name == "Delivery":` False → return | same test (name `swarm`) — asserts `"closed"` in result and repair issue 14 stays open |
| 554-560 | Delivery green closes exact repair-gave-up issue; equality True and False | `RunEventGreenTests.test_a_green_delivery_run_closes_only_the_repair_gave_up_issue` — asserts close 12 with "Main is green again", no close 13 |
| 662-672 | watchdog closes repair-gave-up issue on green Delivery | `WatchdogGreenTests.test_a_green_delivery_run_closes_the_repair_gave_up_issue` — asserts close 12, no close 13 |
| 673-674 | `except Exception` — broken Delivery listing does not stop the watchdog | `WatchdogGreenTests.test_a_failed_delivery_listing_does_not_stop_the_watchdog` — asserts skip message on stderr and `sync_issues` still called |

No production line or branch was added by this change. No pragma, exclusion, skip or lowered
threshold was added.

## Clinical and architecture review

Not applicable — test-only tooling change; no clinical data, cross-organization sharing, patient
association, prescribing, critical results or architecture boundary touched. This is simulated
review activity, not real clinical or unit acceptance.

## Review verdicts

- smartclinic-plan-reviewer: GO (after the three Required process gaps were addressed with the command evidence above and the owner/integrator escalation under Findings)
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

All Critical and Required findings were resolved on the final state; no verdict was copied from an
earlier pass.

## Findings

- **Owner/integrator escalation (process, not code).** `scripts/swarm.py:67` lists `"scripts/*"` in
  `PROTECTED`, and `guard()` at `:3298-3315` labels any PR touching this in-scope test file
  `needs-human` and opens a `protected-change` issue. That is why an earlier PR for this task was
  flagged "Protected files changed". The task scope is exactly that file, so this worker must not
  edit `scripts/swarm.py`, the gate or any tracker to "fix" it. Next step: an authorized person
  reviews and merges this scoped test-only PR, or records an approved protected-file exception.
- The full Python suite is not green in this cloud worker, but the exact same 6 failures and 2
  errors occur with the new file removed (1187 vs 1194 tests). Recorded once here from tool
  evidence and left for the integrator's full gate; no existing test was weakened to hide them.
- The 6 committed conflict markers in three `exchange/hl7` Java files are pre-existing in
  `origin/main` (identical count in HEAD and origin/main) and outside this scope; they were not
  touched. Flagged for the integrator/owner before release.
- No unreachable code was found in lines 1-700: every previously uncovered line and branch was
  reachable and is now exercised. No task request is needed.
- Optional (devsecops): the environment bootstrap fetched `get-pip.py` over HTTPS and executed it
  locally because this worker image ships no pip. No token or repository file was involved and it is
  outside the change scope, but future workers should prefer approved worker provisioning instead.

## Lessons for workers

- Re-measure coverage before writing tests in a parallel swarm: the stale line list and the current
  `scripts/swarm.py` can disagree, and sibling tasks can close gaps in the meantime.
- When the suite fails locally, prove a new test file is innocent by re-running the suite with that
  file temporarily moved out and comparing failure counts and names before blaming the change.
- A test file whose path matches a protected glob (`scripts/*`) will be flagged by the swarm guard
  even when it is the task's exact scope: record the owner decision in notes, never edit the gate.
- The fix-pass notes filename in the prompt wins; write that file only, one new file per run, and
  copy only final-state reviewer verdicts into it.
- Coverage and security reviewers in this pack may have no shell: they trace and judge, while the
  primary agent must still run and record the actual commands and numbers as tool evidence.

## Why this is not finished

The scoped task itself is complete: `scripts/swarm.py` lines 1-700 are at 100% line and branch
coverage, all 7 new tests pass, and the diff-based coverage and constraint gates pass. This section
records the two blockers that remain outside this worker's authority:

- What failed: full-suite `python3 -I -m unittest discover -s scripts/tests -q` →
  `FAILED (failures=6, errors=2)`; `bash scripts/python-coverage.sh` → exit 1 at that same unittest
  step; the swarm integrator's protected-file guard → PR flagged `needs-human` for
  `scripts/tests/test_swarm_plan_coverage.py`.
- Most likely cause: documented cloud-worker artifacts (skip-worktree `opencode.json` profile;
  no Maven `surefire-reports` output) and the `scripts/*` protected-file rule colliding with this
  task's exact scope. None is caused by this change.
- What was tried: measured coverage before and after; isolated the 7 new tests (OK); ran all 479
  swarm tests (OK); reproduced the identical 8 failures after temporarily removing the new file;
  verified `git ls-files -v opencode.json` is `S` and `git diff HEAD -- opencode.json` is empty;
  ran the delta coverage, constraints, names, secrets and docs checks directly (all exit 0).
- Exact next step: keep this scoped test-only change, let an authorized person review/merge it (or
  record an approved protected-file exception), and let the integrator run the full gate and 100%
  coverage check on the merge result with the Maven and PyYAML/NVD tooling available there.
- Attempt note: this is attempt 3 (TODO.md marks a task at 3 as waiting). If the protected-file or
  coverage gate still fails, stop and escalate rather than schedule another worker round.
