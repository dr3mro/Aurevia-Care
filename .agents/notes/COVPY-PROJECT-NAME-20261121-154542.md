# COVPY-PROJECT-NAME — 100% coverage of `scripts/project-name.py`

## Summary

Added one new test file, `scripts/tests/test_project_name_coverage.py`, that exercises the real
`scripts/project-name.py` CLI in-process with synthetic `sys.argv`, a synthetic `project.json`
and captured stdout/stderr, and drives it to 100% line and branch coverage. No production code,
existing test, threshold, exclusion or `# pragma: no cover` was changed. No network or real
project data is used.

Requirement: **OPS-06**. Task: **COVPY-PROJECT-NAME** (swarm-9). Scope used:
`scripts/tests/test_project_name_coverage.py` only.

## Affected requirements

OPS-06 (synthetic test fixtures, CI policy checks and migration/tooling verification). No
clinical requirement is touched.

## Risk class

Routine (`docs/WORKFLOW.md`) — test-only change to an identity-tooling script; no runtime,
contract, data or deployment behavior changes.

## Review owner

Senior Python Tooling (`aurevia-senior-python`) and Test Engineer (`aurevia-test-engineer`)
skills loaded and applied; one bounded read-only specialist review requested. All checks below
were executed by the primary worker (tool evidence). No simulated user or real human evidence.

## Data changes

None. No migration, backfill, lock/volume impact or rollback.

## Authorization boundaries and audit events

None. Tests use synthetic values only (`"Synthetic Clinic"`, `"synthetic-clinic"`) and patch
`Path.read_text`; no tenant, role, patient-link scope or audit event is involved. No credential,
token or real domain name appears in the test file.

## Migration and rollout considerations

None. Test-only change.

## Test evidence

Entry point to test trace: `scripts/project-name.py` (argparse CLI entry) → `runpy.run_path` in
`run_script()` → asserts on exit code, stdout and stderr.

Commands actually run (final lines quoted):

1. Baseline measurement (with `coverage` 7.17.4 installed via
   `PYTHONPATH=/__t/Python/3.11.18/x64/lib/python3.11/site-packages python3 -m pip install --break-system-packages coverage`,
   because the system `python3` 3.11.2 had neither pip nor coverage; the toolchain Pythons could
   not run because they require GLIBC 2.38 and the host has 2.36):
   - `python3 -m coverage erase && python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q`
   - `python3 -m coverage report -m --include=scripts/project-name.py`
   - Result: `scripts/project-name.py 16 16 4 0 0% Missing 2-18` (0% before the change).

2. New tests in isolation:
   - `python3 -I -m unittest discover -s scripts/tests -p 'test_project_name_coverage.py'`
   - Result: `Ran 14 tests in 0.015s` / `OK` (exit 0). (The final run after adding the two
     hardening tests from the specialist review; the initial 12-test run was also `OK`.)

3. Full tooling suite (step 4 of the task):
   - `python3 -I -m unittest discover -s scripts/tests`
   - Result: `FAILED (failures=3)` — all three failures are in `test_check_pack.py`
     (`test_check_mode_detects_drift`, `test_committed_opencode_json_is_generated`,
     `test_repository_pack_is_valid`). Confirmed pre-existing and unrelated: running
     `python3 -I -m unittest discover -s scripts/tests -p 'test_check_pack.py'` alone reproduces
     the same three failures, and the new test file is not involved. Root cause is the documented
     cloud-worker artifact: `git ls-files -v opencode.json` reports `S opencode.json`
     (skip-worktree) and the worktree copy contains extra CI deny rules absent from `HEAD`, so
     `scripts/check-pack.py` reports `opencode.json is stale`. See MEMORY.md "Dispatch-3 finding".

4. `bash scripts/python-coverage.sh` → exit 1. It stops at the same pre-existing unittest
   failures (the three `test_check_pack` cases) before reaching the coverage gate; grep confirms
   zero occurrences of `python_coverage` in its output.

5. `bash scripts/check.sh` → exit 1 with `check-pack: opencode.json is stale: run scripts/gen-opencode.py`
   (line 16), the same pre-existing skip-worktree artifact. Not caused by this change and out of
   scope to fix (the task forbids editing `opencode.json`, `.opencode/` or `scripts/`).

6. Verification that the artifact is pre-existing:
   - `git ls-files -v opencode.json` → `S opencode.json`
   - `diff <(git show HEAD:opencode.json) opencode.json` → worktree has extra shell/git deny rules.

Evidence source for all commands above: executed tool commands (no reviewer, simulated user or
human participant evidence).

## Coverage

Command (as specified by the task):
`python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests` followed
by `python3 -m coverage report -m --include=scripts/project-name.py`.

