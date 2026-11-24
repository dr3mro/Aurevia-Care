# COVPY-MONITOR-COMPOSE — monitor screen-model coverage (compose part)

## Summary

Added one new test-only file, `scripts/tests/test_monitor_compose_coverage.py` (13 tests), that
exercises the screen-model half of `scripts/monitor.py` — `compose()` and its nested layout helpers —
through the public `compose(data, W, H, alarm_ids, busy, loading)` entry point. The module is loaded
once with a stub `curses.wrapper` and benign `sys.argv`, and the clock is frozen to a synthetic
`2026-11-21T12:00:00Z`; every run, job, task, PR, issue and count is synthetic. No real terminal,
network, `gh` call, patient data or credential is used. No production file, threshold, exclusion or
existing test was changed.

Scope stayed at `scripts/tests/test_monitor_compose_coverage.py` only.

## Affected requirements

- OPS-06 — separate test tooling, synthetic fixtures and CI policy checks. This story adds the
  compose-part regression tests for the ops monitoring script (requirement line 139 of
  `PROJECT_V1_SPEC.md`).

Acceptance mapping: public `compose()` is reached with empty, short/wide, rich, loading, alarm,
failed-run, live-run and merged-PR screens; assertions check stat cards, history strip, pipeline
meters, In Progress / Recent Runs / Failed / Next Up / Merged / PR / Issues panels and alarm styling.

## Risk class

Routine (test-only change; no runtime behaviour, contract, migration or dependency touched).

## Review owner

`aurevia-senior-python` and `aurevia-test-engineer` skills were loaded and applied. No live
reviewer, simulated user or human participant evidence — all evidence below is executed tool output.

## Data changes

None. No migrations, backfills, locks or volume impact.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit-event code is touched.

## Migration and rollout considerations

None.

## Test evidence

Entry point to test trace: `compose(payload, W, H, ...)` in `scripts/monitor.py` walks
`data["runs"/"issues"/"prs"/"merged"/"closed"/"todo"]`, builds `top` stat cards and the PIPELINE
box, then `fit_col(left/right, ...)`; tests flatten the returned `(text, style)` segments and assert
on rendered labels, styles, symbols and panel titles.

Commands actually run (all synthetic data, from the repository root):

1. `python3 -I -m unittest discover -s scripts/tests -p "test_monitor_compose_coverage.py" -v`
   → `Ran 13 tests ... OK` (13 tests, 0 failures, 0 errors).
2. `python3 -I -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -p "test_monitor_compose_coverage.py"`
   → `Ran 13 tests ... OK`.
3. `python3 -I -m coverage report -m --include=scripts/monitor.py`
   → `scripts/monitor.py  606  259  226  5  55%` for the whole module when only this part's tests
   run (the other three parts are owned by separate workers). See Coverage below for the focused
   result on lines 319-620.
4. `python3 -I -m unittest discover -s scripts/tests -q` (full suite, after the change)
   → `Ran 465 tests ... FAILED (failures=4)`. The 4 failures are pre-existing and unrelated:
   three `test_check_pack` failures because the worktree `opencode.json` is the CI profile under
   `skip-worktree` (`git ls-files -v opencode.json` → `S opencode.json`), so pack checks judge that
   profile, not `HEAD`'s local one (documented OPS-CW-003-01 in MEMORY.md / daily notes); and one
   flaky `test_swarm.TwoTierTests.test_integrate_batch_retries_when_main_advanced`, reproduced
   failing alone with `python3 -I -m unittest discover -s scripts/tests -p "test_swarm.py" -k
   test_integrate_batch_retries_when_main_advanced` (`AssertionError: 1 not greater than or equal to 2`).
5. Diff-based gate: generated full coverage JSON and ran
   `python3 -I scripts/python_coverage.py /tmp/opencode/full.json --base origin/main`
   → `python coverage: every changed script line is covered` (exit 0). The only changed file is a
   test under `scripts/tests`, which coverage omits by design.
