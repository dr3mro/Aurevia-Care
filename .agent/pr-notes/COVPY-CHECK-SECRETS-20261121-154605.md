<!-- PROJECT_V1_SPEC.md section 11: every implementation pull request records the items below. Use synthetic data only. -->

## Summary

Reached 100% line and branch coverage of `scripts/check-secrets.py` by adding one new test file that drives the previously untested CLI entry points: `main()` (lines 64-67) and the `if __name__ == "__main__"` guard / `sys.exit(main())` (lines 70-71). No production code was changed; no existing test was changed. All findings are synthetic (`Path("fixture/...")` names and plain labels), `subprocess.run` is mocked and no network or real credential is used.

Scope respected: the only changed path is `scripts/tests/test_check_secrets_coverage.py`.

## Affected requirements

OPS-06

## Risk class

Routine (test-only coverage addition; no production behaviour change). docs/WORKFLOW.md.

## Review owner

orevia-senior-python (Python tooling); integrator verifies the full gate.

## Data changes

None. No migrations, backfills, DDL, lock or volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role or patient-link scope; no audit events added or changed.

## Migration and rollout considerations

None.

## Test evidence

Baseline (before change):
- `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q` → `Ran 452 tests ... FAILED (failures=3)` (the same 3 unrelated `test_check_pack` failures noted below).
- `python3 -m coverage report -m --include=scripts/check-secrets.py` →
  `scripts/check-secrets.py 36 5 14 1 84% 64-67, 71`

Focused new tests:
- `python3 -I -m unittest discover -s scripts/tests -p 'test_check_secrets_coverage.py' -v` → `Ran 4 tests in 0.002s` / `OK`.

Final full suite (story step 4):
- `python3 -I -m unittest discover -s scripts/tests` → `Ran 456 tests in 17.768s` / `FAILED (failures=3)`. Exit 1 is caused solely by the 3 pre-existing, unrelated failures:
  - `FAIL: test_check_mode_detects_drift (test_check_pack.GeneratorTests.test_check_mode_detects_drift)`
  - `FAIL: test_committed_opencode_json_is_generated (test_check_pack.PackTests.test_committed_opencode_json_is_generated)`
  - `FAIL: test_repository_pack_is_valid (test_check_pack.PackTests.test_repository_pack_is_valid)`
  All three are `opencode.json is stale: run scripts/gen-opencode.py` — a parallel worker (COVPY-GEN-OPENCODE) is mid-edit on protected files. They reproduce on the unmodified baseline (452 tests) and are outside this task's scope. The 4 new coverage tests pass; `test_repository_is_clean` also passes, confirming the new test file contains no secret-shaped strings.

Changed-line coverage gate (ran directly because python-coverage.sh aborts at its unittest step on the same 3 unrelated failures):
- `python3 -m coverage json -q --omit='scripts/tests/*' -o /tmp/opencode/allcov.json`
- `python3 -I scripts/python_coverage.py /tmp/opencode/allcov.json --base origin/main` → `python coverage: every changed script line is covered` (exit 0).

Other gates:
- `bash scripts/python-coverage.sh` → exit 1, but only at its first command (`coverage run ... unittest`), which re-hits the 3 unrelated `test_check_pack` failures; it never reached the changed-line check. The changed-line check itself passes (command above).
- `bash scripts/check.sh` → exit 1 with the single line `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (the same parallel-worker staleness; outside scope, not regenerated here).

Entry point → test trace: `scripts/check-secrets.py:main()` is invoked directly by the three `MainCoverageTests.run_main` tests (with `secrets.scan` patched) and through the `python3 scripts/check-secrets.py` command shape simulated by `runpy.run_path(..., run_name="__main__")` in `test_module_main_guard_exits_through_sys_exit`.

## Coverage

Command:
`python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
`python3 -m coverage report -m --include=scripts/check-secrets.py`

