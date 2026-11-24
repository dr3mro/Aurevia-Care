# FIX-WORKER-REPORTS — fresh integration test reports per swarm gate invocation

## Summary

Each swarm gate invocation now reads integration reports from a private directory created
for that run, so failing Surefire XML left by an earlier run can no longer change a later
verdict. `scripts/worker-check.sh` clears the shared `TEST-*.xml` files before Maven, copies
only this run's XML into a `mktemp -d` directory and passes that path to
`scripts/worker_gate.py` as a second argument. The gate reads only that directory, treats an
absent/empty/unparseable fresh directory on a nonzero build as a compile/setup failure, and
keeps the existing repair-task classification for valid fresh failures.

## Affected requirements

OPS-06 (CI policy checks, synthetic fixtures and reproducible delivery tooling).

## Risk class

Cross-boundary — protected delivery tooling (`scripts/`) shared by all swarm workers and the
integrator. No service, patient data or clinical workflow is involved. The task explicitly
requires maintainer review.

## Review owner

Maintainer / orchestrator (protected `scripts/` path). Senior Python Tooling and Senior Bash
review guidance was loaded; implementation evidence in this file is from executed tool
commands and synthetic fixtures, not a human reviewer.

## Data changes

None. No migration, backfill, lock or volume impact; no reserved migration version used.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit-event change.

## Migration and rollout considerations

None. The change is confined to worker-side tooling; the integrator's full Delivery gate is
unchanged. `scripts/repair-gate.sh` and `.github/workflows/swarm-worker.yml` delegate to
`worker-check.sh` and need no edit.

## Test evidence

Red-first: the new module failed with 8 failures + 6 errors against the unmodified gate and
shell script (stale XML changed the verdict, `usable_reports` did not exist, no private
directory). After implementation:

- `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_reports.py' -v`
  -> `Ran 18 tests in 0.017s` / `OK`
- `shellcheck scripts/worker-check.sh` -> exit 0, no findings
- `python3 -I -m unittest discover -s scripts/tests -q`
  -> `Ran 1077 tests in 29.644s` / `FAILED (failures=6)`. All six are pre-existing
  `test_check_pack` failures caused by the known skip-worktree CI-profile `opencode.json`
  artifact (OPS-CW-003-01), not by this change: `git ls-files -v opencode.json` is `S`,
  the worktree file is byte-identical to `.github/opencode.ci.json` and differs from
  `git show HEAD:opencode.json`. With HEAD bytes restored the same suite is green.
- `bash scripts/python-coverage.sh` (HEAD `opencode.json` bytes temporarily restored,
  `PYTHONPATH=/tmp/opencode/covlib`, `REQUIRE_PY_COVERAGE=1`)
  -> `python coverage: every changed script line is covered`
- Coverage detail:
  `PYTHONPATH=/tmp/opencode/covlib COVERAGE_FILE=/tmp/opencode/wg.coverage python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' -m unittest discover -s scripts/tests -q`
  then `python3 -m coverage report -m --include='scripts/worker_gate.py'`
  -> `scripts/worker_gate.py 176 0 70 0 100%` (`missing_lines: []`, `missing_branches: []`)
- Functional end-to-end simulation of the shell isolation block with synthetic XML and a
  fake Maven writer (no real integration tests):
  `SHARED_FILES: TEST-com.x.FreshTest.xml`, `PRIVATE_FILES: TEST-com.x.FreshTest.xml`,
  `SUCCESS_RUN: integration tests passed`,
  `COMPILE_RUN_EXIT=1 MSG=integration run failed without fresh, usable test results (compile or setup error)`.
  A stale `TEST-com.x.StaleTest.xml` with `failures="2"` was injected first and had no effect.

Entry point -> test trace: `.github/workflows/swarm-worker.yml` (`CHECK_SCRIPT: scripts/worker-check.sh`)
-> `scripts/worker-check.sh:39-58` (private dir, clear, Maven, copy, explicit gate call)
-> `scripts/worker_gate.py:main()` -> `usable_reports()` and `decide()`. `main()` and
`usable_reports()` are driven directly by `scripts/tests/test_worker_reports.py`; the shell
wiring is asserted by `WorkerCheckScriptTest` in the same file.

## Coverage

`scripts/worker_gate.py`: 176 statements, 0 missed; 70 branches, 0 partial; 100%.
Every new or changed branch and the test that takes it:

- `args = argv or sys.argv[1:] or ["0"]` — existing `test_missing_maven_status_defaults_to_zero`,
  `test_empty_argv_falls_back_to_sys_argv`, `test_default_argument_reads_sys_argv`
  (both `test_worker_gate.py` and `test_worker_gate_coverage.py`).
