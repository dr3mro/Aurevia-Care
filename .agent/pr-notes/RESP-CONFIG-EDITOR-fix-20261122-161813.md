## Summary

Fix pass `RESP-CONFIG-EDITOR-fix-20261122-161813` on branch
`swarm/RESP-CONFIG-EDITOR-876` (HEAD `d78f8c2b`, merged with `origin/main`
`f1cdda10`). The scoped responsive `ConfigEditor` change from the original
worker is complete and every in-scope gate is green. This pass re-verified it on
the current tree, obtained the three read-only reviewer verdicts that **every
earlier fix note omitted** (`scripts/quality-gate.sh` ->
`scripts/check-verdicts.py` requires them), and confirmed the sole remaining
blocker is a pre-existing defect in protected tooling (`scripts/worker_gate.py`)
outside this task's scope. No production source was changed in this pass; the
only file added is this hand-off note.

## Affected requirements

WEB-07, UX-01 (story `RESP-CONFIG-EDITOR`, task `00250000`). The component is a
presentation-only responsive adaptation; no requirement behaviour was added.

Acceptance mapping: WEB-07/UX-01 "presentation styling flexible, English and
Arabic with RTL, no loss of content at supported widths" -> responsive
`ConfigEditor.responsive.test.tsx` asserts one labelled structure at
compact/medium/expanded, logical-properties-only inline styles, 16 px field text
and coarse-pointer `touch` classes, a stable keyboard order, RTL/Arabic isolated
`BDI dir="auto"` wrapping runs, long English/Arabic value wrapping, and draft
survival across a simulated viewport change. Pixel geometry (320/360/390/768/
820/1024/1280 px, 44x44 targets, 200% zoom) remains to the Playwright tasks —
jsdom cannot lay out pixels.

## Risk class

Routine (docs/WORKFLOW.md): presentation-only JSX attributes, a class name, one
`useBreakpoint()` call and logical inline styles in
`apps/staff-web/src/features/admin/ConfigEditor.tsx`, plus a jsdom responsive
test. No data, authorization, API or state-machine change.

## Review owner

Senior React and Vite (staff web), with Senior TypeScript for the strict typing
of the breakpoint fixture map. No clinical, security or architecture review
required (see "Clinical and architecture review").

## Data changes

None. No migration, backfill, lock or volume impact; no reserved migration
version used.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope touched; no audit event
added or changed. No real patient data, identifier or credential appears in
code, tests or notes — all fixtures are synthetic (synthetic alpha organization
and booking-rule configuration values).

## Migration and rollout considerations

None. Presentation-only change with no schema or deployment impact.

## Test evidence

Evidence source: executed tool commands (local Vitest, tsc, eslint, Vite build,
V8 coverage, Python unittest) and three read-only reviewer reports — not a
simulated user or human participant.

- `git merge --no-edit origin/main` (at start) -> `Already up to date.`
  (`origin/main` `f1cdda10` was already an ancestor of HEAD). No conflict markers
  in either scoped file (verified with a `git grep` for conflict-marker lines ->
  none).
- `cd apps/staff-web && bash check.sh` -> `EXIT=0`, all stages passed:
  `gen:api: generated client matches the contract`; `npm run typecheck`;
  `npm run lint`; `npm run build` + bundle-size; `npm run test:coverage` ->
  `Test Files  207 passed (207)`, `Tests  2548 passed (2548)`.
  (Console stack traces in the log come from pre-existing negative tests in
  `ThemeProvider.test.tsx` / `I18nProvider.test.tsx` that intentionally render
  without a provider; all tests passed.)
- `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`
  -> `Test Files  1 passed (1)` / `Tests  6 passed (6)`.
- Typecheck final lines (mandatory paste), from
  `cd apps/staff-web && npm run typecheck`, `TYPECHECK_EXIT=0`:
  '''
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  '''
  (no diagnostics — every `error TS…` resolved).
- Lint final lines, from `npm run lint`:
  '''
  > smartclinic-staff-web@0.1.0 lint
  > eslint . --max-warnings 0
  '''
- `python3 -I scripts/check-constraints.py --base origin/main --require-base`
  -> `CONSTRAINTS_EXIT=0` (no added suppression, exclusion, skipped test or
  lowered threshold).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
  -> `Ran 143 tests` / `FAILED (errors=2)` — pre-existing, out of scope; see
  "Findings" and "Why this is not finished".
