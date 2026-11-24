# COVPY-MONITOR-FETCH · monitor tool coverage, fetch part

Story: Script tests — monitor tool coverage, fetch part (one of four parts covering `scripts/monitor.py`).
Requirement: **OPS-06**. Scope (only file changed): `scripts/tests/test_monitor_fetch_coverage.py`.

## Summary

Added the first test suite for `scripts/monitor.py`, covering the data layer owned by this task:
the `CALLS` `gh` command table, `spawn`, `collect`, `log_timing`, `merge_runs`, `fetch` with its
nested `put`/`timed`/`load`/`add_jobs`, the churn/compare step, caching and error handling, plus
the adjacent pure helpers (`blank`, `ts`, `span`, `clip`, `mid`, `failure_parts`, `state`,
`parse_tasks`, `job_summary`, `failure_detail`, `cells`, `clipline`, `box`, `fit_col`) —
lines 1-316. The module is loaded with a controlled `sys.argv` and a stubbed `curses.wrapper`;
all `gh` calls are faked with canned JSON and all threads are made synchronous. No network,
no real terminal, no production code change, synthetic data only.

## Affected requirements

OPS-06 (synthetic fixtures, CI policy checks, deterministic tooling). No patient data, credentials
or real identifiers appear anywhere in the tests.

## Risk class

Routine — test-only addition; no runtime behavior, contract or data path changes.

## Review owner

Senior Python Tooling (`orevia-senior-python`) — bounded read-only review completed:
**APPROVE, no must-fix items** (evidence source: reviewer report; the reviewer did not re-run
commands — all commands were executed by this worker and are listed below). Non-blocking
suggestions were applied: a third module load with `AUTOSTART=7` so the line-5 ternary assertion
discriminates, `assertIsNone` on the swallowed-OSError path, concrete padding assertions in
`fit_col`, removal of a stray comma, and a new test for the `not want and jobs is None`
sub-case of `add_jobs`.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback/forward-fix.

## Authorization boundaries and audit events

None. The tests exercise only data-layer functions with synthetic JSON; no tenant, role or
patient-link scope and no audit events are added or changed.

## Migration and rollout considerations

None.

## Test evidence

Entry-point-to-test trace: controlled module import (`curses.wrapper` stubbed) → `monitor.fetch` →
`spawn` (faked by `FakeGh.spawn`) → `collect`/`merge_runs`/`add_jobs` → assertions on the returned
`out` dict, the `emit` parts, the recorded `gh` argv and the fake process kill list. Every helper
is also called directly through its public name.

Commands (all run from the repository root) with final lines:

1. `python3 -I -m unittest discover -s scripts/tests -p "test_monitor_fetch_coverage.py"`
   → `Ran 27 tests in 0.004s` / `OK`
2. `python3 -I -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -p "test_monitor_fetch_coverage.py"`
   → `Ran 27 tests in 0.006s` / `OK`
3. `python3 -m coverage report -m --include=scripts/monitor.py` (report below)
4. `python3 -m coverage json -q -o /tmp/mon.json` + branch-line filter on lines ≤ 320
   → `missing/partial branch arcs at lines <= 320: []`, `missing lines <= 320: []` (tool evidence)
5. `python3 -I -m unittest discover -s scripts/tests -q`
   → final re-run `Ran 479 tests in 13.447s` / `FAILED (failures=3)`. All 3 are `test_check_pack.py`
   failures from the known `opencode.json` CI-profile artifact (see Findings); my 27 tests pass.
6. `bash scripts/python-coverage.sh` → exit 1 **at the unittest step** (same 3 pack failures), so
   its coverage-diff step never ran. Ran that step directly:
   `COVERAGE_FILE=/tmp/covdata python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' -m unittest discover -s scripts/tests -q`
   then `python3 -m coverage json -q --omit='scripts/tests/*' -o /tmp/report.json` and
   `python3 -I scripts/python_coverage.py /tmp/report.json --base origin/main`
   → `python coverage: every changed script line is covered` (exit 0).
