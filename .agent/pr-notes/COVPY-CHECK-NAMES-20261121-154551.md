# COVPY-CHECK-NAMES — Script tests: 100% coverage of `scripts/check-names.py`

## Summary

Added one new test module, `scripts/tests/test_check_names_coverage.py`, that covers every previously
uncovered line and branch of `scripts/check-names.py`: the `tracked_files` git-failure fallback, the
unreadable-Java-source skip, the `main()` reporting loop/exit status, and the `if __name__ ==
"__main__"` command-line entry point (with and without a root argument). No production script,
existing test, threshold or exclusion was changed; no `# pragma: no cover` was added. All fixtures
are synthetic temporary directories and git/subprocess calls are mocked — the network is never used.

## Affected requirements

- OPS-06 — synthetic test fixtures and CI policy checks (coverage of the naming policy checker).

## Risk class

Routine (test-only change to a CI policy script; no production behavior changed).

## Review owner

aurevia-senior-python / aurevia-test-engineer (simulated review; no human reviewer available).

## Data changes

None. No migrations, backfills or schema changes.

## Authorization boundaries and audit events

None. This is a tooling test; it handles no tenant, role or patient data and adds no audit events.

## Migration and rollout considerations

None. The change is a single new file under `scripts/tests/`.

## Test evidence

Entry point to test trace: `scripts/check-names.py` public functions `tracked_files`, `violations`
and `main` are imported directly; the command-line entry point at line 78 is executed through
`runpy.run_path(..., run_name="__main__")` with `sys.argv` patched and `SystemExit` asserted.

Commands and their final lines (evidence source: executed tool commands):

- Baseline measurement (`coverage` 7.17.4 installed because it was missing):
  - `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  - `python3 -m coverage report -m --include=scripts/check-names.py`
  - Baseline: `scripts/check-names.py 48 13 22 1 74% Missing: 37-42, 58-59, 70-74, 78`
- New tests only: `python3 -I -m unittest discover -s scripts/tests -p 'test_check_names_coverage.py'`
  - `Ran 8 tests in 0.037s` / `OK`
- All check-names tests (existing + new): `python3 -I -m unittest discover -s scripts/tests -p 'test_check_names*.py'`
  - `Ran 14 tests in 0.071s` / `OK`
- Full tooling suite: `python3 -I -m unittest discover -s scripts/tests`
  - `Ran 460 tests in 16.351s` / `FAILED (failures=3)` — all 3 are pre-existing `test_check_pack`
    failures about the stale CI-profile `opencode.json` (see Findings); none are in a file touched
    by this task, and all 14 check-names tests pass.
- `bash scripts/python-coverage.sh` — runs the same full suite and therefore reports the same 3
  unrelated `test_check_pack` failures in this worker environment (see Findings).
- `bash scripts/check.sh` — exit 1 with the single line
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (same environmental artifact;
  see Findings and "Why this is not finished").

## Coverage

Command: `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
followed by `python3 -m coverage report -m --include=scripts/check-names.py`.

Final report lines (100% required, achieved):

```
Name                     Stmts   Miss Branch BrPart  Cover   Missing
--------------------------------------------------------------------
scripts/check-names.py      48      0     22      0   100%
--------------------------------------------------------------------
TOTAL                       48      0     22      0   100%
```

`python3 -m coverage json --include=scripts/check-names.py` confirms `missing_lines: []` and
`missing_branches: []`.

New branch → test mapping (all branches were uncovered at baseline):