- Byte-identity proof of the out-of-scope blocker:
  `git rev-parse HEAD:scripts/worker_gate.py` and
  `git rev-parse origin/main:scripts/worker_gate.py` both
  `b5c66a108c48ea454c46de05b026a2c49d3d6e9a`; the two test files likewise match
  on HEAD and `origin/main`. `git diff origin/main...HEAD -- scripts/` is empty.
  Introduced by `8f363e02 FIX-WORKER-REPORTS: isolate integration reports per
  swarm gate run`.
- Not run: `scripts/check.sh` (calls `apps/api/check.sh` = full `mvn -q -B
  verify`, >10 min, forbidden for workers) and `scripts/worker-check.sh`
  (full integration gate + SpotBugs; the integrator/Delivery own it). The
  canonical frontend gate `bash apps/staff-web/check.sh` was run instead. No
  OWASP/NVD result is claimed (`NVD_API_KEY` unset; worker-image limit).
- Entry-point-to-test trace: `ConfigEditor` (mounted by `ConfigPage.tsx` ->
  `AdminScreen.tsx` at the admin configuration screen) -> root section element
  with `data-part="config-editor"` / `data-breakpoint` ->
  `tests/responsive/features-admin/ConfigEditor.responsive.test.tsx` asserts
  structure at compact/medium/expanded, 16 px field text, coarse-pointer
  targets, a stable keyboard order, RTL/Arabic isolated wrapping runs, long-value
  wrapping, and draft survival across a simulated viewport change.

## Review verdicts

- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

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

`ConfigEditor.tsx` is fully covered. No new executable branch was added to the
component — the edits are JSX attributes, a class name, one `useBreakpoint()`
call and inline style objects (no `if`/ternary/`&&`/`switch`/`catch`). Branch
map for the changed lines:
- `useBreakpoint()` result driving `data-breakpoint` (three values) -> responsive
  test 1 (compact/medium/expanded renders) and test 6 (`matchMedia` change
  events driving compact -> medium -> expanded with draft retained).
- Root/table `data-part`, `data-breakpoint`, `className="stack"`, logical inline
  styles -> responsive test 1 (`:125-146`) and logical-only assertions in tests
  2 and 4.
- `overflowWrap: 'anywhere'` on keys/values -> responsive tests 4 and 5
  (long English and Arabic wrapping, BDI `dir="auto"`).
- Approval-cell `inline-flex`/`flexWrap` style is rendered on every row; existing
  approved/pending branches stay covered by `admin-components.test.tsx`.

No coverage threshold, exclusion or skip was added or changed.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of
information, critical results, cross-organization sharing or state-machine
change. Synthetic fixtures only. All layout claims are simulated DOM assertions;
pixel-level checks remain to the Playwright tasks.

## Findings

- **Blocker (STILL OPEN, out of scope).** `scripts/worker_gate.py:63`
  `usable_reports()` calls `Path(reports_dir).glob("TEST-*.xml")` on the absent
  `apps/api/target/surefire-reports`. Two tests that mock `Path.is_dir` -> True
  error: `test_integration_failure_still_fails_the_gate`
  (`scripts/tests/test_worker_gate.py`) and
  `test_maven_failure_with_failing_reports_still_fails`
  (`scripts/tests/test_worker_gate_coverage.py`), both with
  `FileNotFoundError: [Errno 2] No such file or directory:
  'apps/api/target/surefire-reports'`. Pre-existing on `origin/main`
  (byte-identical hashes above; introduced by `8f363e02`). `scripts/` is
  protected tooling owned by the existing `FIX-WORKER-REPORTS` task (marked
  `[x]` in TODO.md but incomplete). A swarm PR touching `scripts/` is never
  auto-merged (`validate_candidate` emits a `scope` violation), and worker
  `task-requests` cannot name script paths, so I did not edit it and add no
  task-request for it.
- **Gap fixed in this pass.** Every earlier fix note on this branch
  (`…-091414`, `…-092135`, `…-092934`, `…-133048`, `…-143824`) lacked the
  `## Review verdicts` section that `scripts/quality-gate.sh` ->
  `scripts/check-verdicts.py` requires for any change touching non-doc files.
  This note carries GO from all three required reviewers. This is necessary but
  not sufficient: the Python tooling gate above still fails on a fresh checkout.
