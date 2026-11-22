# RESP-CONFIG-EDITOR — fix pass (branch `swarm/RESP-CONFIG-EDITOR-876`)

## Summary

Fix pass for task `RESP-CONFIG-EDITOR`, continuing from the earlier hand-offs
(`RESP-CONFIG-EDITOR-20261122-085352.md` and the three
`RESP-CONFIG-EDITOR-fix-20261122-*.md` files) rather than starting over. The
responsive `ConfigEditor` implementation and its new responsive test were already
committed on this branch (`1db42e32`); the earlier fix passes added only notes and
one regression-guard assertion (`f35fc48a`).

I re-read the two scoped files, the story task, decision 0004 and the responsive
layout contract, then re-ran the in-scope gates from the current checkout. Every
in-scope gate passes with 100% coverage, so no production or test change was
needed: the scoped work is complete and correct. The only new file in this pass
is this note at the exact path named in the prompt.

The integration verdict is still red, but the cause is a reproducible tooling
defect in `scripts/worker_gate.py` on `origin/main`, which this worker is
forbidden to edit (protected delivery tooling). It is **not** a defect of the
scoped staff-web change. Details, first-hand reproduction and the exact next
step are under `## Findings` and `## Why this is not finished`.

Evidence source: **executed tool commands** (Vitest, tsc, ESLint, Vite build,
bundle-size gate, V8 coverage, Python unittest discovery, git) run locally by me,
plus a bounded static review by the `smartclinic-senior-react` specialist. No
simulated-user or human-participant evidence is claimed. All fixtures are
synthetic (`alphaOrganizationId`, `syntheticConfigurations`,
`'Synthetic retained draft'`). No patient data or credentials appear in code,
tests or notes.

Entry-point-to-test trace: `apps/staff-web/src/features/admin/AdminScreen.tsx`
and `src/features/admin/ConfigPage.tsx` render `ConfigEditor`; the new test
renders that component inside the real shell providers (`ThemeProvider` +
`I18nProvider`) and drives it through its normal `onPropose` / `onApprove` props.
`AdminScreen`/`ConfigPage` -> `ConfigEditor` -> `useBreakpoint()` and
`AdminTableRegion`/`Form`/`FormField`/`MixedScriptText` ->
`tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`.

## Affected requirements

**WEB-07, UX-01.** Acceptance mapping:

| Story step | Acceptance | Where satisfied / evidence |
|---|---|---|
| 1. Read component, existing test, layout doc, stylesheet, hook | Done | `ConfigEditor.tsx`, `src/features/admin/admin-components.test.tsx`, `docs/design/responsive-layout.md`, `src/theme/responsive.css`, `src/theme/useBreakpoint.ts`. |
| 2. Usable 320-1280 px, LTR/RTL, long EN/AR strings wrap, columns stack on phones, 44 px coarse targets, 16 px inputs, safe areas, logical properties, no hidden clinical column, 200% zoom, single-column phone editor | Done | Shared `Form`/`FormField` supply `.page`, `.field`, `.touch`, `data-layout="single-column"`, `data-breakpoint`, `data-coarse-pointer`, sticky footer and `scroll-padding-block-end`; `overflowWrap: 'anywhere'`/`whiteSpace: 'normal'` on configuration values; `flexWrap: 'wrap'` on the badge/action row; `inlineSize: '100%'`, `textAlign: 'start'`, `paddingBlock` logical styles; versions stay in a labelled `tabindex=0` `AdminTableRegion`; no `display: none` on clinical content; no fixed heights. Asserted by the six new tests. Pixel geometry is deferred to the Playwright tasks (jsdom cannot lay out pixels). |
| 3. Reuse `responsive.css` classes and the hook; use logical inline styles for a missing class; do not edit the stylesheet | Done | `useBreakpoint()`, `.stack`, `.page`, `.field`, `.touch`; no dedicated clinical-value/badge-row class exists, so logical inline styles were used as the story directs. `responsive.css` is untouched. |
| 4. New test file mocks `matchMedia` for compact/medium/expanded; structure, labels/roles, keyboard order, RTL | Done | `tests/responsive/features-admin/ConfigEditor.responsive.test.tsx` — 6 tests, including a live compact -> medium -> expanded change event proving the typed draft survives without a remount. |
| 5. No change to behaviour, text, API calls or exported names | Done | The diff versus `origin/main` is one `useBreakpoint()` call, JSX attributes, a class name, logical inline style objects and an expanded doc comment. No prop, handler, message key or export changed. |

## Risk class

**Routine** (`docs/WORKFLOW.md`): a contained presentation change in one
component plus one new test file. No behavior, data, contract, authorization or
migration change.

