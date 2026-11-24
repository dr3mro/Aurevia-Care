# COVPY-MONITOR-CLI — monitor tool coverage, CLI part

## Summary

Added `scripts/tests/test_monitor_cli_coverage.py` (12 tests) covering the CLI / entry-point
slice of `scripts/monitor.py`: argument parsing from `sys.argv`, the module-body `main`
dispatch, the auto-start launch (`start_swarm`) and the program exit paths. No production
code was changed; the other three monitor parts (fetch, compose, TUI) remain owned by their
own tasks. Only synthetic data is used; `gh` and `curses` are mocked and no network or real
terminal is touched.

## Affected requirements

- OPS-06 (CI policy checks, dependency/secret scanning, synthetic test fixtures, 100% testability).

## Risk class

Routine — test-only additive change to a Python tooling script's coverage. No behavior,
contract, data or deployment change.

## Review owner

Senior Python Tooling (`aurevia-senior-python`); QA reviews the coverage evidence.

## Data changes

None. No migrations, backfills, lock/volume impact or rollback.

## Authorization boundaries and audit events

None. The tests mock `gh workflow run swarm.yml` and never dispatch a real wave. No tenant,
role or patient scope. No audit events added or changed.

## Migration and rollout considerations

None.

## Test evidence

All commands run from the repository root on branch `swarm/COVPY-MONITOR-CLI-454` at HEAD
`bcd5aa89` (Python 3.11.2, coverage.py 6.5.0 installed via `apt-get install python3-coverage`
because `pip`/`ensurepip` are unavailable in this worker).