Final report line (required, 100%):
`scripts/check-secrets.py 36 0 14 0 100%`

Coverage JSON detail: `missing_lines []`, `missing_branches []`, `percent_covered 100.0`, `num_branches 14`, `num_partial_branches 0`.

New/uncovered lines and branches and the test that takes each (baseline missing: lines 64-67, 71; partial branch at line 70; missing arcs `[65,66]`, `[65,67]`, `[70,71]`):
- Lines 64-67 executed (findings, `print` to stderr, `return 1 if findings else 0`) — `test_main_with_many_findings_prints_each_and_returns_one`, `test_main_with_single_finding_prints_one_line`, `test_main_with_no_findings_reports_nothing_and_returns_zero`.
- Arc `[65,66]` — loop body entered with non-empty findings — `test_main_with_many_findings_prints_each_and_returns_one`, `test_main_with_single_finding_prints_one_line`.
- Arc `[65,67]` — loop exhausted / skipped with empty findings — `test_main_with_no_findings_reports_nothing_and_returns_zero`.
- Line 67 ternary outcome `1` — `test_main_with_many_findings_prints_each_and_returns_one` and `test_main_with_single_finding_prints_one_line` (assert rc == 1).
- Line 67 ternary outcome `0` — `test_main_with_no_findings_reports_nothing_and_returns_zero` (assert rc == 0).
- Arc `[70,71]` — `if __name__ == "__main__"` true branch reached — `test_module_main_guard_exits_through_sys_exit` (executes via `runpy.run_path(..., run_name="__main__")`).
- Line 71 `sys.exit(main())` executed, catching `SystemExit(0)` — `test_module_main_guard_exits_through_sys_exit`.
- Loop iteration counts: zero items — `test_main_with_no_findings_reports_nothing_and_returns_zero`; one item — `test_main_with_single_finding_prints_one_line`; many items — `test_main_with_many_findings_prints_each_and_returns_one`.

## Clinical and architecture review

None required — no signed data, cross-organization sharing, patient association, prescribing or critical results. No clinical reviewer evidence; this is an engineering-only tooling test change (tool evidence only).

## Findings

- The repository-wide suite is currently red with 3 `test_check_pack.py` failures (`opencode.json is stale: run scripts/gen-opencode.py`). They are present on the unchanged baseline (452 tests) and come from a parallel worker (COVPY-GEN-OPENCODE) editing `opencode.json` / `gen-opencode.py`. They are outside this task's single-file scope and were not modified or regenerated here; the integrator sees them resolve when that worker lands. Evidence source: executed commands and file reads (tool).
- Consequently `bash scripts/python-coverage.sh` and `bash scripts/check.sh` exit 1 on this worktree for the same external reason. The changed-line coverage check (`python_coverage.py`) itself passes with exit 0.
- `coverage` and `pip` were absent in this worker; both were bootstrapped locally (get-pip.py then `pip install --break-system-packages coverage`) purely as tooling, with no repository dependency or file change. No dependency was added to the project.

## Lessons for workers

- For a script whose CLI entry point is uncovered, `runpy.run_path(str(path), run_name="__main__")` inside `unittest` covers the `if __name__ == "__main__"` true arc and `sys.exit(main())` without spawning a subprocess under coverage — catch `SystemExit` and assert `.code`. Patch `subprocess.run` to return an empty `.stdout` to keep `scan()` deterministic and offline.
- A one-line ternary (`return 1 if findings else 0`) is not a coverage branch arc in coverage.py; still assert both outcomes as behaviour, but the real arcs to chase on this script are the `for` loop enter/exit (`[65,66]`/`[65,67]`) and the `__main__` guard (`[70,71]`).
- `python-coverage.sh` aborts at its `unittest` step on any unrelated suite failure before reaching the changed-line check; run `python3 -I scripts/python_coverage.py <json> --base origin/main` directly to isolate the changed-line verdict when parallel workers make the suite red.
