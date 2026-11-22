## Summary

This is fix pass `RESP-CONFIG-EDITOR-fix-20261122-143824` on branch
`swarm/RESP-CONFIG-EDITOR-876` (HEAD `7e2577e62b32f5453b77ee021c1f96a983cff1ef`).
The scoped responsive `ConfigEditor` change from the original worker is complete
and every in-scope gate is green. This pass re-verified it after `origin/main`
advanced, confirmed two of the three integrator blockers were resolved on main,
and confirmed the last blocker is a pre-existing defect in protected tooling
(`scripts/`) outside this task's scope. No production source was changed in this
pass; the only file added is this hand-off note.

## Affected requirements

WEB-07, UX-01 (story `RESP-CONFIG-EDITOR`, task `00250000`). The component is a
presentation-only responsive adaptation; no requirement behaviour was added.

## Risk class

Routine (docs/WORKFLOW.md): presentation-only JSX attributes, a class name, one
`useBreakpoint()` call and logical inline styles in
`apps/staff-web/src/features/admin/ConfigEditor.tsx`, plus a jsdom responsive
test. No data, authorization, API or state-machine change.

## Review owner

Senior React and Vite (staff web). No clinical, security or architecture review
required (see "Clinical and architecture review").

## Data changes

None. No migration, backfill, lock or volume impact; no reserved migration
version used.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope touched; no audit event
added or changed. No real patient data, identifier or credential appears in
code, tests or notes — all fixtures are synthetic.

## Migration and rollout considerations

None. Presentation-only change with no schema or deployment impact.

## Test evidence

Evidence source: executed tool commands (local Vitest, tsc, eslint, Vite build,
V8 coverage, Python unittest) — **not** a reviewer, simulated user or human
participant.

- `git merge --no-edit origin/main` -> `Already up to date.` (`origin/main`
  `7ab12216` was already an ancestor of HEAD). No conflict markers in either
  scoped file (`git grep -nE '^(<<<<<<<|>>>>>>>) '` -> none).
- `cd apps/staff-web && bash check.sh` -> all stages passed (generated-client
  parity, typecheck, lint, build/bundle-size, coverage):
  `Test Files  207 passed (207)`, `Tests  2548 passed (2548)`,
  `Duration  95.60s`. Console stack traces in the log come from pre-existing
  negative tests in `ThemeProvider.test.tsx` / `I18nProvider.test.tsx` that
  intentionally render without a provider; all tests passed.
- Typecheck final lines (mandatory paste), from `cd apps/staff-web && npm run typecheck`:
  '''
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  '''
  (no diagnostics — every `error TS…` resolved; exit 0).
- `bash scripts/frontend-typecheck-check.sh origin/main` ->
  `frontend typecheck: passed (or no frontend files changed)`, `EXIT=0`
  (tsc --noEmit and eslint both clean).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'` ->
  `Ran 143 tests` / `FAILED (errors=2)` — pre-existing, out of scope; see
  "Findings" and "Why this is not finished".
- Targeted responsive test `tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`
  (6/6) is included in the 207-file full run above.
- Not run: `scripts/check.sh`. It delegates to `apps/api/check.sh` (`mvn -q -B
  verify`, the full Java/integration gate, >10 min), which is explicitly
  forbidden for workers. The canonical frontend gate
  `bash apps/staff-web/check.sh` was run instead; the integrator and Delivery
  run the full gate. No OWASP/NVD result is claimed (`NVD_API_KEY` unset).
- Entry-point-to-test trace: `ConfigEditor` (rendered by the admin configuration
  screen) -> `data-part="config-editor"` / `data-breakpoint` on the root
  `<section>` -> `tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`
  asserts structure at compact/medium/expanded, 16px field text, coarse-pointer
  targets, a stable keyboard order, RTL/Arabic isolated wrapping runs, long-value
  wrapping, and draft survival across a simulated viewport change.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (its `npm run test:coverage` step,
V8 per-file thresholds, 100% enforced).

Result:
'''
No files with missing coverage.
213 files fully covered.
4 empty files skipped.
Statements   : 100% ( 4060/4060 )
Branches     : 100% ( 2071/2071 )
Functions    : 100% ( 1346/1346 )
Lines        : 100% ( 3877/3877 )
'''

`ConfigEditor.tsx` is fully covered. No new executable branch was added in this
pass; the underlying scoped change added JSX attributes, a class name, one
`useBreakpoint()` call and inline style objects — no `if`/ternary/`&&`/`switch`/
`catch`. Existing branches stay covered by the existing suite; the responsive
test drives the breakpoint hook through compact -> medium -> expanded via
`matchMedia` change events while asserting the typed draft survives. No coverage
threshold, exclusion or skip was added or changed.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of
information, critical results, cross-organization sharing or state-machine
change. Synthetic fixtures only. All layout claims are simulated DOM assertions;
pixel-level checks (320/360/390/768/820/1024/1280 px, 44x44 targets, 200% zoom)
remain to the Playwright tasks — jsdom cannot lay out pixels.

## Findings