Final report line for the touched area:

```
scripts/project-name.py      16      0      4      0   100%
```

16 statements / 0 missed, 4 branches / 0 partial — 100% line and branch coverage.

The same 100% is produced by the new test file alone (unambiguous attribution):
`python3 -m coverage erase && python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -p 'test_project_name_coverage.py' -q` (exit 0) → the same report line above. The coverage JSON confirms every arc:
`executed_arcs= [[14, 15], [14, 16], [16, 17], [16, 18]]`, `missing_branches= []`, `percent_covered= 100.0`, `percent_branches_covered= 100.0`.

Branch-to-test map (coverage.py records 4 arcs: 2 `if` statements × 2 outcomes):

| Line / branch | Outcome | Test(s) |
|---|---|---|
| 14 `if not isinstance(name, str) or not name.strip() or not name.isprintable()` | False (valid name) → line 16 | `ProjectNameSuccessTests.test_display_name_is_printed`, `test_repository_slug_is_printed`, `test_valid_printable_name_with_punctuation` |
| 14 | True → line 15 (`parser.error`) | `ProjectNameDisplayValidationTests.test_non_string_display_name_is_rejected` (`not isinstance`), `test_blank_display_name_is_rejected` (`not name.strip()`), `test_missing_display_name_is_rejected` (None), `test_non_printable_display_name_is_rejected` (`not name.isprintable()`) |
| 16 `if not isinstance(slug, str) or not re.fullmatch(...)` | False (valid slug) → line 18 | all three `ProjectNameSuccessTests` |
| 16 | True → line 17 (`parser.error`) | `ProjectNameSlugValidationTests.test_uppercase_slug_is_rejected`, `test_underscore_slug_is_rejected`, `test_non_string_slug_is_rejected`, `test_missing_slug_is_rejected` |

Straight-line statements (lines 2-13 and 18, including the `print(project[args.field])` for both
argparse choices) are covered by the three `ProjectNameSuccessTests`; the invalid-choice CLI path
is asserted by `ProjectNameArgumentTests.test_unknown_field_is_rejected_before_reading_project`
(exit code 2, `invalid choice` in stderr, and an assertion that `project.json` is not read).

Every test asserts an outcome (exit code, exact stdout or stderr content, or a raised
`JSONDecodeError`). No test is skipped. Two optional hardening tests added after the specialist
review: `ProjectNameRobustnessTests.test_malformed_project_json_propagates_decode_error` and
`test_additional_invalid_slugs_are_rejected` (empty, leading/trailing/doubled hyphen, Unicode).
While writing the latter, an initially mislabelled fixture (`leading-hyphen`) was corrected to
`-leading-hyphen` after the test itself showed the original is valid kebab-case.

## Specialist review

One bounded read-only review by the `aurevia-senior-python` specialist (session
`ses_e0131674cffe8B0kbrndmKUOc2`) returned **PASS — no blocking findings**: all 4 arcs traced to
asserting tests, no test without an assertion, synthetic stdlib-only data, sound `runpy`/patching
seam with no credible false-positive path, and no correctness bug. It could not execute commands
in its session, so its verdict is from static inspection; its two low-severity suggestions (JSON
decode failure and regex edge cases) were adopted above and re-verified by the primary worker.

## Findings

- Pre-existing, unrelated: the worktree `opencode.json` is under skip-worktree and carries CI deny
  rules absent from `HEAD`. This makes three `test_check_pack.py` tests fail and makes
  `scripts/check.sh` and `scripts/python-coverage.sh` exit 1 before they can judge this change.
  It is documented in MEMORY.md and is not fixable inside this task's scope. The integrator should
  evaluate this change against the committed `HEAD` profile, not the CI-profile worktree copy.
- `scripts/project-name.py` is a top-level script with no importable functions, so coverage is
  obtained by executing the real file with `runpy.run_path` under patched `sys.argv` and
  `Path.read_text`. Coverage is measured on the original `scripts/project-name.py` path (confirmed
  by the report), not on a copy.
- No defect was found in the script under test.

## Lessons for workers

- Before concluding that a full-suite failure is yours, run the failing test file alone; on cloud
  workers, `test_check_pack.py` failures are usually the skip-worktree `opencode.json` CI profile,
  not a regression. Check `git ls-files -v opencode.json` for the `S` flag.
- To cover a top-level Python CLI in-process (so coverage attributes lines to the original file),
  use `runpy.run_path(..., run_name="__main__")` with `patch.object(sys, "argv", ...)`, patch the
  file-read boundary, redirect stdout/stderr and catch `SystemExit`; copying the script to a temp
  dir would make the coverage report miss the original path.
