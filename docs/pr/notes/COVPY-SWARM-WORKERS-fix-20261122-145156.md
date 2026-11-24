# COVPY-SWARM-WORKERS — swarm.py worker coverage (fix pass 1)

## Summary

Fix pass for the swarm.py worker-coverage test task. The first run added
`scripts/tests/test_swarm_workers_coverage.py` (5 tests) that cover the five
previously uncovered lines/arcs in `scripts/swarm.py` lines 1250–1850. The code
and tests were verified again in this pass: they pass and the assigned range has
0 missing lines and 0 missing branch arcs. The earlier run stopped before
finishing because its notes file used the wrong filename and had no
`## Review verdicts` section, which `scripts/quality-gate.sh` /
`scripts/check-verdicts.py` require for a non-documentation change. This pass
writes the correctly named notes file with the required verdicts and re-runs the
checks. No production script, threshold, existing test or tracker was changed.

## Affected requirements

OPS-06 (reproducible delivery tooling, synthetic fixtures and CI policy checks).
This is one of five parallel swarm tasks that split `scripts/swarm.py` by line
range; this task owns only lines 1250–1850.

Acceptance mapping:

| Acceptance criterion | Evidence |
|---|---|
| Cover only uncovered lines/arcs in `scripts/swarm.py` 1250–1850 | JSON range filter: missing lines `[]`, missing arcs `[]`, 257 executed arcs |
| Test-only change in the single named `scope` file | `git diff --name-only origin/main...HEAD` shows only the test file plus notes |
| No production change, no existing test change, no suppression/skip/exclusion/threshold change | `scripts/check-constraints.py --base origin/main --require-base` exit 0 |
| Synthetic data, no network, no real `gh`/push | tests mock `authed_git`, `git`, `todo_read`/`todo_write`, `_checker`, `time.sleep` |
| 100% coverage of changed lines | `scripts/python_coverage.py ... --base origin/main` -> every changed script line covered |

## Risk class

Routine — an isolated new test module with mocked boundaries; no production
code, contract, migration, deployment or patient-facing change (docs/WORKFLOW.md).

## Review owner

Senior Python Tooling (`aurevia-senior-python`) and Test Engineer
(`aurevia-test-engineer`). Evidence in this notes file comes from executed
tool commands (primary), from the read-only specialist reviewers listed under
`## Review verdicts` (simulated review), and from no simulated user or human
participant. No clinical or real-patient evidence is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None changed. The tests mock every external boundary (`authed_git`, `git`,
`todo_read`, `todo_write`, `_checker`, `time.sleep`); no token is read, no push
is performed and no audit event is produced.

## Migration and rollout considerations

None. Test-only change; no rollout, production access or deployment gate.

## Test evidence

All commands were run from the repository root.

- `git merge --no-edit origin/main` (at start and before finishing) -> `Already up to date.`
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_workers_coverage.py' -v`
  -> `Ran 5 tests in 0.005s` / `OK`. This is the entry-point-to-test trace: the
  public functions `parse_tasks`, `redact_secrets`, `set_task_status` and
  `update_todo` of `scripts/swarm.py` are the entry points, each driven by a
  synthetic fixture in `scripts/tests/test_swarm_workers_coverage.py`.
- `python3 -m coverage erase && python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  -> exit 1 only because of pre-existing environment failures (see Findings);
  all five new tests passed in that run.
- `python3 -m coverage report -m --include=scripts/swarm.py`
  -> `2427 stmts, 147 miss, 1247 branch, 58 BrPart, 93%`. No missing item lies
  in 1250–1850; the nearest are `1147-1148` and `2008-2009`, owned by other tasks.
- `python3 -I scripts/python_coverage.py /tmp/opencode/cov-report.json --base origin/main`
  -> `python coverage: every changed script line is covered` (exit 0).
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` -> exit 0
  (no suppression, exclusion, skip or weakened threshold added).
- `python3 -I scripts/check-verdicts.py docs/pr/notes/COVPY-SWARM-WORKERS-fix-20261122-145156.md --base origin/main`
  -> exit 0 (all three required verdicts present below).
