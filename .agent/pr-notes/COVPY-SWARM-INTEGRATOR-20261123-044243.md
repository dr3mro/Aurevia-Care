# COVPY-SWARM-INTEGRATOR — swarm.py coverage, integrator part (OPS-06)

## Summary

Added one new test file, `scripts/tests/test_swarm_integrator_coverage.py`, with 27 synthetic
unittest tests. It closes every previously uncovered line and reachable branch of this worker's
part of `scripts/swarm.py`: the publish guards and repair PR body, the daily-notes reconciliation
and its failure paths, `repair_task`, `integrate_batch` and `resolve_publish`. No production
script, existing test, tracker, workflow, protected file or configuration was changed. The file
split means whole-file `swarm.py` coverage stays at 95% until the other four workers merge; this
part's range has no missing line and only one structurally unreachable branch arc (see
`## Why this is not finished`).

21 of the tests are carried forward from an unmerged earlier branch (PR #910 / issue #977) after
verifying them against the current `swarm.py`; 6 tests are new in this run. The stale line range
in the task details (`1850-2450`) and the senior developer's function list were reconciled with
the actual file (now ~3990 lines): functional ownership, not the stale numbers, decides scope.

## Affected requirements

- OPS-06 — synthetic CI tooling with a checked delivery pipeline. Acceptance mapping:
  - Task step 1 (read `swarm.py` and the existing swarm test fixtures; copy `FakeGh` locally):
    done; nothing is imported from another test module except `helpers.load`.
  - Task step 2 (measure first; use the report, not the stale list): done. Baseline
    `scripts/swarm.py 2427 152 940 58 93%`; after the change `2427 91 940 44 95%`.
  - Task step 3 (new tests only in the scoped file, synthetic data, no network, both sides of
    every reachable branch): done; 27 tests, all assertion-bearing.
  - Task step 4 (paste the coverage lines of the range): done under `## Coverage`.
  - Scope: only `scripts/tests/test_swarm_integrator_coverage.py` was added.

## Risk class

Routine — a test-only change to a CI script's coverage. No runtime behaviour, contract, schema
or deployment changes.

## Review owner

`smartclinic-test-engineer` and `smartclinic-senior-python` are the standing owners for Python
tooling tests. This run used the read-only specialists named in the senior developer's brief:
context-optimizer, plan-reviewer, test-engineer, code-reviewer, coverage-auditor and devsecops.
All findings are simulated/static specialist reviews; no human or clinical reviewer was involved.

## Data changes

None. No migration, schema, backfill, lock or persisted data. All fixtures are synthetic
(`CW-001`, `FIX-100`, `2026-11-19`, `swarm/CW-001-1`) in temporary directories.

## Authorization boundaries and audit events

None added. The tests assert that a fix worker changing `.github/workflows/*` releases no claim
and pushes nothing, that a rules patch outside the allowed instruction documents is dropped, and
that fork branches and moved branches are never force-pushed. Every `gh`, git and
`subprocess.run` call is mocked or replaced by a local fake; no token, credential or network is
used. No audit event, tenant scope or patient scope changed.

## Migration and rollout considerations

None. Test-only; the integrator runs the full gate and the 100% coverage check on the merge result.

## Test evidence

Environment: Python 3.11.2, `coverage` 7.17.4. The worker image had neither pip nor coverage;
both were bootstrapped into the interpreter from `get-pip.py` via a helper written under
`/tmp/opencode` (no repository file touched). Coverage data was written to
`COVERAGE_FILE=/tmp/opencode/*.coverage` so the tracked `.coverage` file was never modified.
`git merge --no-edit origin/main` ran at the start and before finishing: `Already up to date.`
No conflict markers were introduced by this change.

Commands run (tool evidence, final lines):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_integrator_coverage.py' -v`
  → `Ran 27 tests in 0.042s` / `OK`; final run after the hardening edits (no `-v`)
  → `Ran 27 tests in 0.038s` / `OK`. Re-run after every edit to the test file.
  (The brief's `python3 -m unittest scripts.tests.test_swarm_integrator_coverage -v` cannot work:
  `scripts/` has no `__init__.py`. The repository convention is `unittest discover -s scripts/tests`
  and is used throughout.)
- `COVERAGE_FILE=/tmp/opencode/cov-baseline.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  → baseline `scripts/swarm.py 2427 152 940 58 93%`.
- `COVERAGE_FILE=/tmp/opencode/cov-after.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  then `coverage report -m --include=scripts/swarm.py`
  → `scripts/swarm.py 2427 91 940 44 95%` (full missing list under `## Coverage`).
- `coverage json` filter over the in-scope function ranges
  → `in-scope missing LINES: []`, `in-scope missing BRANCHES: [(3584, 3621)]`.
- `bash scripts/python-coverage.sh` → exit 1 **only** from the eight documented cloud-worker
  false positives, confirmed once and not re-diagnosed: six `test_check_pack` failures
  (`opencode.json is stale: run scripts/gen-opencode.py`; `git ls-files -v opencode.json` shows
  `S`, the CI profile installed under `skip-worktree`, while `git show HEAD:opencode.json` is the
  committed local profile — OPS-CW-003-01) and two `test_worker_gate*` errors
  (`FileNotFoundError: apps/api/target/surefire-reports`). The `set -e` gate stops at the test
  failures before its policy step, so the policy was run directly:
  `COVERAGE_FILE=... coverage json --omit='scripts/tests/*' -o /tmp/opencode/policy.json` then
  `python3 -I scripts/python_coverage.py /tmp/opencode/policy.json --base origin/main`
  → `python coverage: every changed script line is covered`, `POLICY_EXIT=0`. No production
  script line changed, so the changed-lines gate is vacuous by design; the direct line/branch
  report is the meaningful evidence.
- `python3 -I -m unittest discover -s scripts/tests` (the TODO step-6 suite) →
  `Ran 1214 tests in 37.343s`, `FAILED (failures=6, errors=2)` — the same eight documented
  environment failures, no failure from the new file.
- `git merge --no-edit origin/main` → `Already up to date.`; `git grep -nE '^(<<<<<<<|>>>>>>>) '`
  over non-Java files → empty.
- `scripts/check.sh` was deliberately not run: it delegates to `apps/api/check.sh` (full
  `mvn verify`, over ten minutes) and AGENTS.md forbids a swarm worker from running it. The
  integrator and Delivery own that gate.

Entry-point-to-test trace: `unittest discover` → `scripts/tests/test_swarm_integrator_coverage.py`
→ `helpers.load("swarm")` → public functions `release`, `publish`, `notes_collect`, `push_main`,
`archive_day`, `open_rules_pr`, `notes_publish`, `repair_task`, `integrate_batch` and
`resolve_publish`, each with a patched `gh`/git/`subprocess.run` boundary and an asserted outcome.

Evidence provenance: all commands above are tool evidence executed in this worker. The five
specialist reviews are simulated/static specialist evidence, not human or clinical acceptance.
No simulated-user walkthrough and no human participant was involved; there is no patient data.

## Coverage

Command: `COVERAGE_FILE=/tmp/opencode/cov-after.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
then `python3 -m coverage report -m --include=scripts/swarm.py` and a `coverage json` arc filter.

Result: `scripts/swarm.py` 95% overall (91 missed lines, 44 partial branches). **No missing line
and no reachable missing branch remains in this part's functions.** Before the change this part
had 31 missed lines and 20 partial branches. Every branch and its test:

| Branch / line (swarm.py) | Test in `test_swarm_integrator_coverage.py` |
|---|---|
| 2008→2009, lines 2008-2009 (`release` writes a step summary) | `ReleaseSummaryTest.test_release_appends_a_row_to_the_step_summary` |
| 2064→2066 (workflow files, fix kind: no claim release) | `PublishGuardTest.test_a_fix_with_workflow_changes_does_not_release_a_claim` |
| 2101→2103, line 2103 (repair PR body prefix + autofix label) | `PublishGuardTest.test_repair_pull_request_explains_the_automatic_merge` |
| 2194-2195 (`new_york_now`) | `NotesClockTest.test_new_york_now_is_timezone_aware` |
| 2225-2226 (unreadable tracked note skipped) | `NotesClockTest.test_an_unreadable_tracked_note_is_skipped` |
| 2255→2256 (no files left in a day) | `NotesClockTest.test_a_day_with_no_files_left_ends_collection` |
| 2267→2268 (edit returns false), 2269→2272, 2276→2282 | `PushMainTest` (five tests: unchanged edit, zero attempts, clean push, race retry, final failure) |
| 2289→2290 (no names) | `ArchiveDayTest.test_no_names_leaves_no_archive` |
| 2296→2295 (name already in the zip) | `ArchiveDayTest.test_a_name_already_in_the_zip_is_not_written_twice` |
| 2329→2330 (proposals not a JSON list) | `NotesIssueEntriesTest.test_a_json_object_is_not_a_list_of_proposals` |
| 2356-2385 (`open_rules_pr` patch checks, docs check, PR URL, label) | `RulesPullRequestTest` (five tests) |
| 2414→2410, 2420-2421 (`notes_publish` issue/rules failures) | `NotesPublishFailureTest` (two tests) |
| 2714→2715, line 2715 (worker-only failure needs no repair) | `RepairTaskTest.test_a_worker_only_failure_returns_no_repair_task` |
| 3529→3530, line 3530 (guard blocks a PR before merging) | `IntegrateBatchGuardTest.test_a_blocked_pr_is_skipped_before_any_merge` |
| 3556→3557, lines 3557-3559 (gate fails: fail + reset) | `IntegrateBatchGuardTest.test_a_failing_merge_gate_fails_the_pr_and_resets_the_merge` |
| lines 3571-3574 (`git commit` raises; status/staged attached) | `IntegrateBatchGuardTest.test_a_failed_integrate_commit_is_reported_with_git_state` |
| 3663→3664 (resolution bundle fetch fails) | `ResolvePublishFailureTest.test_an_unreadable_bundle_is_reported_and_not_pushed` |
| 3672→3673 (resolution branch moved during push) | `ResolvePublishFailureTest.test_a_branch_that_moved_during_resolution_is_reported` |

Already covered by existing `scripts/tests/test_swarm.py` (verified from the coverage JSON
executed-arc list, both sides taken): 3589→3590 (final push attempt raises) by
`test_integrate_batch_restores_trackers_on_retry` (line 2891); 3589→3591 (retry continues) by
`test_integrate_batch_retries_when_main_advanced` (line 2758). The 3584→3621 loop-exhaustion arc
is unreachable and is documented below.

Full `coverage report -m --include=scripts/swarm.py` missing list after the change (all other
items belong to the four other workers' ranges): `148->151, 371, 546-549, 553->561, 557-560,
666-674, 873, 926, 927->935, 929, 951-952, 963, 987, 994->993, 1147-1148, 1336->1320,
1533-1534, 1753, 1838, 1843, 2568-2570, 2921, 2955, 3127-3129, 3584->3621, 3698, 3807, 3809,
3813, 3816, 3837-3841, 3859-3861, 3864, 3876, 3878, 3880, 3892-3893, 3895-3896, 3899, 3903,
3905, 3909-3910, 3918-3919, 3923-3925, 3929->3982, 3935, 3937-3940, 3942-3944, 3946-3948,
3966-3981, 3986`.

## Clinical and architecture review

Not required. The change is confined to tests for CI tooling. No signed data, cross-organization
sharing, patient association, prescribing or critical result is touched.

## Why this is not finished

One branch arc remains uncovered and cannot be covered by tests without editing production code,
which is outside this task's scope:

- `scripts/swarm.py:3584 -> 3621` — the `for attempt in range(3)` loop-exhaustion arc in
  `integrate_batch`. Every iteration of the loop body leaves by `break` (line 3587, push
  succeeded), `raise` (line 3590, `attempt == 2` and the push failed) or `return 0` (line 3620,
  re-gate failed). `coverage.py` does not record the `break` jump as a branch arc, so the loop's
  exit-to-after arc has no reachable path. This was confirmed with a minimal standalone
  `coverage run --branch` experiment on the same `for/try/break/except/if-raise` shape: the
  loop→body arc is recorded, the loop→after arc stays missing even though `break` executes.
  What I tried: full-suite coverage before and after the new tests, and a JSON arc filter that
  isolates this single arc. Exact next step for a person (production change, separately
  authorized): remove or restructure the dead loop-exhaustion path in `swarm.py` so the arc
  disappears, then re-run `python3 -m coverage report -m --include=scripts/swarm.py`. No
  `# pragma: no cover`, exclusion, threshold change or fabricated test was used to hide it.

All other in-scope lines and reachable branches are covered and the targeted suite, the
changed-lines policy and the TODO step-6 suite (apart from the documented environment failures)
are recorded above.

## Findings

- Whole-file coverage is 95%, not 100%, because the other ranges belong to the other four
  parts of this five-task split. This part's range has no missing line; do not treat the other
  gaps or the single dead arc as a defect of this part.
- Known cloud-worker false positives recur in the unfiltered suite (see Test evidence):
  `test_check_pack` (CI `opencode.json` profile under `skip-worktree`) and the two
  `test_worker_gate*` cases (missing `apps/api/target/surefire-reports`). No production code or
  test in scope caused them.
- The senior developer's brief is slightly wrong on one command (`python3 -m unittest
  scripts.tests...` cannot work because `scripts/` is not a package) and the task's declared
  line range is stale (1850-2450 versus functions now at 2002-3681). Both are recorded here;
  neither changed the work.
- No defect in the script under test was found; no protected file was edited; no
  `task-requests` block is needed because the one follow-up requires a production change that
  a task-request is not allowed to name (script paths are excluded from task-request scopes).

## Lessons for workers

- Before trusting a coverage report, convert `A->B` partial branches with `coverage json`: it
  turns a percentage into an exact arc list so every branch can be mapped to a named test.
- A `for` loop whose body can only `break`, `raise` or `return` leaves its loop-exhaustion arc
  uncovered; coverage.py does not count `break` as that arc. Detect this early with a small
  standalone experiment rather than writing a test that exists only to touch the line.
- Read the task's function list, not its stale line numbers, when a script has grown since the
  story was written; reconcile both against the current `grep -n '^def '` output.
- Reusing a proven test file from an unmerged branch is faster than rewriting it, but verify it
  against the current source and re-run coverage: line shifts and function changes can silently
  stale the fixture.
- Keep coverage data outside the repo (`COVERAGE_FILE=/tmp/opencode/...`) because `.coverage` is
  tracked; otherwise a coverage run pollutes the scoped commit.
- When `set -e` makes `python-coverage.sh` stop at known environment test failures, run the
  policy step directly with the produced JSON (`python3 -I scripts/python_coverage.py report.json
  --base origin/main`) so the changed-lines gate is still verified.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-test-engineer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

All five reviews were read-only specialist reviews (simulated/static evidence). The first plan
review returned NO-GO with three Required items; all were closed with tool evidence before the
final GO (probe of the dead arc, confirmation that existing tests cover both sides of the
final-attempt push check, and a line-by-line reconciliation of the full missing-lines report).
The test-engineer's two Optional hardening findings were applied: patched `gh_json`/`gh` in the
guard-blocked batch test with `assert_not_called`, and patched `emit` with explicit pushed/failed
assertions in both `ResolvePublishFailureTest` cases. The targeted suite and coverage were re-run
after those edits with the same results. No Critical or Required finding remains.