- Earlier fix passes 1-3, fix worker 1's round 2 and fix worker 5 only rewrote
  notes and re-ran the frontend gate; none ran the Python tooling gate or
  repaired the tooling, which is why their local green never matched the
  integrator's fresh-checkout red. I did not repeat that approach; I reproduced
  the actual blocker directly and verified the scoped code is green.
- Two earlier blockers are resolved on current main by `20bbffe6`: the Java
  `ExportRecord` test-compile error and the seven failing staff-web responsive
  test files. Not actioned here.
- The `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` lines in the
  unittest stdout are emitted by tests that intentionally exercise the tracker
  rule; they are expected test output, not a violation from this pass.

## Lessons for workers

- A fix pass that only rewrites notes and re-runs the frontend gate cannot make
  this PR green: the blocker is the Python tooling gate. Verify the whole worker
  gate, not just the module you changed.
- Include the `## Review verdicts` section with GO from all three default
  reviewers in every notes file; `check-verdicts.py` fails closed without it for
  any change that touches non-documentation files.
- In this cloud worker `apps/api/target/surefire-reports` is absent; any test
  that mocks `Path.is_dir` -> True while calling `usable_reports()` reproduces
  the FileNotFoundError. Confirm with `python3 -I -m unittest discover -s
  scripts/tests -p 'test_worker_gate*.py'`.
- The bug is pre-existing on main and lives in protected `scripts/`; a swarm
  worker must not fix it in scope. It needs a FIX- repair task on main (trusted
  job) with maintainer review. Worker `task-requests` cannot name script paths.
- When main advances, re-check every reported blocker against the current tree;
  `20bbffe6` resolved two of the three without any action from this PR.
- Do not rewrite `ConfigEditor.tsx` or its responsive test: they are complete,
  green and covered.

## Why this is not finished

The scoped RESP-CONFIG-EDITOR change is complete and all in-scope gates are green
(`bash apps/staff-web/check.sh`, typecheck, lint, build, 100% coverage, three
reviewer GOs). The PR still cannot pass the integrator gate because of a
pre-existing, out-of-scope defect in protected tooling on main:

- **Failing command:** `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
- **Error:** `Ran 143 tests` / `FAILED (errors=2)` —
  `FileNotFoundError: [Errno 2] No such file or directory:
  'apps/api/target/surefire-reports'` in
  `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
  `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`.
- **Most likely cause:** `worker_gate.usable_reports()`
  (`scripts/worker_gate.py:63`) does
  `for report in sorted(Path(reports_dir).glob("TEST-*.xml")):`; when
  `Path.is_dir` is mocked True and the directory is absent, `glob` raises
  `FileNotFoundError` instead of yielding nothing. `main()` short-circuits in
  production (`reports.is_dir() and usable_reports(reports)`), so only the
  mocked tests trigger it. Confidence: high — reproduced directly and the file
  is byte-identical on `origin/main`.
- **What I already tried:** ran the full frontend gate (green), standalone
  typecheck (green), the constraint gate (green), reproduced the worker-gate
  failure directly, proved the defect is unchanged from `origin/main`, and
  obtained all three reviewer verdicts. I did not edit `scripts/` because it is
  protected, outside this task's scope, and a swarm PR touching it is never
  auto-merged.
- **Exact next step (FIX- repair task on main, created by a trusted job, with
  maintainer review; reopen or supersede the closed `FIX-WORKER-REPORTS`
  task):** keep both existing tests unchanged; add an absent-directory
  regression test asserting
  `usable_reports("apps/api/target/surefire-reports")` returns `False` without
  raising; wrap the glob at `scripts/worker_gate.py:63` in
  `try: reports = sorted(Path(reports_dir).glob("TEST-*.xml")) / except OSError:
  return False`, leaving the rest of the loop unchanged. Do **not** add an
  `is_dir()` guard (the tests mock it True), do not touch `main()`,
  `failing_classes()`, any threshold or any exclusion. Prove with
  `python3 -I -m unittest discover -s scripts/tests` -> OK and
  `bash scripts/python-coverage.sh` -> exit 0. After that lands on main and is
  merged into `swarm/RESP-CONFIG-EDITOR-876`, the integrator gate is green and
  the ConfigEditor change merges unchanged — do not rewrite it.