- `mvn_failed = args[0] != "0"` — all `main()` tests, both statuses.
- `len(args) > 1 and args[1]` true with a value — every `FreshReportDirectoryTest` case
  passing `str(self.fresh)`.
- `len(args) > 1` false — `test_single_argument_uses_the_default_directory`.
- `args[1]` empty — `test_empty_second_argument_uses_the_default_directory`.
- `reports.is_dir()` false (short-circuit) — `test_stale_failure_does_not_disguise_a_compile_failure`
  plus existing `MainTest` cases with `reports=False`.
- `reports.is_dir()` true and `usable_reports()` true — `test_stale_failure_does_not_fail_a_successful_run`.
- `reports.is_dir()` true and `usable_reports()` false — `test_nonzero_build_with_unusable_fresh_reports_fails`.
- `mvn_failed and not usable` true — `test_stale_failure_does_not_disguise_a_compile_failure`,
  `test_nonzero_build_with_unusable_fresh_reports_fails`.
- `mvn_failed` true, `usable` true — `test_fresh_failure_reaches_the_repair_classifier`.
- `mvn_failed` false — `test_stale_failure_does_not_fail_a_successful_run`.
- `failing_classes(...)` selected — `test_fresh_failure_reaches_the_ordinary_classifier`.
- `set()` selected (not usable) — `test_stale_failure_does_not_disguise_a_compile_failure`.
- `mvn_failed and not failing` true — `test_nonzero_build_with_all_passing_fresh_reports_fails`.
- `mvn_failed` true with a failing class — `test_fresh_failure_reaches_the_repair_classifier`.
- `usable_reports` loop with no report / non-report file — `test_empty_directory_has_no_usable_results`,
  `test_non_report_files_are_ignored`.
- `usable_reports` `ET.ParseError` -> `continue` — `test_truncated_xml_alone_is_not_usable`,
  `test_truncated_xml_followed_by_a_valid_report_is_usable`.
- `usable_reports` parse success -> `return True` — `test_valid_report_is_usable`.
- `usable_reports` loop exit after `continue` — `test_truncated_xml_alone_is_not_usable`.

No threshold, exclusion, pragma or skip was added or changed.

## Findings

- Verified empirically (tool evidence) that Surefire 3.2.5 ignores both
  `-Dsurefire.reportsDirectory=/path` and `-Dproject.build.directory=/path`: two one-class
  Maven runs wrote reports to `apps/api/target/surefire-reports` regardless. The fix therefore
  isolates at the file level (clear shared `TEST-*.xml`, copy into a private directory) rather
  than relying on an unsupported CLI property. `apps/api/pom.xml` is outside scope.
- The gate keeps `REPORTS` as a legacy fallback for direct/manual single-argument invocations;
  the swarm path always passes the private directory explicitly, so stale XML cannot affect a
  gate invocation. Existing docs (`docs/reference/testing-guide.md`) and the `REPORTS` constant
  comments still describe the single-argument form; that file is outside this task's scope and
  is left for the maintainer to update.
- Two pre-existing environment facts, unrelated to this change and not edited: (1) `main`
  carries unresolved conflict markers in
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7{ParseException,Parser,Segment}.java`
  (found by `git grep -nE '^(<<<<<<<|>>>>>>>) '`); (2) the worktree `opencode.json` is the CI
  profile under `skip-worktree`, causing six `test_check_pack` failures locally.
- The coverage package is absent from this image and `pip` is unavailable. A pure-Python
  coverage 7.17.4 wheel was fetched from PyPI into `/tmp/opencode/covlib` and used via
  `PYTHONPATH` for real branch measurement; nothing was added to the repository.
- `scripts/check.sh` was not run: it invokes `apps/api/check.sh` (`mvn verify`, every
  integration test), which the worker instructions forbid running. Shell lint, the full Python
  suite, the Python branch-coverage gate and a synthetic end-to-end simulation were run instead.

## Lessons for workers

- Before designing a report-directory fix, verify the tool actually supports the CLI property;
  Surefire's `reportsDirectory` has no user property in 3.2.5 (checked against the archived
  plugin docs and two live Maven runs). A documented-looking `-D` flag can be silently ignored.
- When the worktree `opencode.json` is flagged `S`, six `test_check_pack` tests fail for reasons
  unrelated to your change. Restore `git show HEAD:opencode.json` temporarily (with an EXIT
  trap to put the CI bytes back) before running the Python coverage gate, which otherwise
  aborts on those failures.
- `bash scripts/python-coverage.sh` silently falls back to plain unittest when `coverage` is
  missing. Set `REQUIRE_PY_COVERAGE=1` and provide the module via `PYTHONPATH`, or you will
  record a green result that measured nothing.
