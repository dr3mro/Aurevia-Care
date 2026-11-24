# COVPY-CHECK-WIRING — 100% line and branch coverage of scripts/check-wiring.py

## Summary

The existing suite (`scripts/tests/test_check_wiring.py`) exercises every public
function of `check-wiring.py` directly, leaving only the module's command-line entry
point uncovered: line 127 (`sys.exit(main())`) and the `True` outcome of the branch at
line 126 (`if __name__ == "__main__":`). Added one new test file,
`scripts/tests/test_check_wiring_coverage.py`, that executes the real script as
`__main__` in-process. Coverage of `scripts/check-wiring.py` moved from 98% (line 127
missing, 1 partial branch) to **100% lines and 100% branches**. No production script,
existing test, threshold or exclusion was changed; no dependency was added; only
synthetic fixtures in temporary directories were used and no network was called.

## Affected requirements

- OPS-06 (CI policy checks, synthetic fixtures). Script-only test change.

## Risk class

Routine — test-only addition inside the single declared `scope`.

## Review owner

Senior Python Tooling (`orevia-senior-python`) and Test Engineer
(`orevia-test-engineer`); both skills were loaded.

## Data changes

none — no migrations, backfills, schema, lock or volume impact; no rollback/forward-fix.

## Authorization boundaries and audit events

none — the tests drive a local CLI entry point against a temporary fixture; no tenant,
role, patient-link scope and no audit events.

## Migration and rollout considerations

none.

## Test evidence

Commands run from the repository root (tool evidence; synthetic fixtures only):

- `python3 -m pip install --break-system-packages coverage` → `Successfully installed coverage-7.17.4` (coverage was absent; no repo dependency added).
- `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests` → `Ran 454 tests ... FAILED (failures=3)` — the 3 failures are pre-existing `test_check_pack` failures (see Findings), present before this change too; both new tests pass.
- `python3 -m coverage report -m --include=scripts/check-wiring.py` →
  `scripts/check-wiring.py      77      0     36      0   100%`
- `python3 -I -m unittest discover -s scripts/tests -p 'test_check_wiring_coverage.py' -v` → `Ran 2 tests ... OK`.
- `python3 -I -m unittest discover -s scripts/tests` → `Ran 454 tests ... FAILED (failures=3)` — same 3 pre-existing `test_check_pack` failures.
- `bash scripts/python-coverage.sh` → `exit=1`, `Ran 454 tests ... FAILED (failures=3)` at the unittest step (the same pre-existing `test_check_pack` failures; `set -e` stops before `python_coverage.py`). No new production script lines were added, so the diff-based added-line check has nothing to flag.
- `bash scripts/check.sh` → `exit=1`, `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — pre-existing skip-worktree `opencode.json` artifact (see Findings), reached before any script-test step.

Entry point → test trace: `scripts/check-wiring.py:126-127` (`if __name__ == "__main__": sys.exit(main())`) → `CheckWiringEntryPointTests.test_entry_point_exits_zero_on_a_clean_tree` (exit 0 on a clean fixture) and `test_entry_point_exits_one_on_a_new_finding` (exit 1 with an orphan `OrphanService.java`). The fake-`__file__` namespace redirect makes `ROOT` resolve to the temporary fixture, so no real repo file is read or written.

The 3 pre-existing failures are all in `scripts/tests/test_check_pack.py`:
`GeneratorTests.test_check_mode_detects_drift`,
`PackTests.test_committed_opencode_json_is_generated`,
`PackTests.test_repository_pack_is_valid`. They were failing before the new test file
existed (baseline coverage run: `Ran 452 tests ... FAILED (failures=3)`).

## Coverage

Command and result:
`python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
then `python3 -m coverage report -m --include=scripts/check-wiring.py`
→ `scripts/check-wiring.py 77 stmts, 0 miss, 36 branches, 0 partial, 100%` (was 98%:
line 127 missed, branch `126 -> 127` missing).

Previously-uncovered branches and the test that now takes them:

- `if __name__ == "__main__":` **True** outcome (branch `126 -> 127`) — both new tests execute the module code as `__main__` via `compile(...)/exec(...)` with `__name__` set to `"__main__"`.
- line 127 `sys.exit(main())` — executed by both new tests; `SystemExit.code` is asserted as `0` (clean fixture) and `1` (new finding), so the call is asserted, not merely touched.
- `if __name__ == "__main__":` **False** outcome (module import) — already covered by the existing suite loading `check-wiring` through `helpers.load`.

No new production branches were introduced (test-only change). No thresholds,
exclusions or skips were added or changed.

## Clinical and architecture review

none — no signed data, cross-organization sharing, patient association, prescribing
or critical results; no clinical or human evidence involved (synthetic data only).

## Findings

- TODO entry `COVPY-CHECK-WIRING` step 4 contains a copy/paste typo: it says to paste the final report line for `scripts/check-secrets.py`, but the task title, requirement and `scope` all say `check-wiring.py`. Used `check-wiring.py` throughout (tool evidence: report line above).
- The local worktree `opencode.json` is the CI profile under `skip-worktree` (`git ls-files -v opencode.json` → `S opencode.json`). This makes the 3 `test_check_pack` tests fail and `scripts/check.sh` fail at `check-pack`. This is the documented OPS-CW-003-01 / IMP-01 worker-environment artifact (MEMORY.md, BUGS.md, 2026-11-19 daily notes), pre-existing and unrelated to this change. `opencode.json` is a protected file and was not touched; per skill guidance these worktree checks are CI-profile results, not HEAD truth.
- `coverage.py` and `pip` were absent; installed `pip` via `get-pip.py` and then `coverage==7.17.4` into the environment to measure coverage. Nothing was added to the repository.

## Lessons for workers

- To cover a Python module's `if __name__ == "__main__":` guard in-process without importing under a different name, compile the real source with its real path (`compile(source, str(SCRIPT), "exec")`) and `exec` it in a namespace with `__name__ = "__main__"`; supply a fake `__file__` pointing into a temp fixture so `ROOT = Path(__file__).resolve().parents[1]` is redirected deterministically, and catch/assert `SystemExit`. Coverage stays attributed to the real script path.
- A local worktree `opencode.json` under `skip-worktree` (flag `S`) is the CI profile; `test_check_pack` and `check.sh`'s `check-pack` failures from it are environment artifacts, not change defects. Verify with `git ls-files -v opencode.json` and leave the protected file alone.
