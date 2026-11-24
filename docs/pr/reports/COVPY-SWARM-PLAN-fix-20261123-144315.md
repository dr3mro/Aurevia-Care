# COVPY-SWARM-PLAN — fix pass (2026-11-23 14:43) — swarm.py plan-half coverage

## Summary

Fix pass on the committed work of the first worker. The integrator twice flagged the pull
request "**Draft: the worker did not finish cleanly**". I reproduced the state at the tested
head (`9da42f39`, branch `swarm/COVPY-SWARM-PLAN-1176`, zero commits behind `origin/main`)
and found the committed change sound: the targeted suite is 12/12 green, every changed line
and branch in the assigned range (`scripts/swarm.py` lines 1–700) is covered, and every gate
I can run in this worker passes. The draft flag is explained by the worker's local `check.sh`
failing on six `test_check_pack` cases — the documented OPS-CW-003-01 cloud-worker false
positive (the worktree `opencode.json` is the CI profile installed under `skip-worktree`),
not a defect in the change. **No source, test, script, workflow, tracker or configuration
file was changed in this pass**; the only new file is these notes. The first worker's notes
(`.agent/pr-notes/COVPY-SWARM-PLAN-20261123-131739.md`) are retained as history and remain
the authoritative description of the committed change.

## Affected requirements

OPS-06 (CI policy checks and actionable operational diagnostics). The task file names no
other requirement; the coverage is for the planning half of the swarm tooling.

Acceptance mapping:

| Acceptance (task step) | Evidence |
|---|---|
| 1. Reuse `FakeGh` fixtures, cover only uncovered lines in 1–700 | `Gh` in `scripts/tests/test_swarm_plan_coverage.py` is local to the file (no cross-test import); new test drives `run_event` through the public CLI boundary |
| 2. Measure with branch coverage first | `coverage run --branch --source=scripts` + `coverage json`, filtered to lines ≤ 700 (result below) |
| 3. New test code only in `test_swarm_plan_coverage.py`, synthetic data, no network | One new test method plus the local fake; every `gh` call is mocked; issue URL `https://github.com/example/repo/issues/42` is synthetic |
| 3. Cover every line and branch | `missing_lines = []`, `missing_branches = []` for lines 1–700 (fresh run this pass) |
| 3. Unreachable line → report, do not fabricate | The unreachable trailing `else` was deleted in the committed work under the earlier repair directive; no fabricated return value, no pragma, no skipped test |
| 4. Paste coverage under `## Coverage` | See `## Coverage` below |
| 6. Finish with the step-6 check | `python3 -I -m unittest discover -s scripts/tests` run; six known `test_check_pack` false positives only |

## Risk class

Routine — a synthetic Python tooling test plus removal of unreachable Python helper code.
No clinical data, patient identity, permission, prescribing, release of information, critical
result, contract, migration or deployment is touched.

## Review owner

Senior Python Tooling (`aurevia-senior-python`) for the change; read-only
`aurevia-plan-reviewer`, `aurevia-code-reviewer`, `aurevia-coverage-auditor` and
`aurevia-devsecops` for the quality loop. All reviewers were read-only and simulated;
no real clinical or unit participant was involved.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix needed.

## Authorization boundaries and audit events

None. The test exercises failed-run issue reporting through a scripted `gh`; no token,
credential, patient identifier, real issue or real repository is used. The
`ci-failure`/`needs-human` hand-off for credential and quota failures is unchanged.

## Migration and rollout considerations

None. The committed change is confined to `scripts/tests/` and one dead branch in a Python
helper; the integrator's full `scripts/check.sh` remains authoritative on the merge result.

## Test evidence

Entry point to test trace: `swarm.run_event` (`scripts/swarm.py:520`) → `classify_failure`
(`:381`) → quota proposed-fix branch (`:580-581`) → `record_issue` (`:288`) → fake
`gh("issue", "create")` asserted by
`FailureProposalTests.test_a_quota_failure_proposes_waiting_for_the_quota`. The default
("Inspect the failed step…") branch is exercised by
`test_swarm_issues.py::RunEventTests.test_a_failure_opens_one_issue_naming_the_failed_step`,
and the credentials branch by `RunEventTests.test_a_credentials_failure_needs_a_person`.