7. Policy checks (all passed, tool evidence): `python3 -I scripts/check-wiring.py`,
   `check-migrations.py --require-baseline`, `check-names.py`, `check-docs.py`, `check-secrets.py`.
   `check-pack.py` and `gen-opencode.py --check` fail only with `opencode.json is stale` (artifact below).

Evidence source: executed tool commands (coverage, unittest, git, policy scripts). No reviewer,
simulated-user or human-participant evidence. `bash scripts/check.sh` and `quick-check.sh` were
**not** run: line 25-40 of `scripts/check.sh` runs `apps/api/check.sh` (full `mvn verify` with
JaCoCo/OWASP/SpotBugs) and `db/check.sh` (PostgreSQL integration), which AGENTS.md Coverage rule
8 and the story explicitly forbid a worker from running; the fast policy + diff-coverage checks
above cover this test-only change.

## Coverage

Command (my file alone, as the story requires):
```
python3 -I -m coverage run --branch --source=scripts \
  -m unittest discover -s scripts/tests -p "test_monitor_fetch_coverage.py"
python3 -m coverage report -m --include=scripts/monitor.py
```
Result:
```
Name                 Stmts   Miss Branch BrPart  Cover   Missing
scripts/monitor.py     606    369    411      1    37%   321-619, 630-639, 644, 651-663,
                                                        669-674, 679-680, 684-688,
                                                        692-823, 827
```
The whole-module number is 37% because the **other three tasks** own lines 317-829 (compose,
once/TUI/CLI). For my part alone, the coverage JSON reports **zero missing lines and zero
missing/partial branch arcs at lines ≤ 320 — i.e. 100% line and branch coverage of lines 1-316**;
the single partial branch in the report is `[826, 827]` (`if ONCE:` true side, another task's part).
The only changed file is a test file under `scripts/tests/*`, which `--omit` excludes from the
diff gate; `python_coverage.py` confirms no uncovered changed script lines.

New branches/paths covered by this file and the test that takes each side:

| Code path | Test(s) covering both outcomes |
|---|---|
| line 5 AUTOSTART ternary (`len(argv)>4` / else) | module import: `monitor` (5 args, `0`), `_monitor_autostart` (5 args, `7`, asserts value 7) vs `_monitor_defaults` (4 args) |
| line 826 `if ONCE` false side (true side owned by another task) | module import with `ONCE=0`, `curses.wrapper` stubbed |
| `spawn` argv/stream settings | `test_spawn_prepends_gh_and_forwards_output_settings` |
| `collect` JSON vs text key, `out or "[]"`, exception + `kill` | `test_collect_parses_json_and_keeps_text_keys_verbatim`, `test_collect_kills_a_timed_out_process_and_keeps_its_slot` |
| `log_timing` sorted rows, empty rows, `except OSError` | `test_log_timing_writes_slowest_first_and_handles_an_unwritable_log` |
| `merge_runs` dedup, sort, missing `createdAt` | `test_merge_runs_deduplicates_by_id_and_sorts_newest_first` |
| `fetch` `emit` None / callable | `test_fetch_loads_every_section_and_merges_runs`, `test_fetch_emits_each_part_as_it_arrives` |
| `timed` note: FAILED / N chars / N items | full-success test, failures test, JSON-vs-text sections |
| load `val is None`: put-None vs drop (`commits`/`runs`) | `test_fetch_keeps_previous_values_and_drops_unavailable_sections` |
| `repo` strip | `test_fetch_loads_every_section_and_merges_runs` |
| churn `if commits and commits[-1]['parent']` true/false; compare dict vs non-dict | `test_fetch_compares_the_day_when_the_oldest_commit_has_a_parent`, `test_fetch_loads_every_section_and_merges_runs`, `test_fetch_keeps_no_churn_when_compare_returns_a_non_object`, `test_fetch_with_no_commit_lines_still_reports_zero_churn` |
| `runs`/`runs_more` merge with/without prior runs | `test_fetch_loads_every_section_and_merges_runs` |
| `add_jobs`: live, recent-failed, old-failed, no-`updatedAt`, success, duplicate-claimed, early return | `test_fetch_loads_every_section_and_merges_runs` |
| `add_jobs` early-return guard with `jobs is None` vs `jobs` already present | `test_fetch_with_only_finished_runs_publishes_an_empty_jobs_map`, `test_fetch_loads_every_section_and_merges_runs` |
| `add_jobs` job filter dict / None / list and `(j or {})` | `test_fetch_loads_every_section_and_merges_runs` |
| `blank` text/jobs-churn/list; `ts` Z/no-Z/missing; `span` clamp/min/hour/day; `clip` short/long/n<3/non-str; `mid` short/long/small-n/non-str; `failure_parts` job+step/no-step/no-failure/Run-prefix; `state` conclusion/status/blank | `SmallHelperTests` (7 tests) |
| `parse_tasks` three statuses, agent line, indent reset, `text=None`; `job_summary` >3 running, missing `startedAt`, queued, bad, empty; `failure_detail` with/without steps, none | `TodoAndJobSummaryTests` (3 tests) |
| `cells` flex/fixed width, rjust/ljust, separators; `clipline` break/no-break; `box` gray mapping, badge, fill 0, short rows, small height; `fit_col` avail None, free>0, break, padding, loading, empty, overflow | `LayoutHelperTests` (4 tests) |

## Clinical and architecture review

Not applicable: test-only change, no signed data, cross-organization sharing, patient
association, prescribing or critical-results behavior.

## Findings

1. **`opencode.json` stale failures are a worker-environment artifact, not this change.**
   `git ls-files -v opencode.json` → `S` (skip-worktree); HEAD copy is 38 387 bytes vs worktree
   39 700 bytes (CI profile). `gen-opencode.py --check` and `check-pack.py` report
   `opencode.json is stale`, and the 3 `test_check_pack.py` failures are exactly that. This is
   OPS-CW-003-01 already recorded in MEMORY.md / BUGS.md. I did **not** regenerate or edit the
   file. Evidence source: executed git/coverage/unittest commands.
2. **Tooling bootstrap.** `pip` and `ensurepip` are absent in this worker; installed coverage
   6.5.0 with `apt-get install -y python3-coverage`. No dependency or requirement file was added
   to the repository.
3. **Whole-module coverage is intentionally incomplete here.** Lines 317-829 (compose, once,
   TUI/CLI) are owned by the other three fetch-part tasks; this task's scope is lines 1-316.
4. **`collect` turns empty stdout into `[]`** (`json.loads(out or "[]")`). To reach the
   `j is None` branch of `add_jobs`, the fake job process must emit the JSON literal `null`, not
   empty output. This is a fixture detail, not a production defect.
5. Full `scripts/check.sh`/`quick-check.sh` deliberately not run (rationale under Test evidence).

## Lessons for workers

- `scripts/monitor.py` reads `sys.argv` and starts `once()` or the curses TUI at import: load it
  with a controlled argv and `mock.patch.object(curses, "wrapper", ...)` or the import starts a
  real terminal.
- Patch `monitor.threading` with `SimpleNamespace(Thread=SyncThread, Lock=threading.Lock)` to make
  `fetch`'s parallel loads deterministic without starting real threads.
- Use JSON `null` (not empty stdout) to fake a failed/absent job result, because `collect`
  maps empty output to `[]`.
- In this worker image the CI-profile `opencode.json` sits under skip-worktree; `check-pack` /
  `gen-opencode --check` failures are environmental — never regenerate it from a swarm worker.
- A script-only worker should run the fast policy checks plus the `python_coverage.py` diff step,
  not `scripts/check.sh` (it invokes the ten-minute Maven verify and PostgreSQL integration).
