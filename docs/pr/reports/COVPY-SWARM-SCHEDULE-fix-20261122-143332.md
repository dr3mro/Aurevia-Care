# COVPY-SWARM-SCHEDULE — fix attempt 1 (`swarm.py` schedule-slice coverage)

## Summary

The story's deliverable is a single new test-only file,
`scripts/tests/test_swarm_schedule_coverage.py`, which closes the previously
uncovered lines and branches of `scripts/swarm.py` in the assigned range
(lines 700–1250: issue-monitor selection, task status resets, monitor
assignment and slot-image readiness). The first worker wrote that file; this
fix pass re-verifies it from a fresh merge of `origin/main`, confirms 100%
coverage of the whole assigned range, and records clean, reproducible evidence.

No production code, existing test, coverage threshold, exclusion or skip was
changed. All fixtures are synthetic; `gh` is replaced by a local fake and no
network, token, credential or patient data is used.

Why the parent pull request was a draft: the parent worker's local "checks"
step ran the full `scripts/tests` suite, which fails on 8 pre-existing,
environment-only cases in this cloud worker (see Findings). The test file
itself is green and fully covers the assigned range. This fix pass makes the
evidence explicit; the integrator runs the authoritative full gate in its own
environment and hands the pull request back.

## Affected requirements

OPS-06 (swarm tooling). Test-only change; no product requirement behaviour changed.

## Risk class

Routine — one new synthetic test file; no runtime, contract, schema or gate change.

## Review owner

aurevia-senior-python (bounded review requested for this fix pass) and
aurevia-test-engineer. Advisory only; no production change.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit-event code was touched.

## Migration and rollout considerations

None.

## Test evidence

All commands run from the repository root. Environment: Python 3.11.2;
coverage.py 6.5.0 (installed via `apt-get update && apt-get install -y
python3-coverage` because this worker image has no `pip`).

- `git merge --no-edit origin/main` → `Already up to date.` (exit 0); working
  tree clean before and after; no merge in progress.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_schedule_coverage.py' -q`
  → final lines `Ran 10 tests in 0.013s` / `OK` (exit 0).
- `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
  → exit 1 (the 8 pre-existing full-suite failures listed in Findings; the 10
  new tests are among those that pass).
- `python3 -m coverage json -o /tmp/opencode/cov.json -q` → exit 0.
- Filter of `/tmp/opencode/cov.json` for `scripts/swarm.py` with
  `700 <= line <= 1250`: `missing lines 700-1250: []`,
  `missing branches 700-1250: []`. Whole-file summary at this commit:
  `percent_covered 93.61` (the other four split tasks own the other ranges,
  which is expected).
- `python3 -I scripts/python_coverage.py /tmp/opencode/cov.json --base origin/main`
  → `python coverage: every changed script line is covered` (exit 0). A new
  file under `scripts/tests/` is not a changed production script line, so the
  changed-line gate is satisfied.
- `python3 -I scripts/check-docs.py` → exit 0 (no output).

Entry point → test trace (public function → test):
- `monitor_select()` → `MonitorSelectCoverageTests.test_an_issue_naming_an_unknown_task_is_nudged`
- `reset_attempts()` → the three `ResetAttemptsCoverageTests` cases
- `monitor_assign()` → the four `MonitorAssignCoverageTests` cases
- `slot_config()` → `SlotImageCoverageTests.test_an_unknown_slot_number_has_no_config`
- `slot_manifests_exist()` → `SlotImageCoverageTests.test_a_registry_error_means_the_image_is_missing`

The full `scripts/check.sh` was not run: AGENTS.md forbids swarm workers from
running it because it delegates to `apps/api/check.sh` (full `mvn verify`,
>10 minutes) and would end this run as a timeout. The integrator and Delivery
own that gate.

## Coverage

Command and result:
`python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
followed by `python3 -m coverage json -o /tmp/opencode/cov.json -q`. From that
JSON, missing lines and missing branches of `scripts/swarm.py` filtered to
`700 <= line <= 1250` are both **empty**. The changed-line gate
(`python3 -I scripts/python_coverage.py /tmp/opencode/cov.json --base origin/main`)
also passes. Only the assigned range is in this task's scope.

Each previously uncovered line/branch in that range and the test that takes it:

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

No new production branch was added by this change (test-only), so there is no
production branch left without a test.

## Clinical and architecture review

Not required. No signed data, cross-organization sharing, patient association,
prescribing or critical results.

## Findings