- `python3 -I scripts/check-docs.py` -> exit 0 (Markdown/notes formatting).
- `bash scripts/quality-gate.sh origin/main docs/pr/notes/COVPY-SWARM-WORKERS-fix-20261122-145156.md`
  -> exit 0 (constraints and verdicts together). This was the gate the first run
  failed; running it locally closes that root cause before the commit.
- Full suite `python3 -I -m unittest discover -s scripts/tests`
  -> `Ran 1177 tests in 29.099s` / `FAILED (failures=6, errors=2)`. None involve
  this file: 6 `test_check_pack.*` failures (worktree `opencode.json` is the CI
  profile under skip-worktree, OPS-CW-003-01) and 2 `test_worker_gate*` errors
  (`apps/api/target/surefire-reports` absent with no Maven build in the worker).
  These are documented cloud-worker false positives (AGENTS.md), recorded not
  re-diagnosed; no protected file was edited to "fix" them.

Frontend checks are not applicable (no `apps/patient-mobile` or `apps/staff-web`
change). The full `scripts/check.sh` was intentionally not run: it delegates to
`apps/api/check.sh` -> full `mvn verify`, which AGENTS.md and this task forbid a
swarm worker from running (over ten minutes, ends the run as a timeout). The
individual policy scripts were run instead; the integrator and Delivery own the
full gate and the 100% Java/database coverage check.

## Coverage

Coverage measurement (Coverage.py 6.5.0, installed from Debian's `python3-coverage`
package), exact commands and results:

1. `python3 -m coverage erase`
2. `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
   — all five new tests passed in this run (overall exit 1 only from the
   environment failures under `## Test evidence`).
3. `python3 -m coverage report -m --include=scripts/swarm.py`
   — `scripts/swarm.py 2427 stmts, 147 miss, 1247 branch, 58 BrPart 93%`;
   no missing item lies in 1250–1850 (nearest: `1147-1148`, `2008-2009`).
4. `python3 -m coverage json -q --omit='scripts/tests/*' -o /tmp/opencode/cov-report.json`
   plus an inline filter of `files['scripts/swarm.py']` to lines 1250–1850:
   `missing lines in range: []`, `missing branch arcs in range: []`,
   `executed branch arcs in range: 257`. **This range filter is the proof for
   the assigned range**; the whole-file number is not.
5. `python3 -I scripts/python_coverage.py /tmp/opencode/cov-report.json --base origin/main`
   — `python coverage: every changed script line is covered` (exit 0).
   Note: `python_coverage.py` scans `^scripts/[^/]+\.py$`, not `scripts/tests/`,
   so this green result complements but does not replace the range-filter proof.

Result for the assigned range: **0 missing lines, 0 missing branch arcs**
(257 executed branch arcs). Whole-file coverage is 93% because the other four
parallel coverage tasks own the remaining ranges; it reaches 100% when their
test files merge alongside this one.

Each previously uncovered line/arc in the assigned range and the test that takes it.
The task prose lists line numbers from the 2026-11-21 baseline; a later
`origin/main` change (the `MAX_PR_LINES` guard) shifted them by +2, so both are given:

| Baseline / current item | Meaning | Test |
|---|---|---|
| arc `1334->1318` | `parse_tasks`: unknown field key falls through the known-field check and the loop continues | `test_parse_tasks_ignores_an_unknown_field_and_keeps_reading` |
| lines `1531-1532` | `redact_secrets`: `_checker("check-secrets")` raises `OSError`/`AttributeError`/`ImportError`, text returned unchanged | `test_redact_secrets_returns_text_when_the_checker_cannot_load` |
| line `1751` / now `1752-1753` | `set_task_status`: end pointer walks back over a trailing blank line before inserting fields | `test_set_task_status_keeps_the_blank_separator_after_an_entry` |
| line `1836` / now `1838` | `update_todo`: re-raise `CalledProcessError` when the final push attempt fails | `test_update_todo_raises_when_every_push_fails` |
| line `1841` / now `1843` | `update_todo`: `for attempt in range(attempts)` takes zero iterations and falls through to `return False` | `test_update_todo_with_no_attempts_does_nothing` |