Exact commands and final lines (source: executed tool commands in this worker):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_plan_coverage.py' -v`
  → `Ran 12 tests in 0.169s` / `OK` (exit 0).
- `COVERAGE_FILE=/tmp/opencode/cov-plan.dat python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
  → exit 1 solely because six `test_check_pack` cases fail (see below); the coverage data
  file was produced and read by the next command.
- `COVERAGE_FILE=/tmp/opencode/cov-plan.dat python3 -m coverage json -o /tmp/opencode/cov-plan.json -q`
  → exit 0; filtered result in `## Coverage`.
- `python3 -I scripts/python_coverage.py /tmp/opencode/cov-plan.json --base origin/main`
  → `python coverage: every changed script line is covered` (exit 0).
- `python3 -I scripts/check-constraints.py --require-base` → exit 0, no output.
- `bash scripts/quality-gate.sh origin/main .agent/pr-notes/COVPY-SWARM-PLAN-fix-20261123-144315.md`
  → exit 0, no output (after the verdicts below were recorded).
- `python3 -I scripts/check-docs.py` → exit 0, no output.
- `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1308 tests in 27.943s` /
  `FAILED (failures=6)`. All six are `test_check_pack`
  (`GeneratorTests.test_check_mode_detects_drift`, `MainTests.test_cli_entry_point`,
  `MainTests.test_main_reports_success`, `PackTests.test_committed_opencode_json_is_generated`,
  `PackTests.test_repository_pack_is_valid`,
  `PackTests.test_simulated_user_skills_need_no_process_sections`). Zero failures reference
  the changed file.
- `PYTHONPATH=scripts/tests python3 -m unittest test_check_pack.PackTests.test_committed_opencode_json_is_generated -v`
  → `FAILED (failures=1)` with an `opencode.json` content diff. `git ls-files -v opencode.json`
  returns `S` and `git diff -- opencode.json` is empty: `agent-run.sh` installed the CI
  runtime profile under `skip-worktree` (OPS-CW-003-01), a documented worker-image false
  positive. The committed local profile is correct; no protected file was edited.
- `git merge --no-edit origin/main` → `Already up to date.`
  `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no conflict markers.
- Post-merge re-run on the merged tree (as the plan reviewer advised):
  `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_plan_coverage.py' -q`
  → exit 0; `COVERAGE_FILE=/tmp/opencode/cov-postmerge.dat python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -p 'test_swarm*.py' -q`
  + `coverage json` + `python3 -I scripts/python_coverage.py /tmp/opencode/cov-postmerge.json --base origin/main`
  → `python coverage: every changed script line is covered` (exit 0).

`COVERAGE_FILE` was kept under `/tmp/opencode`; the tracked `.coverage` artifact is unchanged.
`python3 -I scripts/check.sh` was deliberately not run in this worker because it delegates to
`apps/api/check.sh` (full `mvn verify`, >10 minutes); the integrator runs the full gate.

## Coverage

Fresh measurement this pass (tool evidence): the `coverage run`, `coverage json` and
`python_coverage.py` commands in `## Test evidence`, with the JSON filtered to
`scripts/swarm.py` lines ≤ 700.

- `missing_lines` for lines 1–700 = `[]`
- `missing_branches` for lines 1–700 = `[]`
- changed-line gate (`python_coverage.py --base origin/main`) = every changed script line covered, exit 0

| Line(s) / arc | Behaviour | Test |
|---|---|---|
| `580->581` (`kind == "quota"`) | a failed run whose log contains "API rate limit exceeded" is classified `quota`; its issue carries the quota proposed fix, the person-must-fix suffix and `ci-failure`/`needs-human`, with no rerun | `FailureProposalTests.test_a_quota_failure_proposes_waiting_for_the_quota` (new in the committed work) |
| `581->582` (default `else:`, new line 582–583) | a failed run matching neither credentials nor quota keeps the "Inspect the failed step…" proposal and the `ci-failure` label without `needs-human` | `test_swarm_issues.py::RunEventTests.test_a_failure_opens_one_issue_naming_the_failed_step` (log "JaCoCo: coverage checks have not been met") |
| `578->579` (`kind == "credentials"`) | the credentials proposal and the human-fix hand-off are unchanged | `test_swarm_issues.py::RunEventTests.test_a_credentials_failure_needs_a_person` |