- The parent pull request (#897) was drafted solely because its local "checks"
  step failed on 8 **pre-existing, environment-only** failures in the full
  `scripts/tests` suite. Diffing a run against the baseline shows the same 8
  failures with or without the new test file:
  - 6 `test_check_pack` failures (`test_check_mode_detects_drift`,
    `test_cli_entry_point`, `test_main_reports_success`,
    `test_committed_opencode_json_is_generated`, `test_repository_pack_is_valid`,
    `test_simulated_user_skills_need_no_process_sections`) whose root cause is
    `opencode.json is out of date: run scripts/gen-opencode.py`. Verified
    `git ls-files -v opencode.json` → `S` (skip-worktree): the CI runtime profile
    is layered over the worktree file by `agent-run.sh`. This is the documented
    false positive OPS-CW-003-01; `git show HEAD:opencode.json` is the correct
    local profile and the committed file is not stale.
  - 2 `test_worker_gate` errors (`test_integration_failure_still_fails_the_gate`,
    `test_maven_failure_with_failing_reports_still_fails`) with
    `FileNotFoundError: [Errno 2] No such file or directory:
    'apps/api/target/surefire-reports'` — the sandbox worker has never run the
    Maven integration suite that creates that directory. Known worker-image
    sandbox limit.
  Per AGENTS.md these are worker-environment false positives; no protected
  file was edited for them. The integrator owns the full gate in its proper
  environment.
- `bash scripts/python-coverage.sh` therefore exits 1 at its full-unittest
  stage, but the changed-line coverage gate itself passes
  (`python coverage: every changed script line is covered`, exit 0) and the
  assigned range is at 100%.
- Three unrelated Java files in `HEAD` and `origin/main` still contain committed
  conflict markers: `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`
  (lines 6–10), `Hl7Parser.java` (14–18) and `Hl7Segment.java` (10–14), all
  `>>>>>>> origin/swarm/BUILD-37-469`. They predate this change and are outside
  this task's `scope`; left untouched for the integrator. A `git grep` check
  confirms these are the only markers and they are identical in `origin/main`.
- A bounded `aurevia-senior-python` review found one weak assertion:
  `test_a_reset_leaves_every_other_task_alone` seeded the "other" task `CW-002`
  with `attempts: 0` and asserted it stayed `"0"`, which passes even if a reset
  wrongly rewrote every task. Fixed in this pass by seeding `CW-002` with
  `attempts: 7` and asserting it is still `"7"` after resetting `CW-001`. The
  review confirmed no uncovered listed branch, no real data/network/PHI/token
  use and no clean-CI failure; the redundant `slot_config` StopIteration test
  was kept because it is the named test for branch `1062->-1062` in the
  coverage table.
- Apart from that strengthened assertion, no production change was needed: the
  first worker's test file already reaches 100% of the assigned range. The fix
  is the clean evidence, the corrected assertion and the required notes path.

## Lessons for workers

- `monitor_assign(directory, ...)` expects the directory that *contains*
  `triage-<n>.json` (pass `.../triage`), not its parent — an easy source of a
  false "unanswered" result.
- When coverage is split across tasks, measure the report and filter the JSON
  to the assigned line range; the whole-file percentage stays below 100% until
  the other parts land, which is expected and not a defect of this task.
- In this cloud worker there is no `pip`; use
  `apt-get update && apt-get install -y python3-coverage` to get branch
  coverage.
- A test-only new file under `scripts/tests/` never fails the changed-line
  coverage gate (`SCRIPT` in `scripts/python_coverage.py` matches only
  `scripts/<name>.py`); do not edit a production script to "help" coverage.
- The full `scripts/tests` suite cannot pass in a worker whose `opencode.json`
  is under skip-worktree and which has no `apps/api/target/surefire-reports`.
  Record the exact failing command and final line, run the targeted suites and
  coverage analysis individually, and leave the full gate to the integrator.

## Why this is not finished

The story's code deliverable **is finished**: 10 tests pass and the assigned
range is at 100% line and branch coverage. The only check still red is the
full `scripts/tests` suite, which fails on the 8 pre-existing environment
false positives above (command: `python3 -I -m unittest discover -s scripts/tests -q`;
first cause: `opencode.json is out of date` with `git ls-files -v opencode.json`
showing `S`; second cause: `FileNotFoundError: 'apps/api/target/surefire-reports'`).
Most likely cause: this cloud worker applies the CI runtime profile under
skip-worktree and has not run the Maven integration suite that creates the
Surefire directory. What was already tried: confirmed the failures are identical
before and after the new test file; ran the new tests and the coverage analysis
individually; ran the changed-line coverage gate and the docs check, both green.
Exact next step: the integrator runs `scripts/check.sh` / Delivery in its proper
environment (committed local `opencode.json`, PostgreSQL and a prior Maven run),
confirms the full gate, clears `needs-fix` and marks the pull request ready. No
further code fix is available or appropriate from this worker.