## Review owner

Senior React and Vite (`smartclinic-senior-react`), with strict TypeScript
verified by `npm run typecheck` (`smartclinic-senior-typescript`). No clinical or
defensive-security review is required (see `## Clinical and architecture review`).

A bounded static review by the `smartclinic-senior-react` specialist (evidence:
**reviewer**, not a tool run) returned **no actionable defects** in either scoped
file. It independently verified logical-only inline styles, value wrapping for
long English and Arabic, the full five-column table inside the labelled
keyboard-focusable region, single-column compact fields with `.field touch`,
document-level RTL with isolated `<bdi dir="auto">` runs, and draft retention
across band changes. It also confirmed no exported name, text, validation,
submit behavior or API changed. Its one non-blocking note — that the responsive
test titled "16px text" asserts class membership rather than the font-size
rule — is already covered by the dedicated
`tests/theme/responsiveStylesheet.test.ts` (line 199 asserts
`.field { font-size: 16px }`), so no duplicate assertion was added.

## Data changes

None. No migration, backfill, lock/volume impact, rollback or forward-fix; no
reserved migration version used.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope touched; no audit event
added or changed.

## Migration and rollout considerations

None. Presentation-only change; nothing to roll out beyond the existing bundle.

## Test evidence

All evidence below is from **executed tool commands** (not a reviewer, simulated
user or human participant), run from the repository root unless noted.

- `git merge --no-edit origin/main` (start of pass) -> `Already up to date.`,
  exit 0. No conflict-marker lines in either scoped file: a `git grep -nE`
  for the start/end conflict-marker prefixes over
  `apps/staff-web/src/features/admin/ConfigEditor.tsx` and
  `apps/staff-web/tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`
  returned no matches.
- `bash scripts/frontend-typecheck-check.sh origin/main` -> `EXIT: 0`:
  ```
  frontend typecheck: apps/staff-web
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  > smartclinic-staff-web@0.1.0 lint
  > eslint . --max-warnings 0
  frontend typecheck: passed (or no frontend files changed)
  ```
  No `error TS…` diagnostics; this is the exact gate the worker re-runs.
- `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`
  -> `EXIT: 0`, `Test Files  1 passed (1)`, `Tests  6 passed (6)`,
  `Duration  889ms`. (Red-first evidence was recorded in the original pass:
  before the component change most of these tests failed on missing
  `data-part`/`data-breakpoint`, physical `width: 100%` and field-wrapper
  attributes.)