The unreachable trailing `else` and its "Investigate the failing job/step…" proposal were
deleted in the committed work (per AGENTS.md coverage rule 3) rather than covered with a
fabricated value. No threshold, exclusion or test was weakened or skipped.

## Clinical and architecture review

Not required: no clinical data, patient identity, result, order, prescription, release or
tenant boundary is touched. OPS-06 is operational tooling only.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

All four were run in this fix pass on the committed change. No Critical, Required or
Optional finding was left unresolved. The code reviewer confirmed `classify_failure`
returns exactly `credentials`/`quota`/`unknown` and the reachable behaviour for all three
classes is unchanged. The coverage auditor had no shell in its read-only session and judged
the reported numbers against the source and tests, confirming every changed branch maps to
an asserting test; its one Optional finding was an evidence-attribution correction in the
*earlier* notes (see Findings), which this file already states correctly. DevSecOps
confirmed no credential, PHI, network, new dependency or suppression and that the
person-must-fix hand-off is intact. The plan reviewer confirmed no Required gap and no source
edit warranted in this pass.

## Findings

- The integrator's "did not finish cleanly" carried no failing check or code evidence. The
  publish code (`scripts/swarm.py:2179-2189`) marks a PR draft when `local checks != passed`;
  the first worker's `check.sh` failed on the six OPS-CW-003-01 `test_check_pack` cases. The
  committed change is not the cause, so no source change was made in this pass.
- The committed `scripts/swarm.py` edit is outside the story's original `scope`
  (`scripts/tests/test_swarm_plan_coverage.py` only). The first worker's notes record that a
  repair directive in the earlier round explicitly authorized deleting the unreachable
  branch; the deletion is the only way to reach 100% branch coverage without a fabricated
  value (AGENTS.md coverage rule 3), and no alternative exists because a `task-requests`
  block cannot name `scripts/*.py`. I kept it as committed rather than rewriting it in a fix
  pass with no contrary directive.
- Earlier `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` lines in the hand-off
  text are captured test-log fixtures from the swarm tooling, not gate results on this
  branch; `git status` is clean apart from this notes file and I did not edit `TODO.md`.
- The PR-description snapshot in the hand-off text was truncated mid-sentence
  ("Production dead code (no"); the complete notes file is on disk at
  `.agent/pr-notes/COVPY-SWARM-PLAN-20261123-131739.md`. A truncated snapshot is not evidence
  of unfinished production work.
- Evidence-attribution correction from the coverage auditor: the earlier notes attributed
  the default `else` coverage to `GhRetryTests`/`GreenRunIssueTests`/`HandoffIssueTests`/
  `WatchdogRepairIssueTests`, which do not reach `run_event`'s failure path. The `unknown`
  branch is actually exercised by `test_swarm_issues.py::RunEventTests`
  (`test_a_failure_opens_one_issue_naming_the_failed_step` and siblings). This file names the
  correct tests in `## Test evidence` and `## Coverage`; the branch was and remains covered.
- No `task-requests` block: no new task is needed to finish this story.

## Lessons for workers

- On a "did not finish cleanly" fix attempt with no failing check, verify the committed work
  before rewriting it: re-run the focused suite, the individual gate stages and the coverage
  for the assigned line range. Green work usually means the earlier run ended before emitting
  its completion signal, not that the source is broken.
- In a cloud worker, `git ls-files -v opencode.json` returning `S` and a `test_check_pack`
  content diff explains six suite failures without any change to the work (OPS-CW-003-01).
  Confirm once with `git show HEAD:opencode.json`, run the remaining gate stages individually
  and do not regenerate or commit that file.
- A branch after an exhaustive `if/elif` over a closed return set is dead code. Delete it;
  never patch the classifier to return an impossible value just to colour the arc.
- Keep `COVERAGE_FILE` outside the repository so repeated measurement never dirties the
  tracked `.coverage` artifact.