- **Blocker #1 (STILL OPEN, out of scope).** `scripts/worker_gate.py:63`
  `usable_reports()` calls `Path(reports_dir).glob("TEST-*.xml")` on the absent
  `apps/api/target/surefire-reports`. Two tests that mock `Path.is_dir` -> True
  error: `test_integration_failure_still_fails_the_gate`
  (`scripts/tests/test_worker_gate.py`) and
  `test_maven_failure_with_failing_reports_still_fails`
  (`scripts/tests/test_worker_gate_coverage.py`), both with
  `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'`.
  Pre-existing on `origin/main`; `scripts/` is protected tooling owned by
  `FIX-WORKER-REPORTS` (marked `[x]` in TODO.md but incomplete). A swarm PR
  touching `scripts/` is never auto-merged and `validate_candidate` fails
  out-of-scope edits, so I did not edit it.
- **Blocker #2 (RESOLVED on main).** The Java `ExportRecord` test-compile error
  is fixed by `20bbffe6 Fix responsive landmarks and export fixture`; all four
  `new ExportRecord(...)` calls in
  `apps/api/src/test/java/com/aurevia/smartclinic/tenant/retention/OffboardingCommandTest.java`
  now pass the 7th `PROVENANCE` argument, matching the 7-field record.
- **Blocker #3 (RESOLVED on main).** The failing staff-web responsive test files
  were fixed by the same `20bbffe6` commit (AdminScreen, ChartPage, ChartScreen,
  DuplicatesPage, NotFoundPage, SignedOutPage responsive tests). The full
  207-file frontend suite now passes.
- Earlier fix passes 1-3 and fix worker 1's round-2 only rewrote notes and
  re-ran the frontend gate; they never ran the Python tooling gate or repaired
  the tooling, which is why their local green never matched the integrator's
  fresh-checkout red. The note-path theory and the HL7-Javadoc-marker theory are
  ruled out as causes (Java compilation reaches test-compile; those markers are
  inside comments).
- The `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` lines in the
  unittest stdout are produced by tests that intentionally exercise the tracker
  rule; they are expected test output, not a real violation from this pass.

## Lessons for workers

- A fix pass that only rewrites notes and re-runs the frontend gate cannot make
  this PR green: the blocker is the Python tooling gate. Verify the whole worker
  gate, not just the module you changed.
- In this cloud worker `apps/api/target/surefire-reports` is absent; any test
  that mocks `Path.is_dir` -> True while calling `usable_reports()` reproduces
  the FileNotFoundError. Confirm with `python3 -I -m unittest discover -s
  scripts/tests -p 'test_worker_gate*.py'`.
- The bug is pre-existing on main and lives in protected `scripts/`; a swarm
  worker must not fix it in scope. It needs a FIX- repair task on main (trusted
  job), which may touch scripts/ and needs maintainer review. Worker
  `task-requests` cannot name script paths, so this repair cannot be requested
  through that mechanism.
- When main has advanced, re-check every reported blocker against the current
  tree — `20bbffe6` resolved two of the three without any action from this PR.
- Do not rewrite `ConfigEditor.tsx` or its responsive test: they are complete,
  green and covered.

## Why this is not finished

The scoped RESP-CONFIG-EDITOR change is complete and all in-scope gates are
green (`bash apps/staff-web/check.sh`, typecheck, lint, build, 100% coverage).
The PR still cannot pass the integrator gate because of a pre-existing,
out-of-scope defect in protected tooling on main:

- **Failing command:** `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
- **Error:** `Ran 143 tests` / `FAILED (errors=2)` —
  `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'`
  in `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate`
  and `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`.
- **Most likely cause:** `worker_gate.usable_reports()`
  (`scripts/worker_gate.py:63`) does
  `for report in sorted(Path(reports_dir).glob("TEST-*.xml")):`; when
  `Path.is_dir` is mocked True and the directory is absent, `glob` raises
  `FileNotFoundError` instead of yielding nothing.
- **What I already tried:** ran the full frontend gate (green), the standalone
  typecheck and `frontend-typecheck-check.sh` (green), confirmed the Java and
  responsive blockers were fixed on main by `20bbffe6`, and reproduced the
  worker-gate failure directly. I did not edit `scripts/` because it is
  protected and outside this task's scope, and a swarm PR touching it is never
  auto-merged.
- **Exact next step (FIX- repair task on main, created by a trusted job, with
  maintainer review):** keep both existing tests unchanged; add an
  absent-directory regression test asserting
  `usable_reports("apps/api/target/surefire-reports")` returns `False` without
  raising; wrap the glob at `scripts/worker_gate.py:63` in
  `try: ... except OSError: return False`. Do **not** add an `is_dir()` guard
  (the tests mock it True), do not touch `main()`, `failing_classes()`,
  thresholds or exclusions. Prove with `python3 -I -m unittest discover -s
  scripts/tests` -> OK and `bash scripts/python-coverage.sh` -> exit 0. After
  that lands on main and is merged into `swarm/RESP-CONFIG-EDITOR-876`, the
  integrator gate is green and the ConfigEditor change merges unchanged.
