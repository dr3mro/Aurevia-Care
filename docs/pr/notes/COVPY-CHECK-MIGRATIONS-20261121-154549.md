# COVPY-CHECK-MIGRATIONS — script test coverage for `check-migrations.py`

## Summary

Added one new test file, `scripts/tests/test_check_migrations_coverage.py`, with seven
synthetic, deterministic tests that close the remaining line and branch gaps in the
OPS-06 migration change-safety gate. No production code, existing test, threshold,
exclusion, dependency or file outside the declared scope was changed. Baseline
coverage of `scripts/check-migrations.py` was 93% (11 missed statements, 8 partial
branches); it is now 100% statements and branches (184 statements, 96 branches).

## Affected requirements

- OPS-06 — migration change-safety / reversible expand-contract discipline.
  Acceptance: 100% line and branch coverage of `scripts/check-migrations.py`
  through new tests of its public functions and command-line entry point only.

## Risk class

Routine (test-only change; no production, contract, migration or deployment path
modified). Checks per docs/WORKFLOW.md: targeted Python tooling tests and coverage
plus the full tooling suite.

## Review owner

Senior Python Tooling (simulated/static subagent review, session
`ses_e0134a0efffeSf5SxSQjdXoX4P`) and Test Engineer skill. No human or clinical
review was available or required.

## Data changes

None. No migrations, backfills, locks, volume changes, rollback or forward-fix
introduced. Synthetic string literals only; no patient data or credentials.

## Authorization boundaries and audit events

None. No tenant, role, patient-link scope or audit event changed.

## Migration and rollout considerations

None. The script under test was not edited; the checked-in migration set is read
only by the single `__main__` entry-point test (see Findings).

## Test evidence

Environment: Python 3.11.2; `coverage.py` 7.17.4. `pip` and `ensurepip` were absent,
so pip was bootstrapped from `https://bootstrap.pypa.io/get-pip.py` and coverage
installed with `python3 -m pip install --break-system-packages coverage` (no
repository dependency added). All commands run from the repository root.

1. Baseline measurement (before the new test file):
   - `python3 -m coverage erase && python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests && python3 -m coverage report -m --include=scripts/check-migrations.py`
   - Result: `scripts/check-migrations.py 184 11 96 8 93% Missing: 94->69, 97-99, 112->69, 115-117, 126-128, 134, 138->69, 264`.
2. New tests only:
   - `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations_coverage.py' -v`
   - Final lines: `Ran 7 tests in 0.037s` / `OK`.
3. Full tooling suite with the new file:
   - `python3 -I -m unittest discover -s scripts/tests` → `Ran 459 tests in 12.783s` / `FAILED (failures=3)`.
   - The same three failures occur on the baseline without the new file
     (`Ran 452 tests ... FAILED (failures=3)`), so the change adds seven passing
     tests and no new failures. Failing tests: `test_check_pack.GeneratorTests.test_check_mode_detects_drift`,
     `test_check_pack.PackTests.test_committed_opencode_json_is_generated`,
     `test_check_pack.PackTests.test_repository_pack_is_valid` — the documented
     worker artifact where the worktree `opencode.json` is the CI profile under
     skip-worktree (`git ls-files -v opencode.json` → `S opencode.json`).
4. Script-specific gate: `bash scripts/python-coverage.sh` → exit 1, but only
   because `set -e` stops at the same three pre-existing `test_check_pack`
   failures; its coverage diff stage was never reached. The new file is under
   `scripts/tests/*`, which that gate omits, and it adds no `scripts/*.py` lines.
5. `bash scripts/check.sh` → exit 1 at its first policy step:
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Same
   skip-worktree CI-profile artifact; not regenerated (maintainer-owned, and
   the committed HEAD profile is correct). Evidence source for all items above:
   executed tool commands.

Entry-point-to-test trace: public `strip_noise` / `destructive_statements` are
exercised directly by the six scanner tests; the CLI path
`if __name__ == "__main__": sys.exit(main())` is exercised by `runpy.run_path`
in `CommandLineEntryTest.test_module_main_exits_with_check_result`.

