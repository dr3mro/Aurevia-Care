# RESP-PATIENT-OVERVIEW-PAGE — Staff web: responsive `PatientOverviewPage`

## Summary

Made the tenant- and patient-keyed overview page wrapper responsive at every
supported width without changing behaviour, text, API calls or exported names.
The page now resolves the shared breakpoint hook, applies the `stack` class
from `responsive.css` as a transparent single-column wrapper, exposes
`data-breakpoint` for jsdom structure assertions, and sets inherited logical
`overflow-wrap: anywhere` so long English and Arabic clinical values wrap
instead of forcing a horizontal page scroll. `ApiPage` remains the only `.page`
layout owner, so safe-area padding and the reading-width cap are applied once.
Added a new responsive test file that stubs `matchMedia` for compact, medium and
expanded, checks all seven required widths in both LTR and RTL, the labels and
roles, DOM keyboard order, logical-properties-only, no `display:none` on
clinical fields, and the duplicate-record alert under RTL. Synthetic fixtures
only; no dependency added; no file outside the declared scope changed.

- Scope: `apps/staff-web/src/features/patients/PatientOverviewPage.tsx`,
  `apps/staff-web/tests/responsive/features-patients/PatientOverviewPage.responsive.test.tsx`
- Branch: `swarm/RESP-PATIENT-OVERVIEW-PAGE-965` (HEAD `85db88d`, merged
  `origin/main` at the start, after the implementation stage and before finish;
  every merge reported "Already up to date").

## Affected requirements

- **WEB-07** — staff web usable from 320 px upward; labelled scroll regions and
  wrapping instead of horizontal page scroll; responsive component classes.
- **UX-01** — reflow, no clipped or overlapping text, logical properties,
  safe-area insets, keyboard order and right-to-left mirroring.
- Spec reference: `docs/design/responsive-layout.md` (breakpoint table §2,
  page grid §3, component matrix §5, RTL §11, zoom §12, verification matrix
  §13), decision `docs/decisions/0004-responsive-staff-web.md`.

## Risk class

Routine (docs/WORKFLOW.md): contained presentational change to one page wrapper
plus a new view-level test. No authorization, data, contract or state-machine
change. No clinical column or action is added or removed at a breakpoint.

## Review owner

Senior React and Vite (implementation and frontend gate) with the UX reviewer
(responsive/RTL/a11y structure). Both reviews were **simulated specialists** —
no human, clinical or patient-participant acceptance is claimed. Both returned
findings, which were resolved and re-verified; see Findings.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.
The page still issues the same single `GET /api/v1/patients/{id}` with the same
`X-Active-Organization` header; the new test asserts the synthetic descriptor
indirectly by rendering the installed page against a mocked synthetic transport.

## Authorization boundaries and audit events

Unchanged. Tenant selection still comes from `useActiveOrganization` and the
route's opaque patient id; no body or resource field is used for tenancy. No
audit event added or changed. No PHI, credential or real identifier in code,
tests or notes (fixtures are `patient-a1`, `MRN-1001`, `SYNTHETIC`).

## Migration and rollout considerations

None. Presentation-only change; no schema, contract or generated client drift.
Pixel-level claims (44 × 44 targets, 200 % zoom, no overlap) remain with the
separate Playwright projects, per `docs/design/responsive-layout.md` §13.

## Test evidence

Evidence provenance: **tool** (executed commands) unless labelled simulated
specialist. Red was observed before the production change.

1. Red — new test against unchanged production code (targeted run):
   `cd apps/staff-web && ./node_modules/.bin/vitest run --exclude 'e2e/**' tests/responsive/features-patients/PatientOverviewPage.responsive.test.tsx`
   Final lines: `Test Files  1 failed (1)` / `Tests  14 failed | 3 passed (17)`;
   representative failures `AssertionError: expected null to be 'compact'` and
   `AssertionError: expected '' to be 'anywhere'` (the wrapper had no
   `data-breakpoint`, no `page` class and no wrapping). The three label/role
   tests already passed, confirming existing content was preserved.
2. Green — same targeted command after the change: initially
   `Test Files  1 passed (1)` / `Tests  17 passed (17)`. After the two simulated
   specialist reviews added the seven-width RTL matrix and the single-`.page`
   negative assertion, the final targeted run is
   `Test Files  1 passed (1)` / `Tests  23 passed (23)`.
3. Canonical frontend gate — `bash apps/staff-web/check.sh` (generated-client
   drift, typecheck, lint, production build, WEB-08 bundle budget, Vitest with
   coverage), re-run after the review fixes. Final lines:
   `CHECK_EXIT=0`, `Test Files  159 passed (159)`, `Tests  1914 passed (1914)`,
   `No files with missing coverage.`, `207 files fully covered.`,
   `Statements : 100% (3721/3721)`, `Branches : 100% (1892/1892)`,
   `Functions : 100% (1258/1258)`, `Lines : 100% (3555/3555)`, exit 0.
   Console `Error: useTheme must be used inside ThemeProvider` lines come from
   `tests/theme/ThemeProvider.test.tsx`, which deliberately renders a consumer
   without a provider to assert that error; they are not test failures.