The current line `1841` (the retry `authed_git("fetch", ...)`) is taken by
`test_update_todo_raises_when_every_push_fails` on its first retry, so both the
retry and zero-iteration paths have a test. No threshold, exclusion, pragma or
skip was added; no existing test was weakened.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of
information, tenant boundary or downtime behavior is touched.

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- The five uncovered items in the assigned range are listed under `## Coverage`;
  all are covered. No unreachable production line was found in this range, so
  nothing needed removal.
- Root cause of the earlier failed finish: the first run's notes file was named
  `docs/pr/notes/COVPY-SWARM-WORKERS-20261122-143326.md` (wrong: no `-fix-`
  segment) and lacked the `## Review verdicts` section required by
  `scripts/check-verdicts.py` for a change that includes a `.py` test file.
  This fix pass writes the correctly named notes file with the verdicts. The
  earlier notes file is left untouched as history; `worker_gate.find_notes_file`
  sorts names, so this `-fix-` file is selected as the newest for the story.
- Pre-existing defect outside this task's scope: `origin/main` contains
  committed Git conflict markers in three files:
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`
  (lines 6, 10), `Hl7Parser.java` (14, 18) and `Hl7Segment.java` (10, 14) from
  `origin/swarm/BUILD-37-469`. The merge was clean (`Already up to date`), so
  these are committed content, not an unresolved merge in this worktree. Not
  edited here (outside scope); the owner/integrator should resolve them and run
  the Java gate. `git grep -nE '^(<<<<<<<|>>>>>>>) '` still lists them.
- Coverage package was absent in the worker image (`pip`, `python3 -m pip` and
  `ensurepip` all missing). Installed with
  `apt-get update && apt-get install -y python3-coverage` -> Coverage.py 6.5.0.
  No dependency was added to the repository.
- Worker-environment false positives are recorded under `## Test evidence`, not
  treated as defects of this change.
- Optional (devsecops reviewer, simulated): `scripts/swarm.py` `redact_secrets`
  fails open when its checker module cannot be loaded and the new test codifies
  that pre-existing behavior. This is not introduced or altered by this
  test-only change and does not block it; changing the fallback to fail closed
  would need a person's decision and a separate story. This work does not need
  that task, so no `task-requests` block is raised.
- Optional (coverage auditor, simulated): the `set_task_status` blank-line
  walk-back at lines 1752–1753 appears output-neutral — `lines[start+1:end] =
  fresh + kept` leaves trailing blank lines in place whether or not `end` is
  walked back — so the full-output assertion cannot distinguish that branch.
  The test still executes it, which satisfies the coverage gate. Deciding
  whether the walk-back is removable would be a production change forbidden by
  this task's scope; recorded for a person, not changed here.

## Lessons for workers

- For a line-range coverage task, trust the coverage report over the task's
  prose: function names in the story can be stale, but the missing lines/arcs in
  the numeric range are authoritative. Filter `coverage json` output to your
  range to see exactly what remains.
- `set_task_status` deliberately preserves the blank line between entries; the
  walk-back branch shrinks the replaced slice, it does not delete the separator.
  Assert the blank line remains.
- A swarm worker's notes file MUST be the exact path in the prompt and MUST
  carry `## Review verdicts` with GO lines from code-reviewer, coverage-auditor
  and devsecops whenever the change includes a non-documentation file; otherwise
  `scripts/quality-gate.sh` fails the task before the PR is finished.
- In a cloud worker without a Maven build, `test_worker_gate*` fails on missing
  `apps/api/target/surefire-reports`, and a skip-worktree `opencode.json` (flag
  `S`) breaks `test_check_pack*`. Record these once with their final lines and
  move on; do not regenerate protected files or re-diagnose them later.
- When `pip`/`ensurepip` are unavailable, Debian's `python3-coverage` package
  provides `python3 -m coverage` for the measurement step.