- `cd apps/staff-web && bash check.sh` -> `EXIT: 0`. Every stage passed under
  `set -e`: `gen:api:check` (`generated client matches the contract`),
  `typecheck`, `lint`, `build` + bundle-size gate, then coverage:
  ```
  Test Files  173 passed (173)
       Tests  2046 passed (2046)
  No files with missing coverage.
  213 files fully covered.
  4 empty files skipped.
  Statements   : 100% ( 3915/3915 )
  Branches     : 100% ( 1990/1990 )
  Functions    : 100% ( 1320/1320 )
  Lines        : 100% ( 3738/3738 )
  ```
  Mandatory typecheck final lines, from the same green run (no diagnostics):
  ```
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  Console stack traces in the log come from pre-existing negative tests in
  `tests/theme/ThemeProvider.test.tsx` / `tests/i18n/I18nProvider.test.tsx`
  that intentionally render a hook without its provider; all 2046 tests passed.
- Reproducing the out-of-scope gate defect (read-only investigation, no fix
  applied): `python3 -I -m unittest discover -s scripts/tests` -> `EXIT: 1`,
  `Ran 1127 tests in 25.234s`, `FAILED (failures=6, errors=2)`. The two errors
  are `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate`
  and `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`,
  both `FileNotFoundError: [Errno 2] No such file or directory:
  'apps/api/target/surefire-reports'`. Full trace saved during the run.
- `git diff --quiet origin/main -- scripts/worker_gate.py scripts/tests/test_worker_gate.py scripts/tests/test_worker_gate_coverage.py`
  -> identical to `origin/main`; `git diff origin/main...HEAD --name-only`
  contains no path under `scripts/`, `AGENTS.md`, `.opencode/` or
  `.github/workflows/`. The tooling defect is upstream, not introduced by this
  task.

Not run by design: `scripts/check.sh` and `scripts/worker-check.sh`. They invoke
`apps/api/check.sh` / `mvn -Dgroups=integration test` (the full Java/integration
gate, over ten minutes) and SpotBugs; workers are explicitly forbidden to run
them. The integrator and Delivery run the full gate and the 100% coverage check.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (its `npm run test:coverage` step;
V8 per-file thresholds at 100%, enforced by the module's own config).

Result:
```
No files with missing coverage.
213 files fully covered.
4 empty files skipped.
Statements   : 100% ( 3915/3915 )
Branches     : 100% ( 1990/1990 )
Functions    : 100% ( 1320/1320 )
Lines        : 100% ( 3738/3738 )
```

`ConfigEditor.tsx` is fully covered. **No new executable branch was added** by
the responsive change (a `useBreakpoint()` call, JSX attributes, a class name
and inline style objects: no `if`, ternary, `&&`, `switch` or `catch`). Existing
branches stay covered by the suite:

| Branch in `ConfigEditor.tsx` | Covering test |
|---|---|
| empty proposed value (`proposedValue.length === 0`) | `admin-components.test.tsx` — `blocks an empty or partial draft…` |
| invalid civil date (`!isValidCivilDate`) | `admin-components.test.tsx` — `rejects a non-calendar effective date…` |
| synchronous `onPropose` returning `false` vs a value (`outcome !== false`) | `admin-components.test.tsx` — `proposes a complete local draft` and the rejection cases |
| promise outcome (`outcome instanceof Promise`) and `accepted !== false` vs `false` | `ConfigEditor real-API adaptations` tests in `admin-components.test.tsx` |
| no versions (`versions.length === 0`) vs mapped rows | `admin-components.test.tsx` — `shows a defined empty state…` |
| `entry.effectiveFrom === ''` vs a date | `shows tenant-scoped versions, effective dates, approval and approver` |
| `entry.approved` vs pending, `onApprove === undefined` vs handler, effective badge vs null | the suite above plus `keeps approve actions reachable in a stable keyboard order` |
| responsive bands compact / medium / expanded (from `useBreakpoint`) | new `keeps one labelled, single-column, keyboard-scrollable structure at every band` and `follows a viewport change without remounting or losing the typed draft` |
| coarse-pointer branch inside `FormField` | new `stacks both draft fields with 16px text and coarse-pointer targets` |

The new test file contains no production code, so it adds no coverage
obligation of its own. No threshold, exclusion or test skip was added.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of
information, critical results, cross-organization sharing or state-machine
change. Synthetic fixtures only. All layout claims are simulated DOM
assertions; pixel-level checks remain to the Playwright tasks, as the story
directs.

## Findings

- **Scope outcome:** the implementation and its test were already committed
  (`1db42e32`) when this run started. I verified rather than rewrote them; every
  in-scope frontend gate passes at 100% coverage. No defect was found in the
  scoped code and no production/test change was needed in this pass.
- **Out-of-scope blocker (tooling defect on `origin/main`):**
  `scripts/worker_gate.py::usable_reports()` (line 63) calls `Path(reports_dir).glob(...)`
  on a path that need not exist. Two unit tests mock `Path.is_dir` to `True` and
  call `main()` with the default `apps/api/target/surefire-reports` path, which
  is absent in a clean checkout, so `python3 -I -m unittest discover -s
  scripts/tests` errors twice with `FileNotFoundError`. This makes every worker's
  local Python gate and the integrator gate red with
  `integration run failed without fresh, usable test results (compile or setup error)`.
  The file and its tests are byte-identical to `origin/main` and `scripts/` is
  protected delivery tooling, so I did **not** edit it (AGENTS.md: a swarm PR
  editing `scripts/` is never merged automatically). Earlier fix passes that
  only re-ran the frontend gate could never clear this.
- **Second, known worker-image false positive:** 6 `check-pack.py` unit failures
  (`test_check_mode_detects_drift`, `test_cli_entry_point`,
  `test_main_reports_success`, `test_committed_opencode_json_is_generated`,
  `test_repository_pack_is_valid`,
  `test_simulated_user_skills_need_no_process_sections`) come from the CI
  `opencode.json` profile installed under `skip-worktree`
  (`git ls-files -v opencode.json` -> `S`; worktree sha256 `50005c9c…` vs HEAD
  `0dc2ac8e…`). This is OPS-CW-003-01, not a committed defect. I did not
  regenerate or commit `opencode.json`.
- Pre-existing and outside scope: conflict markers in three HL7 Java files
  (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`) are present on
  `origin/main` inside Javadoc comments; earlier evidence shows Java compilation
  and `check-docs.py` still pass. Left untouched.
- `responsive.css` has no dedicated class for a clinical configuration-value
  cell or a mixed badge+action row, so logical inline styles
  (`overflowWrap: 'anywhere'`, `minInlineSize: 0`, `flexWrap: 'wrap'`) were used
  rather than editing the stylesheet, as the story directs.