4. Typecheck final lines (required by the story):
   `> orevia-staff-web@0.1.0 typecheck`
   `> tsc --noEmit -p tsconfig.json`
   No `error TS…` output; `TYPECHECK_EXIT=0`.
5. Per-file coverage for the touched production file, read from
   `apps/staff-web/coverage/coverage-summary.json`:
   lines 9/9 (100 %), branches 2/2 (100 %), functions 4/4 (100 %),
   statements 9/9 (100 %).
6. `bash scripts/frontend-check.sh` — final line
   `frontend standards: policy and tool commands passed`, exit 0.
7. `bash scripts/check.sh` — exit 1 at the first stage:
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
   Confirmed once as the documented **OPS-CW-003-01** worker artifact:
   `git ls-files -v opencode.json` → `S opencode.json`; the worktree file hash
   equals `.github/opencode.ci.json`; `git show HEAD:opencode.json` is the local
   profile. No protected file was edited. Remaining stages were run
   individually: `check-docs.py`, `check-secrets.py`, `check-names.py`,
   `check-wiring.py`, `check-migrations.py --require-baseline` and
   `frontend-check.sh` all exit 0.
8. `bash scripts/python-coverage.sh` — exit 1 with
   `Ran 1127 tests in 30.462s` / `FAILED (failures=6, errors=2)`. All failures
   are in `test_check_pack` (the same skip-worktree profile artifact) and
   `test_worker_gate` / `test_worker_gate_coverage` (synthetic git/gh fixtures).
   No `scripts/*.py` file was changed by this story; this is the documented
   worker-image limit, not a change defect.

Entry point → test trace: `PatientOverviewPage` (route `/patients/:id`, bound
opaque id) → `PatientOverviewContent` → `useBreakpoint()` +
`ApiPage` (`section.page`, `data-breakpoint`) → `PatientOverviewScreen` →
asserted by `tests/responsive/features-patients/PatientOverviewPage.responsive.test.tsx`
through the installed page with a mocked synthetic transport.

## Coverage

Command: `bash apps/staff-web/check.sh` → `npm run test:coverage`
(Vitest v8, `perFile: true`, thresholds 100 % for lines/branches/functions/
statements). Result: 100 % on all 207 hand-written source files, including the
only touched production file (`PatientOverviewPage.tsx`: 9/9 lines, 2/2
branches, 4/4 functions, 9/9 statements). No threshold, exclusion or skip was
added; no test was disabled.

New or changed production branches and the test that takes each:

- No new executable `if`, ternary, `&&`, `||`, `switch` or `catch` was added to
  `PatientOverviewPage.tsx`; the wrapper JSX is unconditional. The two counted
  branches are the `patientId = params.id ?? ''` fallback (pre-existing,
  covered by the existing `uses an empty id when no route parameter is bound`
  test) and the breakpoint hook call, whose three outcomes are all asserted by
  `resolves the breakpoint and wraps the clinical content in the page class`
  for compact/medium/expanded and by the seven-width viewport matrix.
- `useBreakpoint()` outcomes: `compact` → `resolves... at compact` and widths
  320/360/390; `medium` → `resolves... at medium` and widths 768/820/1024;
  `expanded` → `resolves... at expanded` and width 1280.
- Duplicate-warning present / absent branches (in the untouched screen) are
  exercised by `keeps every clinical label and role available at this width`
  (`syntheticPatientView`) and the viewport matrix (`no duplicates`) so the
  page wrapper is verified around both states.
- RTL branch: `mirrors the flow but not the clinical content at %ipx` runs at all
  seven widths and asserts `dir="rtl"`, `lang="ar"`, Arabic labels, an isolated
  Arabic run and the duplicate-record alert, with no local `direction` override.
- Single-`.page` regression guard: `resolves the breakpoint with one page region
  and a stack wrapper` and the RTL matrix assert the wrapper is `.stack` and
  **not** `.page`, while the `ApiPage` region is `.page`.

## Clinical and architecture review

Not required for this slice: no signed data, cross-organization sharing,
patient association, prescribing or critical result is added or changed; no
contract or state transition changes. UX and Senior React reviews were
simulated (see Review owner and Findings); no real clinician or patient
acceptance is claimed. Real-patient approval remains a G5 gate.

## Findings

- **Simulated Senior React review — nested `.page` (fixed).** The first version
  put `className="page"` on the wrapper while `ApiPage` already renders its own
  `<section className="page">`; `.page` sets padding and a 1200 px reading cap,
  so content was double-padded and narrowed, and the code comment claiming
  "one wrapping grid" was false. Reviewer evidence: `ApiPage.tsx:63,70,78,84`,
  `responsive.css:47-57`. Fix (in scope): the wrapper now uses `.stack` (a
  transparent column with inherited wrapping), `ApiPage` is the sole `.page`
  owner, the comment was corrected, and a negative assertion
  (`wrapper.classList.contains('page') === false`) prevents regression.
  Re-verified: 1914 tests green, 100 % coverage.
