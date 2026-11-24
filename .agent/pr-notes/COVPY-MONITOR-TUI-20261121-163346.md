# COVPY-MONITOR-TUI — monitor tool coverage, curses/TUI part

## Summary

Added `scripts/tests/test_monitor_tui_coverage.py` (new file only) covering the curses half of
`scripts/monitor.py`: the `tui` loop, its nested `part`/`refresh`/`kick`/`put`, palette setup, the
loading screen, key handling, the auto-refresh clock, failure alarms and beeps, autostart,
`swarm_idle`, `start_swarm`, `beep`, `ansi_code`, `AnsiMap` and `once`. Curses is a fake module and
the screen is a stub that records `addstr`; `fetch`, `compose`, `time`, threads, `gh` calls and
subprocesses are stubbed. No real terminal, no network, synthetic data only. The script under test
and all existing tests are unchanged.

## Affected requirements

- OPS-06 — tooling test coverage for the swarm monitor; this is the TUI quarter of the four-way
  split (`COVPY-MONITOR-FETCH`/`-COMPOSE`/`-TUI`/`-CLI`, which together take monitor.py to 100%).

Acceptance mapping: story steps 1–4 (measure first, tests only in the scoped file, cover every
line/branch of this part, paste coverage) are met; step 6 checks are recorded under Test evidence.
Scope: only `scripts/tests/test_monitor_tui_coverage.py` was added.

## Risk class

Routine (test-only change; no production, contract, migration or deployment file touched).

## Review owner

Senior Python Tooling (`aurevia-senior-python`) and the Tester/QA reviewers of the monitor
coverage epic. No clinical reviewer needed.

## Data changes

None. No migration, backfill or schema change.

## Authorization boundaries and audit events

None. The test fakes `gh workflow run` via a stubbed `start_swarm` and never authenticates or
calls the network; no tenant, role or patient scope and no audit event is changed.

## Migration and rollout considerations

None. Test-only addition.

## Test evidence

Commands run from the repository root (Python 3.11.2 locally; coverage.py 6.5.0 installed via
`apt-get install -y python3-coverage` because this image has no pip):

- `python3 -I -m unittest discover -s scripts/tests -p "test_monitor_tui_coverage.py" -q`
  → `Ran 25 tests in 0.02s` / `OK`.
- `COVERAGE_FILE=/tmp/opencode/cov_tui python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' -m unittest discover -s scripts/tests -p "test_monitor_tui_coverage.py" -q`
  → `Ran 25 tests ... OK`.
- `python3 -I -m unittest discover -s scripts/tests -q`
  → `Ran 477 tests in 20.691s` / `FAILED (failures=4)`. All four are pre-existing and unrelated to
  this change; see Findings. My file's 25 tests are green inside this run.
- `bash scripts/python-coverage.sh`
  → `Ran 477 tests ... FAILED (failures=4)` — the same four unrelated failures; the changed-line
  check never reports an uncovered added script line (this change adds no `scripts/*.py` line).
- `python3 -I scripts/check-docs.py && python3 -I scripts/check-secrets.py && python3 -I scripts/check-names.py`
  → all exit 0.
- `python3 -I -m coverage report -m --include=scripts/monitor.py` and a JSON range query (exact
  output under Coverage).

Entry point → test trace: `curses.wrapper(tui)` (module bottom) → `tui(stub_screen)` → scripted
`Screen.getch()` keys → assertions on `Screen.addstr_calls`, fake `curses.beep_count`, recorded
`init_pair` calls, `FakeThread.created`, and the captured `fetch`/`compose` calls. The static entry
`once()` is called directly with `fetch`/`compose`/`get_terminal_size` stubbed and stdout captured.

`bash scripts/check.sh` was intentionally NOT run: line 27 calls `apps/api/check.sh`, whose only
command is `mvn -q -B verify` (full integration tests, OWASP, SpotBugs and JaCoCo). The story
prohibits the full verify and integration suite for a worker; the integrator and Delivery run it.
The script-relevant checks above were run instead.

## Coverage

Command (this file alone, as the story requires):

```
COVERAGE_FILE=/tmp/opencode/cov_tui python3 -m coverage run --branch --source=scripts \
  --omit='scripts/tests/*' -m unittest discover -s scripts/tests \
  -p "test_monitor_tui_coverage.py" -q
COVERAGE_FILE=/tmp/opencode/cov_tui python3 -m coverage report -m --include=scripts/monitor.py
```

Final lines:

```
Name                 Stmts   Miss Branch BrPart  Cover   Missing
scripts/monitor.py     606    387    411      5    33%   ... 321-619, 827
TOTAL                  606    387    411      5    33%
```

The 33% is expected when only a quarter of the module is loaded; the JSON range query is the
authoritative result for this part:

```
missing lines in 620-829: [827]
missing branches in 620-829: [[826, 827]]
executed lines in 620-829: 167
```

All 167 executable lines in 620–826 and every branch starting there are covered by this file
alone. Line 827 (`once()` under `if ONCE:`) and branch `826 -> 827` are the `COVPY-MONITOR-CLI`
entry-path, not the curses loop; they are the only gap in the nominal 600–829 range and belong to
that sibling task (an earlier attempt to cover them by re-importing with ONCE=1 executed the real
`once()` before a stub could be injected, so it was removed rather than fake a test). Lines
1–619 belong to the FETCH and COMPOSE tasks.

New branch → test mapping (every branch in this part):

