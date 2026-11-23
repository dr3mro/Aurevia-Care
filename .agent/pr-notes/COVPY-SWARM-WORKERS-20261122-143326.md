# COVPY-SWARM-WORKERS — swarm.py coverage, workers part

## Summary

Added `scripts/tests/test_swarm_workers_coverage.py`, a new test-only module that
covers the five previously uncovered lines/arcs in `scripts/swarm.py` lines
1250–1850 (the worker-facing half: task parsing, secret/docs sanitising, task
status edits and claim updates). No production script, threshold, existing test
or tracker was changed. The file is synthetic-data only and never calls the
network or a real `gh`/`git push`.

## Affected requirements

OPS-06 (synthetic fixtures, CI policy checks and reproducible delivery tooling).
This is a test-coverage task shared by five swarm tasks; the other four own the
remaining line ranges.

## Risk class

Routine — a new isolated test file with mocked boundaries; no production code,
contract, migration or deployment change.

## Review owner

Senior Python Tooling (`smartclinic-senior-python`) and Test Engineer
(`smartclinic-test-engineer`). Evidence is from executed tool commands, not a
reviewer, simulated user or human participant.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None changed. Tests mock `authed_git`, `git`, `todo_read`/`todo_write`,
`_checker` and `time.sleep`; no token is read and no push is performed.

## Migration and rollout considerations

None. Test-only change; no rollout or production access.

## Test evidence

Baseline measurement (before adding tests), as the story requires:

- `python3 -m coverage erase && python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
- `python3 -m coverage report -m --include=scripts/swarm.py`
- Result: `scripts/swarm.py 2419 stmts, 152 miss, 1245 branch, 62 BrPart, 93%`.
  Missing items inside 1250–1850 were `1334->1318`, `1531-1532`, `1751`,
  `1836`, `1841`. (This "red" coverage report is the fault-detection evidence;
  there is no production change, so no failing unit test is introduced.)

After adding the tests:

- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_workers_coverage.py' -v`
  → `Ran 5 tests in 0.003s` / `OK`.
- `python3 -m coverage erase && python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  → exit 1 only because of pre-existing environment failures (see Findings);
  all five new tests passed in that run.
- `python3 -m coverage report -m --include=scripts/swarm.py`
  → `2419 stmts, 147 miss, 1245 branch, 58 BrPart, 93%`; none of the five
  missing items in 1250–1850 remains.
- Coverage JSON check over `scripts/swarm.py` restricted to lines 1250–1850
  (`python3 -m coverage json -q --omit='scripts/tests/*' ...` plus a small
  inline Python filter): `missing lines in range: []`,
  `missing branch arcs in range: []`, `executed branch arcs in range: 257`.
- `python3 -I scripts/python_coverage.py /tmp/opencode/cov-report.json --base origin/main`
  → `python coverage: every changed script line is covered` (exit 0).
- `git merge --no-edit origin/main` → `Already up to date.` (run at start, after
  implementation and before finishing).

Full-suite command `python3 -I -m unittest discover -s scripts/tests` reported
`Ran 1143 tests ... FAILED (failures=6, errors=2)`. None involve this file. All
are documented cloud-worker false positives: `test_check_pack.*` (6) because
`git ls-files -v opencode.json` shows `S` (CI profile under skip-worktree,
OPS-CW-003-01), and `test_worker_gate*` (2) because `apps/api/target/surefire-reports`
does not exist when no Maven build has run in the worker. Per AGENTS.md these
are recorded, not re-diagnosed or "fixed" by editing a protected file.

Frontend checks are not applicable (no `apps/patient-mobile` or
`apps/staff-web` change). `scripts/check.sh` was deliberately not run: it calls
`apps/api/check.sh` → full `mvn verify`, which the task and the daily notes
forbid in a swarm worker (timeout); the integrator and Delivery own that gate.

## Coverage

Coverage command for the touched area: `python3 -m coverage run --branch
--source=scripts -m unittest discover -s scripts/tests` and
`python3 -m coverage report -m --include=scripts/swarm.py`, with the range check
described above. Result for lines 1250–1850: **0 missing lines, 0 missing branch
arcs** (257 executed branch arcs). Whole-file coverage is still 93% because the
other four parallel coverage tasks own the remaining ranges; it reaches 100%
when their test files merge alongside this one.

Each previously uncovered line/arc and the test that now takes it:

| Item | Meaning | Test |
|---|---|---|
| `1334->1318` | `parse_tasks`: unknown field key falls through the known-field check and the loop continues | `test_parse_tasks_ignores_an_unknown_field_and_keeps_reading` |
| `1531-1532` | `redact_secrets`: `_checker("check-secrets")` raises `OSError`/`AttributeError`/`ImportError`, text returned unchanged | `test_redact_secrets_returns_text_when_the_checker_cannot_load` |
| `1751` | `set_task_status`: end pointer walks back over a trailing blank line before inserting fields | `test_set_task_status_keeps_the_blank_separator_after_an_entry` |
| `1836` | `update_todo`: re-raise `CalledProcessError` when the final push attempt fails | `test_update_todo_raises_when_every_push_fails` |
| `1841` | `update_todo`: fall-through `return False` when the attempt loop never runs | `test_update_todo_with_no_attempts_does_nothing` |

No threshold, exclusion, pragma or skip was added; no existing test was weakened.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of
information, tenant boundary or downtime behavior is touched.

## Findings

- The five uncovered items in the assigned range are listed under `## Coverage`;
  all are now covered. No unreachable production line was found in this range,
  so nothing needed removal.
- Pre-existing defect outside this task's scope: `origin/main` (and my branch
  HEAD `1db647eb`) contains committed Git conflict markers in three files:
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`
  (lines 6, 10), `Hl7Parser.java` (14, 18) and `Hl7Segment.java` (10, 14), all
  from `origin/swarm/BUILD-37-469`. `git grep -nE '^(<<<<<<<|>>>>>>>) '` lists
  them. A merge was already clean (`Already up to date`), so these are committed
  content, not an unresolved merge in my worktree. I did not edit them (outside
  scope); the owner/integrator should resolve them and run the Java gate.
- Coverage package was absent in the worker image (`pip`, `python3 -m pip` and
  `ensurepip` all missing). Installed with
  `apt-get update && apt-get install -y python3-coverage` → Coverage.py 6.5.0.
  No dependency was added to the repository.
- Worker-environment false positives are recorded under `## Test evidence`, not
  treated as defects of this change.

## Lessons for workers

- For a line-range coverage task, trust the coverage report over the task's
  prose: the function names in the story were stale, but the missing lines/arcs
  in the numeric range were authoritative. Filter `coverage json` output to
  your range to see exactly what is left.
- `set_task_status` deliberately preserves the blank line between entries; the
  walk-back branch shrinks the replaced slice, it does not delete the separator.
  Assert the blank line remains.
- In a cloud worker without a Maven build, `test_worker_gate*` fails on a
  missing `apps/api/target/surefire-reports`, and a skip-worktree `opencode.json`
  (flag `S`) breaks `test_check_pack*`. Record these once with their final lines
  and move on; do not regenerate protected files or re-diagnose them later.
- When `pip`/`ensurepip` are unavailable, Debian's `python3-coverage` package
  provides `python3 -m coverage` for the measurement step.