6. `bash scripts/python-coverage.sh` → exit 1, but only because `set -e` aborts on the same 4
   pre-existing unittest failures above; it never reaches the coverage verdict. The equivalent
   direct checker in step 5 passes.
7. `timeout 280 bash scripts/check.sh` → exit 1, final line
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented
   skip-worktree CI-profile artifact, not a change defect; `opencode.json` is protected and out of
   scope, so it was not regenerated.

Coverage tooling: the environment had no `pip`/`coverage` for `/usr/bin/python3`. Coverage 7.10.7
(cp311 wheel) was downloaded, extracted and placed in `/usr/local/lib/python3.11/dist-packages` so
both `python3 -m coverage` and `python3 -I -m coverage` work. This is a local tool install only; no
dependency was added to the repository.

## Coverage

Focused result for this part (compose, lines 319-620), from the file-alone coverage run:

- `python3 -I -m coverage report -m --include=scripts/monitor.py` → `Stmts 606, Miss 259, Branch
  226, BrPart 5, Cover 55%` for the whole module (other parts untested here).
- Parsed `coverage json` for lines 319-620: 212 of 215 lines executed (98.6%); the only missing
  lines are 470, 490 and 543, all unreachable through the public `compose()` API (see
  `## Why this is not finished`). All reachable branches in this part are taken; the only partial
  branch arcs, `469 -> 470` and `542 -> 543`, belong to those same dead-code lines.

Representative branch → test mapping (every reachable branch in the part is named):

| Branch / path | Test that takes it |
|---|---|
| no runs → `rate 0.0`, `median 0`, `day_ok 1.0`, `main unknown`, `pace 0` → ETA `-` | `test_empty_screen_at_natural_height` |
| runs present → success rate, median, `main` found, wave found, pace/ETA | `test_rich_screen_wide_cards_and_all_panels` |
| `jobs`/`churn`/`total` present vs absent; `archive` present vs absent | `test_rich_screen...` / `test_empty...` |
| card-loading condition true vs false | `test_loading_sections_show_placeholders` / `test_rich...` |
| `H is None` wide cards, `H >= 24`, `H < 24` flat/narrow | `test_empty...`, `test_tall_screen_with_few_items_keeps_cards`, `test_short_screen_uses_narrow_cards_and_flat_meters` |
| stat-card drop loop for CLOSED 24h / MEDIAN RUN | `test_short_screen...` (small W drops) / `test_rich...` (large W keeps) |
| history-strip green / red / gray for success / failure / cancelled | `test_history_strip_and_failed_rows` |
| `failed_rows`: with failing job + step, with job only, alarm styling | `test_history_strip_and_failed_rows` (alarm id 2), `test_rich...` |
| `run_row` narrow vs wide (`iw >= 70`), alarm vs plain failure vs success | `test_short_screen...` / `test_rich...` / `test_history_strip...` |
| `with_detail`: task-id rows, unknown task id, generic fallback, `>1` live jobs (line 532), dividers | `test_live_runs_with_and_without_task_ids` |
| live run with queued job only → `wait` label path | `test_live_run_with_no_live_jobs_uses_wait_label` |
| `merged_row`: `TASK-001:` prefix, `Bump `, plain title | `test_merged_pr_prefix_bump_and_plain_rows` |
| `trigger_of` workflow_run / pull_request / workflow_call / unknown default | `test_trigger_names_and_middle_ellipsis` |
| `mid` shortening of a long branch vs short branch | `test_rich...` / `test_empty...` |
| `isDraft`, `needs-human`, `needs-fix` label branches | `test_rich...` |
| `fit_col` with spare lines and with item overflow (`... +N more`) | `test_many_items_fill_available_lines` (H=18) |
| `loading` on runs only vs todo-only vs none | `test_loading_sections_show_placeholders`, `test_todo_only_loading_meter`, others |
| missing timestamps / empty branch / null sha / unknown conclusion | `test_runs_without_timestamps_and_unknown_states` |