## Coverage

Command and final report line:

```
python3 -m coverage erase
python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests
python3 -m coverage report -m --include=scripts/check-migrations.py
scripts/check-migrations.py     184      0     96      0   100%
```

100% line coverage (184/184 statements) and 100% branch coverage (96/96 branches),
0 missed, 0 partial. No threshold, exclusion, `# pragma: no cover`, omit entry or
skipped test was added.

Each previously uncovered line/branch and the test that takes it:

| Gap (baseline) | Behavior | Test |
|---|---|---|
| `97-99` | doubled `''` escape inside a single-quoted literal is blanked | `NoiseScannerEscapeTest.test_doubled_single_quote_inside_literal_is_blanked` |
| `115-117` | doubled `""` escape inside a quoted identifier is blanked | `NoiseScannerEscapeTest.test_doubled_double_quote_inside_identifier_is_blanked` |
| `126-128` | `DOLLAR_TAG.match` returns `None` for a lone `$`; character kept | `NoiseScannerEscapeTest.test_lone_dollar_sign_is_not_a_quote_delimiter` |
| `94->69` | unterminated single-quoted literal blanked to EOF (inner loop exit) | `NoiseScannerEofTest.test_unterminated_single_quoted_string_is_blanked_to_eof` |
| `112->69` | unterminated quoted identifier blanked to EOF (inner loop exit) | `NoiseScannerEofTest.test_unterminated_quoted_identifier_is_blanked_to_eof` |
| `134` | dollar-body closing marker not found → `end = size` | `NoiseScannerEofTest.test_unterminated_dollar_body_is_blanked_to_eof` |
| `138->69` | `if end < size:` false for unterminated dollar body | `NoiseScannerEofTest.test_unterminated_dollar_body_is_blanked_to_eof` |
| `264` | `sys.exit(main())` executed as `__main__` | `CommandLineEntryTest.test_module_main_exits_with_check_result` |

Every test asserts an outcome (exact blanked output, preserved newlines, absence
of a destructive finding, or exit code), not merely that a line executed.

## Findings

- **Pre-existing, not caused by this change:** three `test_check_pack` failures
  (`opencode.json` stale under skip-worktree) make the full tooling suite and both
  `python-coverage.sh` / `check.sh` exit 1 in this worker. Verified identical on the
  baseline with the new file removed. The integrator/Delivery, which use the
  committed local profile, own those gates. No regeneration or threshold change
  was attempted.
- **Accepted LOW-risk coupling (from the simulated reviewer):** the `__main__`
  test asserts exit code 0 while reading the real checked-in migrations, so an
  unrelated in-progress bad migration in a parallel worker could flip it. This
  mirrors the existing `test_check_migrations.py::SequenceTest::test_repository_migrations_are_valid`
  assumption, and the scope forbids copying/symlinking the script or editing it;
  accepted with no change. No other findings.
- Reviewer verdict: no determinism defects, no meaningless assertions, no
  additional repository-state dependencies; safe to pass as written. This was a
  simulated/static review, not human or clinical evidence.

## Lessons for workers

- `pip` and `ensurepip` may be absent in the worker image; bootstrap pip from
  `bootstrap.pypa.io/get-pip.py` before installing `coverage`, and record the
  coverage version in the notes.
- A three-failure `test_check_pack` red suite in a worker is an environment
  artifact (CI `opencode.json` under `S` in `git ls-files -v`), not a change
  defect. Prove it by removing your new test file and rerunning the suite, then
  leave `opencode.json` untouched.
- For the final uncovered `sys.exit(main())` line, `runpy.run_path(path,
  run_name="__main__")` with `patch.object(sys, "argv", [path])` is a
  deterministic in-process way to reach it; a subprocess would not be traced by
  `coverage run --source=scripts`.
- The scanner's hidden branches are all escape/EOF paths: doubled quote escapes,
  unterminated single/double/dollar regions, and a `$` that is not a valid tag.
  Assert the exact blanked output so a regression cannot pass by touching a line.