- `AdminTableRegion` (out of scope) uses inline `overflowX: 'auto'` instead of
  the shared `.table-region` class; its labelled, keyboard-focusable scroll
  semantics are otherwise correct and it was left unchanged.

## Lessons for workers

- A fix pass may find the work already committed. Read the scoped files, the
  earlier notes and `git log` before editing — verify and finalize rather than
  redoing the implementation.
- Write the hand-off note to the **exact** path in the prompt; previous passes
  used near-miss filenames and the task kept being returned.
- When a red gate is caused by a protected file on `main`, do not "fix" it in a
  scoped PR (the PR is never merged) and do not claim the scoped code is broken.
  Reproduce it, record the exact command and failing test names, request a task,
  and stop with `## Why this is not finished`.
- `usable_reports()` is reached through `reports.is_dir() and usable_reports(...)`;
  tests mock `is_dir` to `True`, so a directory guard cannot fix the mocked path —
  the tolerance belongs inside `usable_reports()` itself (try/except OSError
  around the glob). Keep that insight for the tooling owner.
- A test under `apps/staff-web/tests/responsive/features-admin/` reaches `src`
  via `../../../src` (three levels up). `FormField` sets `data-breakpoint` /
  `data-coarse-pointer` on its wrapping `.stack` div, not on the input. Use
  `fireEvent.change` (not a raw `value` assignment) to keep typed state in a
  resize test, and give breakpoint maps an explicit
  `{ medium: boolean; expanded: boolean }` shape under `noUncheckedIndexedAccess`.
- `bash apps/staff-web/check.sh` self-installs pinned dependencies with `npm ci`
  when `node_modules` is absent; a missing `node_modules` is not a broken checkout.

## Why this is not finished

The scoped `RESP-CONFIG-EDITOR` implementation is complete and every in-scope
gate is green with 100% coverage, but the story cannot be accepted from this
worker because the shared integration gate is red for an out-of-scope reason:

- Failing command: `python3 -I -m unittest discover -s scripts/tests`
  -> `EXIT: 1`, `FAILED (failures=6, errors=2)`.
- Most likely cause: 2 errors are the `scripts/worker_gate.py::usable_reports()`
  `FileNotFoundError` on absent `apps/api/target/surefire-reports` (pre-existing
  on `origin/main`); 6 failures are the known `opencode.json` skip-worktree
  cloud-worker artifact. Both live in protected tooling I am forbidden to edit.
- What I already tried: reproduced the failure locally, confirmed the offending
  files are byte-identical to `origin/main`, confirmed this branch touches no
  protected path, ran all in-scope frontend/typecheck/coverage gates green, and
  did not mask the failure by creating report directories or weakening a test.
- Exact next step: a maintainer/tooling worker fixes `usable_reports()` to
  tolerate an absent or unreadable report directory (wrap the `glob` in
  `try: ... except OSError: return False`, keep the existing loop and
  `main()` untouched, add a focused regression test with `Path.is_dir` mocked
  `True` and keep 100% branch coverage), then re-runs
  `python3 -I -m unittest discover -s scripts/tests` and `bash scripts/check.sh`
  on the merge result. After that gate is green, re-run the integrator on this
  branch and accept the already-complete staff-web change. The task request
  below asks the integrator to open that tooling task.

## Task requests

```task-requests
[
  {
    "title": "FIX-WORKER-GATE-ABSENT-REPORTS",
    "details": "python3 -I -m unittest discover -s scripts/tests fails with FAILED (failures=6, errors=2) on a clean checkout. Two errors are FileNotFoundError [Errno 2] 'apps/api/target/surefire-reports' from scripts/worker_gate.py::usable_reports() (line 63): the tests mock Path.is_dir to True, so main() calls usable_reports() on the absent default path and Path.glob raises. Make usable_reports() tolerate an absent/unreadable directory (wrap the glob in try/except OSError and return False), keep main(), thresholds and failing_classes() unchanged, and add a focused regression test that mocks Path.is_dir True and asserts False without raising on a non-existent path. Keep 100% line and branch coverage of scripts/worker_gate.py. This is pre-existing on origin/main and blocks the RESP-CONFIG-EDITOR integrator gate with 'integration run failed without fresh, usable test results (compile or setup error)'.",
    "scope": "scripts/worker_gate.py, scripts/tests/test_worker_gate.py, scripts/tests/test_worker_gate_coverage.py",
    "depends": ["FIX-WORKER-REPORTS"]
  }
]
```
