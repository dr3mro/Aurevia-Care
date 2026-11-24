# COVPY-SWARM-SCHEDULE · scripts/swarm.py schedule slice coverage

## Summary

Added one new test file, `scripts/tests/test_swarm_schedule_coverage.py`, that covers the
previously uncovered lines and branches of `scripts/swarm.py` in the assigned range
(lines 700–1250): issue-monitor selection, task status resets, monitor assignment and
slot-image readiness. No production code, existing test, threshold or exclusion was
changed. All fixtures are synthetic and `gh` is replaced by a local fake; no network,
token or patient data is used.

## Affected requirements

OPS-06 (swarm tooling). This is a test-only change; no product requirement behavior changed.

## Risk class

Routine — one new synthetic test file, no runtime, contract, schema or gate change.

## Review owner

orevia-senior-python and orevia-test-engineer (advisory; no production change).

## Data changes

None. No migration, backfill, lock, volume impact or rollback/forward-fix.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit-event code was touched.

## Migration and rollout considerations

None.

## Test evidence

Commands run from the repository root (Python 3.11, coverage.py 6.5.0 installed via
`python3-coverage` apt package because this worker image has no `pip`):

- Baseline (before the new file) —
  `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
  then `python3 -m coverage json`: `Ran 1138 tests`, `FAILED (failures=6, errors=2)`;
  uncovered lines in 700–1250 were `871, 924, 927, 949, 950, 961, 985, 1145, 1146`
  and missing branches `870->871, 922->-922, 923->924, 925->933, 926->927, 960->961,
  984->985, 992->991, 1062->-1062`.
- New file alone — `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_schedule_coverage.py' -q`
  → `Ran 10 tests ... OK` (exit 0).
- Final combined run — `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
  → `Ran 1148 tests ... FAILED (failures=6, errors=2)`, then
  `python3 -m coverage json -o /tmp/opencode/cov_final2.json -q`: zero missing lines and
  zero missing branches in 700–1250. The 10 new tests are among the 1148 and all pass.
- `bash scripts/python-coverage.sh` → exit 1, stopping at the full unittest stage with
  the *same* pre-existing failures as baseline (see Findings). The coverage analysis was
  therefore run explicitly with the commands above; it proves the assigned range is
  fully covered.
- `python3 -I scripts/check-docs.py` → exit 0 (no output).
- `git merge --no-edit origin/main` → `Already up to date.` at the start; tree clean.

Entry point → test trace (public function → test):
- `monitor_select()` → `MonitorSelectCoverageTests.test_an_issue_naming_an_unknown_task_is_nudged`
- `reset_attempts()` → the three `ResetAttemptsCoverageTests` cases
- `monitor_assign()` → the four `MonitorAssignCoverageTests` cases
- `slot_config()` → `SlotImageCoverageTests.test_an_unknown_slot_number_has_no_config`
- `slot_manifests_exist()` → `SlotImageCoverageTests.test_a_registry_error_means_the_image_is_missing`

The full `scripts/check.sh` was intentionally not run: AGENTS.md forbids swarm workers
from running it because it delegates to `apps/api/check.sh` (full `mvn verify`, >10 minutes)
and would end this run as a timeout. The integrator and Delivery own that gate.

## Coverage

Command and result:
`python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
followed by `python3 -m coverage json -o /tmp/opencode/cov_final2.json -q`; from that JSON,
missing lines and branches in `scripts/swarm.py` filtered to `700 <= line <= 1250` are
**empty**. Before the change the same filter returned 9 lines and 9 branches (listed in
Test evidence). The whole file is 94% because the other four split tasks own the other
ranges; only the assigned range is in this task's scope.

Each previously uncovered line/branch and the test that now takes it:

| Line / branch | Test |
|---|---|
| `870->871`, line 871 (`monitor_select`: task marker with no TODO entry → nudge) | `test_an_issue_naming_an_unknown_task_is_nudged` |
| `922->-922`, `923->924`, line 924 (`reset_attempts`: no entry found → unchanged text) | `test_an_unknown_task_leaves_the_text_unchanged` |
| `925->933` (loop exhausts: entry has no details and no following entry) | `test_a_task_with_no_details_or_following_entry_reaches_the_end` |
| `926->927`, line 927 (scan stops at the next unindented line) | `test_the_scan_stops_at_the_next_unindented_line` |
| `949–950` (`monitor_assign`: invalid triage JSON caught) | `test_a_malformed_triage_answer_is_treated_as_unusable` |
| `960->961`, line 961 (`edit`: entry already present → no second edit) | `test_an_entry_already_added_by_another_merge_needs_no_second_edit` |
| `984->985`, line 985 (`monitor_assign`: nudge loop runs) | `test_nudged_issues_get_a_requeue_comment` |
| `992->991` (`give_back`: a task that is not resettable is skipped) | `test_a_reset_leaves_every_other_task_alone` |
| `1062->-1062` (`slot_config`: no matching slot → StopIteration) | `test_an_unknown_slot_number_has_no_config` |
| lines 1145–1146 (`slot_manifests_exist`: gh raises CalledProcessError → missing) | `test_a_registry_error_means_the_image_is_missing` |

## Findings

- The worker image has no `pip` and no `coverage` module; the story's suggested
  `pip install --break-system-packages coverage` is not available here. Installed
  `python3-coverage` (6.5.0) with apt instead; the coverage commands then ran normally.
- The full Python suite has 8 failures that exist identically before and after this
  change (verified by diffing the baseline and final failure lists): six `test_check_pack`
  failures whose first line is `opencode.json is out of date: run scripts/gen-opencode.py`
  (the known CI-profile-under-skip-worktree false positive, OPS-CW-003-01) and two
  `test_worker_gate` errors from sandbox fixtures (`integration run failed without fresh,
  usable test results`). Per AGENTS.md these are worker-environment false positives; the
  integrator owns the full gate. No attempt was made to edit a protected file for them.
- The tree already contains committed conflict markers in three unrelated Java files
  (`apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java`, `Hl7Segment.java`, lines around 6–18). They are in HEAD, not introduced
  by this change, and are outside this task's scope; left untouched for the integrator.

## Lessons for workers

- `monitor_assign(directory, ...)` expects the directory that *contains* `triage-<n>.json`
  (pass `.../triage`), not its parent — an easy source of a false "unanswered" result.
- When coverage is split across tasks, measure the report first and filter the JSON to
  the assigned line range; the whole-file percentage stays below 100% until the other
  parts land, which is expected and not a defect of this task.
- This worker image has no `pip`; `apt-get update && apt-get install -y python3-coverage`
  is the working way to get branch coverage for the Python tooling suite.