| Branch | Test |
|---|---|
| `rich` true (COLORS>=256) / false, inner `COLORS>=16` true / false; 40-pair bar colour | `test_palette_initialisation_handles_rich_and_plain_terminals` |
| `ansi_code`: `""`, `dim`, `bar`, `fail`, `b_*`, plain and unknown-name fallback | `test_ansi_codes_cover_named_bold_unknown_and_special_styles` |
| `once`: `data[k] is None` / not; left/right present/absent; W clamp 100/140/160 | `test_once_replaces_missing_sections_and_pads_both_columns`, `test_once_clamps_terminal_width_to_the_supported_range` |
| `swarm_idle`: missing runs/todo, live swarm, idle swarm, open/no-open task | `test_swarm_idle_distinguishes_live_waves_and_open_tasks` |
| `start_swarm`: success, stderr with lines, empty stderr fallback | `test_start_swarm_returns_the_last_error_line_or_a_fallback` |
| `beep`: first player, second player, curses fallback | `test_beep_uses_a_local_player_when_present_and_falls_back_to_curses` |
| `part`: `v is None` / not, empty payload | `test_part_ignores_missing_sections_and_empty_payloads`, `test_loading_screen_waits_for_three_quarters_of_the_sections` |
| `kick`: busy (early return) / idle (new thread) | `test_refresh_keys_kick_only_when_idle` |
| keys: `-1`/`KEY_RESIZE` skipped, `q`/`Q`/27 return, `r`/`R` kick, other key | `test_resize_no_key_and_unrelated_keys_do_not_quit`, `test_quit_keys_return_without_consuming_later_steps`, `test_refresh_keys_kick_only_when_idle` |
| alarm silenced by any key | `test_a_new_failure_raises_an_alarm_and_any_key_silences_it` |
| timer: `at` zero / not elapsed / elapsed >= INTERVAL | `test_the_timer_kicks_only_after_the_configured_interval` |
| `ready` 75% threshold; identical `sig` → `continue` | `test_loading_screen_waits_for_three_quarters_of_the_sections`, `test_footer_reports_loading_refreshing_and_updated` |
| `at and at != checked_at and "runs" in loaded` (each short-circuit) | `test_a_scan_with_no_fresh_failures_keeps_the_screen_quiet`, `test_loading_screen_waits_for_three_quarters_of_the_sections` |
| `seen is None` / set; `if fresh` true / false; one / many fresh; existing alarm ids | `test_a_new_failure_raises_an_alarm_and_any_key_silences_it`, `test_a_scan_with_no_fresh_failures_keeps_the_screen_quiet`, `test_multiple_fresh_failures_are_grouped_and_beeped_every_five_seconds`, `test_a_later_failure_joins_the_already_active_alarm` |
| autostart: flag off, live wave, short wait, elapsed, empty/non-empty error | `test_a_wave_starts_when_swarm_is_idle_and_the_wait_has_elapsed`, `test_a_failed_launch_is_reported_in_the_footer`, `test_a_live_wave_or_a_short_wait_prevents_autostart` |
| beep due: no alarm / before next / at next | alarm tests above |
| `phase` with/without alarm; `put` fail+flicker both ways, unknown style, `curses.error` | `test_fail_rows_flicker_only_on_the_alternate_phase`, `test_addstr_errors_from_the_terminal_are_ignored` |
| draw loops: top, `y >= H-1` break, `i < len(left)` / `i < len(right)`, both sides | `test_draw_stops_at_the_last_row_and_handles_short_columns` |
| alarm banner vs footer; footer loading/refreshing/updated; AUTOSTART suffix | `test_a_new_failure_raises_an_alarm_and_any_key_silences_it`, `test_footer_reports_loading_refreshing_and_updated`, `test_a_live_wave_or_a_short_wait_prevents_autostart` |

## Clinical and architecture review

Not required (test-only tooling change). No signed data, cross-organization sharing, patient
association, prescribing or critical result is involved.

## Findings

- The full script suite is red on this checkout for four pre-existing reasons, none caused by this
  change: three `test_check_pack` cases fail because `git ls-files -v opencode.json` reports flag
  `S` (the CI profile is overlaid under `skip-worktree` in this worker), which is BUGS
  `OPS-CW-003-01` and MEMORY's "worktree config is not HEAD truth" rule; and
  `test_swarm.TwoTierTests.test_integrate_batch_retries_when_main_advanced` fails deterministically
  even when run alone (`python3 -I -m unittest discover -s scripts/tests -p "test_swarm.py" -k
  test_integrate_batch_retries_when_main_advanced` → `Ran 1 test / FAILED (failures=1)`, twice).
  No `scripts/*.py` was edited. `python-coverage.sh` therefore cannot give a green verdict here.
- Only `scripts/tests/test_monitor_tui_coverage.py` existed among the four monitor test files at
  measurement time, so combined whole-module coverage cannot be measured yet; the report above is
  the file-alone measurement the story asks for.
- Line 827 is deliberately left to `COVPY-MONITOR-CLI`; see Coverage for why it is not forced here.

## Lessons for workers

- Importing `monitor.py` executes `argv[1..3]` parsing and the bottom `if ONCE` at module scope.
  Always load it with a fake `curses` in `sys.modules` and `sys.argv = [..., "30", "50", "0", "0"]`
  restored afterwards; otherwise a real curses terminal or a live `gh` fetch is started.
- Do not try to stub `once` by assigning to the module before `exec_module`: the module body
  redefines it and the real entry point runs. Cover that line from the CLI task instead.
- `refresh()` marks every section loaded after `fetch` returns, so a synchronous thread makes the
  loading screen unreachable. Use a thread object whose `start()` does nothing and run its
  captured `target` by hand (raising a sentinel from the fetch stub to leave a refresh unfinished)
  to drive partial-loading and alarm timing deterministically.