- **Simulated UX review — RTL only asserted at compact (fixed).** The matrix
  drove all seven widths in LTR only and the RTL fixture had no duplicates, so
  RTL at 768/820/1024/1280 and the duplicate alert under RTL were unverified.
  Fix (test file only): the RTL block now uses `it.each(widths)` with the same
  width→media mapping, renders `locale: 'ar'` with a synthetic duplicate
  candidate, and asserts the band, `dir`/`lang`, Arabic labels/alert, logical
  properties only and inherited wrapping at every width. Re-verified: 23
  targeted tests green.
- **Missing reusable wrapping class (handled in scope, per story step 3).**
  `apps/staff-web/src/theme/responsive.css` gives `min-inline-size: 0` and
  `overflow-wrap: anywhere` to `.app-shell > *`, `.cluster > *`, `.stack > *`,
  `.grid-2 > *` and `.card-list > *`, but **not** to `.page > *`, and there is
  no `.clinical-value` class to opt into. The stylesheet was not edited. The
  page wrapper sets inherited logical `overflow-wrap: anywhere` inline and the
  class gap is recorded under Lessons for workers.
- **Field stacking is owned by the SCREEN task.** `PatientOverviewScreen.tsx`
  renders the identity fields in a single inline grid at every width; making
  them side-by-side on wider screens belongs to `RESP-PATIENT-OVERVIEW-SCREEN`,
  outside this task's scope. The page-level single-column `page` grid and the
  presence of every clinical field at all widths are asserted here.
- **Pixel checks are intentionally absent.** jsdom cannot lay out pixels or
  evaluate real media queries; 44 × 44 targets, 200 %/400 % zoom and overlap
  are left to the Playwright projects in `docs/design/responsive-layout.md` §13.
- **Pre-existing, out-of-scope committed conflict markers.** `git grep -nE
  '^(<<<<<<<|>>>>>>>) '` found markers in
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java`; `git show HEAD:…/Hl7Parser.java`
  confirms they are already committed at HEAD, not introduced here. They are
  outside the declared scope and were left untouched for the integrator/owner.
- **Environment-only gate failures** are recorded with commands and final lines
  in Test evidence items 7 and 8 (OPS-CW-003-01 and the Python fixture/profile
  limits); neither is caused by this change and no protected file was edited.

## Lessons for workers

- When asserting a page wrapper in jsdom, find it via the labelled region's
  parent (or a stable data attribute) rather than a case-sensitive
  `getByRole` name regex; `Patient overview` did not match
  `/patient overview|…/` and produced a misleading initial red.
- `responsive.css` has no `.page > *` wrapping rule and no `.clinical-value`
  class. Use inherited logical `overflow-wrap: anywhere` on the page wrapper
  (as here) instead of editing the stylesheet; report the missing class.
- `ApiPage` already renders its own `section.page` with `data-breakpoint`. Do
  not add `.page` to a page wrapper: two nested `.page` grids double the
  safe-area padding and narrow the 1200 px reading column. Use `.stack` (or a
  plain container) and assert the wrapper is *not* `.page`.
- Parameterize the RTL case over the same width matrix as LTR. Asserting RTL at
  320 px alone misses the medium/expanded rail and the RTL safe-area swap, and
  an RTL fixture with `duplicates: []` never exercises the duplicate alert.
- Adding `useBreakpoint()` to a page means older tests without a `matchMedia`
  stub resolve to `expanded` (the hook's fallback); harmless, but do not read
  that as evidence the breakpoint logic ran.
- Reuse the existing `MatchMedia` stub pattern from
  `tests/components/ApiPage.responsive.test.tsx` and
  `tests/app/OrgSwitcher.responsive.test.tsx`; cover all four queries the
  shared hook and `MixedScriptText` read (`medium`, `expanded`,
  `(pointer: coarse)`, `(orientation: portrait)`).

## Why this is not finished

The scoped implementation and its canonical frontend gate are complete and
green. The only failing repository-level commands are the two documented
worker-environment artifacts above, which are integrator-owned and must not be
"fixed" from a worker:

- `bash scripts/check.sh` → exit 1, `check-pack: opencode.json is stale: run
  scripts/gen-opencode.py`. Cause: OPS-CW-003-01 — `agent-run.sh` installs the
  CI profile over `opencode.json` under `skip-worktree`; `git show
  HEAD:opencode.json` is the correct local profile. Next step: the integrator
  runs the full gate with the committed file in place; no repo edit needed.
- `bash scripts/python-coverage.sh` → exit 1, `Ran 1127 tests … FAILED
  (failures=6, errors=2)` in `test_check_pack` and `test_worker_gate*`. Cause:
  the same skip-worktree profile plus synthetic git/gh fixtures in the worker
  image; no Python file was changed. Next step: rerun in the integrator image
  with PyYAML and the HEAD `opencode.json`.

No task-request block: the missing wrapping class and the field-layout work are
already covered by in-scope inline styling and the separate
`RESP-PATIENT-OVERVIEW-SCREEN`/Playwright tasks respectively.