| Line/branch | Behavior | Test(s) in `test_check_names_coverage.py` |
|---|---|---|
| 37–42; arc 39→40 and 39→42 | `tracked_files` fallback when `subprocess.run` raises `OSError` or `CalledProcessError`; `os.walk` loop body and exit, skipping `SKIP` dirs (`node_modules`) but keeping normal dirs (`apps`) | `test_git_oserror_falls_back_to_walking_the_tree`, `test_git_called_process_error_also_uses_fallback`, `test_violations_uses_fallback_when_git_is_unavailable` |
| 58–59; try→except arc | `violations` skips a `.java` file whose `read_text` raises `OSError` | `test_unreadable_java_file_is_skipped` |
| 70–74; arc 71→72 and 71→74; `return 1 if bad else 0` both sides | `main()` iterates zero violations (clean) and many violations (two folders), printing to stderr and returning 0 or 1 | `test_main_returns_zero_for_clean_tree`, `test_main_reports_violations_and_returns_one` |
| 78; arc 77→78; `sys.argv[1] if len(sys.argv) > 1 else "."` both sides | `__main__` guard runs with an explicit root arg (exit 1 on a violation) and with no arg (defaults to `.`, clean repo, exit 0) | `test_cli_with_argument_exits_with_violation_status`, `test_cli_without_argument_defaults_to_current_directory` |

## Clinical and architecture review

Not applicable (test-only tooling change; no signed data, cross-organization sharing, patient
association, prescribing or critical results).

## Findings

- Pre-existing environmental failure, not caused by this change: in this cloud worker the
  worktree `opencode.json` is the CI profile copied under `skip-worktree` (confirmed by
  `git ls-files -v opencode.json` → `S opencode.json`). It differs from `git show HEAD:opencode.json`
  (local profile: extra `"shell"` / `"resource": "gh *"` deny rules). `scripts/check-pack.py`
  therefore reports `opencode.json is stale`, and `test_check_pack` has 3 failures
  (`test_check_mode_detects_drift`, `test_committed_opencode_json_is_generated`,
  `test_repository_pack_is_valid`). MEMORY.md (`OPS-CW-003-01`, dispatch-3 finding) records this as
  a known false positive; HEAD itself is green. No `opencode.json` change was made here.
- `coverage` and `pip` were absent from the system Python; `coverage` 7.17.4 was installed with
  `python3 -m pip install --break-system-packages coverage` via a `PYTHONPATH` pointing at a
  managed Python's pip (system Python 3.11.2 has no pip/ensurepip). No dependency was added to the
  repository.
- The generated `.coverage` artifact was removed before staging.

## Lessons for workers

- When the worktree `opencode.json` carries the skip-worktree marker `S`, `check-pack.py`,
  `gen-opencode.py --check`, `scripts/check.sh` and the 3 `test_check_pack` tests judge the CI
  profile, not HEAD. Confirm with `git ls-files -v opencode.json` and compare
  `git show HEAD:opencode.json`; do not "fix" it by editing the protected `opencode.json`.
- Bash pipelines hide exit codes: `cmd | tail; echo $?` reports `tail`'s status. Capture the real
  status (e.g. `cmd > log 2>&1; rc=$?`) before recording gate results.

## Why this is not finished

The scoped task itself is complete: `scripts/check-names.py` is at 100% line and branch coverage
with all 14 check-names tests passing, and only the single in-scope file was added.

The repository-wide `bash scripts/check.sh` nonetheless exits 1 in this worker:

- Command/error: `bash scripts/check.sh` → `check-pack: opencode.json is stale: run scripts/gen-opencode.py`
- Most likely cause: the CI-profile `opencode.json` sit under `skip-worktree` in the worker, so
  `scripts/check-pack.py` compares the worktree CI profile against the locally generated local
  profile and reports a false drift (documented in MEMORY.md as OPS-CW-003-01). The same artifact
  causes the 3 `test_check_pack` failures that also fail `python-coverage.sh` and the full unittest
  run.
- What I tried: confirmed `git ls-files -v opencode.json` shows `S`; confirmed the worktree file
  differs from `git show HEAD:opencode.json`; confirmed all 14 check-names tests pass in isolation
  and that the failing tests are in `test_check_pack` (a file I did not touch). I did not edit
  `opencode.json` or any protected file, per AGENTS.md.
- Exact next step: the integrator/Delivery should evaluate the checkout with the committed local
  profile (`git checkout-index -f -- opencode.json` or a fresh checkout without the skip-worktree
  CI overlay) and re-run `bash scripts/check.sh`; the naming-coverage scope needs no further code
  change.