No production line or branch was added or changed by this work; no threshold, exclusion or skip was
introduced and no test was weakened.

## Clinical and architecture review

Not applicable — no clinical workflow, signed data, cross-organization sharing, patient association,
prescribing or critical results. The change is isolated to a Python test fixture.

## Findings

- Three lines in `compose()` cannot be reached through any public input; they are latent dead code
  in `scripts/monitor.py` (out of scope to edit here) and require a maintainer decision to delete:
  line 470 (`run_row`'s `if running:` body — `run_row` is only ever called with `running=False`,
  at line 576, and from the unreachable line 543), line 490 (`detail()`'s body — the nested function
  is defined but never called anywhere in the file), and line 543 (`with_detail`'s `if not running:`
  body — `with_detail` is only invoked through `flat(live, True)`, so `running` is always True).
  Their partial branch arcs are `469 -> 470` and `542 -> 543`. With these three removed, this
  part would be 100%.
- Because all four split tasks may only add tests, whole-file `scripts/monitor.py` cannot reach a
  literal 100% line score until those three dead lines are removed by a maintainer. This should be
  treated as a script defect, not a test gap.
- The full Python suite is red locally for reasons predating this change (worktree CI-profile
  `opencode.json` under skip-worktree; one flaky swarm test). Neither is related to this test file;
  both are reproduced independently above.
- Coverage had to be installed from a downloaded wheel because this worker image has no pip for the
  system Python. No repository dependency changed.

## Lessons for workers

- `monitor.py` reads `sys.argv` and calls `curses.wrapper(tui)` at import: load it under a stub
  `curses` (`wrapper=lambda fn: None`) plus patched `sys.argv`, and freeze `monitor.datetime` with a
  subclass overriding `now()` while inheriting `fromisoformat`, so `ts()` still parses stamps.
- Assert on the flattened `(text, style)` segments returned by `compose`, not on captured stdout;
  `age()`/`failure_parts()` strip prefixes (`"Run "`), so assert on the stripped text.
- This environment has no pip; a cp311 coverage wheel can be fetched and extracted into
  `/usr/local/lib/python3.11/dist-packages` so `python3 -I -m coverage` works. Do not rely on
  `pip install` or on `PYTHONPATH` alone (`-I` ignores it).
- A `scripts/check.sh` or `python-coverage.sh` failure on the worktree `opencode.json` (skip-worktree
  CI profile) or the flaky `test_integrate_batch_retries_when_main_advanced` is environmental; verify
  with `git ls-files -v` / a solo rerun before treating it as your defect.

## Why this is not finished

The reachable screen-model lines in this part are fully covered, but three lines of
`scripts/monitor.py` are unreachable by any input and therefore cannot be covered by tests without
editing the protected script (forbidden by the task scope):

- **line 470** — `when = ("run " if s == "in_progress" else "wait ") + ...` inside `run_row(r,
  running)`. The only call with a literal `False` is the Recent Runs list (line 576); the only other
  call passes `running` from `with_detail`, which is itself only ever invoked with `True`. The True
  branch is dead.
- **line 490** — the body of the nested `detail(text, col)` helper; `grep` finds no call site.
- **line 543** — `return [run_row(r, running)]` in `with_detail` when `not running`; `with_detail`
  is reached only via `flat(live, True)` so `running` is always True.

Exact coverage evidence: `python3 -I -m coverage report -m --include=scripts/monitor.py` reports
`Miss 470, 490, 543` inside 319-620 with partial arcs `469 -> 470` and `542 -> 543`; a parsed JSON
report gives 212/215 lines (98.6%) in this part. Suggested next step for a maintainer: delete those
three dead branches (and the now-unused `detail` helper) in `scripts/monitor.py`, then confirm the
whole file reaches 100% once the FETCH, TUI and CLI parts land. No other line or branch in this
part is uncovered.