1. Baseline (before adding the file):
   - `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
   - `python3 -m coverage report -m --include=scripts/monitor.py`
   - Result: `scripts/monitor.py 606 stmts, 606 miss, 411 branch, 0%` — matches the story.

2. New suite alone (green):
   - `python3 -I -m unittest discover -s scripts/tests -p "test_monitor_cli_coverage.py" -v`
   - Result: `Ran 12 tests ... OK`.

3. Coverage of my part, run file alone as the story requires:
   - `python3 -I -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -p "test_monitor_cli_coverage.py" -q`
   - `python3 -m coverage report -m --include=scripts/monitor.py` → `45%` overall for the
     shared module (the other three parts are not yet merged); `python3 -m coverage json`
     confirmed **zero missing lines and zero missing branch arcs in every line of my part**.

4. Interference check:
   - `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 464 tests, FAILED (failures=4)`.
   - Removing my file and rerunning → `Ran 452 tests, FAILED (failures=4)`: the same four
     pre-existing failures (`test_check_pack` × 3, `test_swarm.TwoTierTests.…main_advanced` × 1)
     occur without my file. They are unrelated worker-environment artifacts (the
     skip-worktree CI-profile `opencode.json` makes `test_check_pack` see a stale generated
     file; documented in MEMORY.md / daily-note lessons). My file adds 12 passing tests and
     introduces no new failure. My file runs alphabetically after those modules, so it
     cannot influence them.

5. `python3 -I scripts/check-docs.py` → exit 0 (no output).

Entry-point-to-test trace:
- module body argv parsing (`monitor.py:4-5`) → `ArgumentParsingTest` (all six tests);
- module-body dispatch (`monitor.py:826-829`) → `MainDispatchTest` (three tests, once/TUI);
- `start_swarm()` (`monitor.py:677-680`) → `AutoStartLaunchTest` (three tests);
- `once()` static frame (`monitor.py:650-663`) → `MainDispatchTest` (three tests).

## Coverage

Coverage command (file alone, branch mode):
`python3 -I -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -p "test_monitor_cli_coverage.py" -q`
then `python3 -m coverage report -m --include=scripts/monitor.py` and
`python3 -m coverage json -q -o <report> --include=scripts/monitor.py`.

Result for my part — **100% of lines and branches in every line I own** (JSON: none of my
lines appear in `missing_lines`, no branch arc with a source in my lines appears in
`missing_branches`):

| Lines | Responsibility | Tests |
|---|---|---|
| 1-22 (executable: 4-5) | argument parsing / constants | `ArgumentParsingTest.test_parses_interval_recent_and_once_from_positional_arguments`, `test_once_flag_enables_the_static_frame`, `test_autostart_defaults_to_zero_without_fifth_argument`, `test_autostart_is_read_from_the_optional_fifth_argument`, `test_non_integer_interval_is_rejected`, `test_non_integer_autostart_is_rejected` |
| 650-663 | `once()` static frame + blank fallback | `MainDispatchTest.test_once_mode_prints_a_static_frame_and_exits`, `test_once_frame_uses_blank_values_for_failed_sections`, `test_parses_interval_recent_and_once_from_positional_arguments` (bottom-block invocation) |
| 677-680 | `start_swarm()` auto-start launch | `AutoStartLaunchTest.test_success_returns_empty_string`, `test_failure_returns_last_stderr_line`, `test_failure_without_stderr_returns_generic_message` |
| 826-829 | module-body `main` dispatch / exit paths | `MainDispatchTest.test_non_once_mode_starts_the_interactive_tui`, `test_once_mode_prints_a_static_frame_and_exits` |

New/changed branches and the test that takes each side:
- line 5 ternary (`AUTOSTART = int(argv[4]) if len(argv) > 4 else 0`):
  - `len(argv) <= 4` → `test_autostart_defaults_to_zero_without_fifth_argument` (AUTOSTART == 0);
  - `len(argv) > 4` → `test_autostart_is_read_from_the_optional_fifth_argument` (AUTOSTART == 15).
  - Non-integer argv[4] → `test_non_integer_autostart_is_rejected` (ValueError).
- line 4 `int(argv[1])` / `argv[3] == "1"`: `test_parses_interval_recent_and_once_from_positional_arguments` (ONCE False) and `test_once_flag_enables_the_static_frame` (ONCE True); non-int → `test_non_integer_interval_is_rejected`.
- line 654 `if data[k] is None` (inside `once()`):
  - True → `MainDispatchTest.test_once_frame_uses_blank_values_for_failed_sections` (a `None` section becomes its blank value);
  - False → same test (the other, non-`None` sections in the mapping).
- line 679 ternary in `start_swarm()` (`returncode == 0`):
  - True → `test_success_returns_empty_string` (returns `""`);
  - False, stderr has lines → `test_failure_returns_last_stderr_line` (returns last line);
  - False, stderr blank → `test_failure_without_stderr_returns_generic_message` (`or ["gh failed"]` side).
- line 826 `if ONCE:`:
  - False → `test_non_once_mode_starts_the_interactive_tui` (`curses.wrapper(tui)` called once with the real `tui`);
  - True → `test_once_mode_prints_a_static_frame_and_exits` (wrapper not called, static frame printed).

The full `scripts/monitor.py` will reach 100% only when the fetch, compose and TUI test
files from the three sibling tasks are merged; this file alone reports 45% for the shared
module because those parts are still owned by other workers.

## Clinical and architecture review

Not required — test-only change, no clinical data, contracts, boundaries or architecture.

## Findings

- No defect found in `scripts/monitor.py`; every CLI branch was reachable with mocked `gh`,
  mocked `curses.wrapper`, a fake `gh` process and controlled `sys.argv`. No line needed
  listing as unreachable.
- The full `scripts/tests` suite has four pre-existing failures unrelated to this task
  (`test_check_pack` × 3 from the CI-profile `opencode.json` skip-worktree artifact, and one
  `test_swarm` integration flake). Reproduced with my file removed. Left untouched because
  `opencode.json`, `scripts/` and `TODO.md` are outside this task's scope.
- `pip` and `python3 -m pip`/`ensurepip` are unavailable in this worker; `coverage` was
  installed with `apt-get install -y python3-coverage` (6.5.0). Both `python3 -m coverage`
  and `python3 -I -m coverage` work.

## Lessons for workers

- `scripts/monitor.py` parses `sys.argv` and dispatches `once()`/`curses.wrapper(tui)` at
  import time, so any test that imports it must patch `sys.argv` (at least 3 entries) and
  `curses.wrapper` before `exec_module`. Loading an isolated copy with
  `importlib.util.spec_from_file_location` + a unique module name avoids clobbering the
  shared `monitor` object used by sibling test files.
- With `--branch`, coverage.py does not emit an arc for a ternary expression; prove both
  sides of line 5 with assertions on `AUTOSTART`, not by looking for branch arcs.
- To tell pre-existing red checks apart from a regression, rerun the suite with the new
  file temporarily moved out — the failure count did not change (452 vs 464 tests, same 4).
