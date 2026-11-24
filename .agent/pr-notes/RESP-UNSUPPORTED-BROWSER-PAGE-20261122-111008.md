# RESP-UNSUPPORTED-BROWSER-PAGE — Staff web: responsive `UnsupportedBrowserPage`

## Summary

Made the standalone unsupported-browser page responsive at every supported width
(320–1280 px) without changing its behaviour, text, action or exported names. The
page now renders inside the shared `page` utility (safe-area-aware logical
padding, single column, centred reading measure) and exposes its layout band via
`data-breakpoint` from `useBreakpoint()`. Logical inline styles centre the notice
and keep long English and Arabic text wrappable; the existing shared `Button`
continues to provide the 24/44 px touch floors through its `touch` class. No new
CSS rule was added and the shared stylesheet was not edited.

Added a new responsive test file that mocks `matchMedia` for compact, medium and
expanded, and asserts structure, labels/roles, keyboard order and the RTL case.

Scope (only these two files changed):
- `apps/staff-web/src/features/system/UnsupportedBrowserPage.tsx`
- `apps/staff-web/tests/responsive/features-system/UnsupportedBrowserPage.responsive.test.tsx`

Evidence source: executed tool commands (local Vitest, `bash apps/staff-web/check.sh`,
`npm run typecheck`, `bash scripts/frontend-typecheck-check.sh`, individual policy
stages). No reviewer, simulated user or human-participant evidence was used. All
fixtures are synthetic; no patient data or credentials appear in code or notes.

## Affected requirements

- WEB-07 — unsupported browsers still see a plain message with no data; the
  message must be legible and usable across the supported responsive bands.
- UX-01 — logical properties, wrapping long English/Arabic text, coarse-pointer
  target floors, safe-area insets and 200%-zoom readability.

Design contract followed: `docs/design/responsive-layout.md`,
`docs/decisions/0004-responsive-staff-web.md`, `docs/design/rtl-and-localization.md`,
`apps/staff-web/src/theme/responsive.css` and `apps/staff-web/src/theme/useBreakpoint.ts`.

## Risk class

Routine (docs/WORKFLOW.md). The change is a contained presentation-layer update to
one data-free page plus a new jsdom structural test. No clinical data, no
authorization, no state machine, no API and no shared component changed.

## Review owner

Senior React and Vite (`aurevia-senior-react`) with Senior TypeScript
(`aurevia-senior-typescript`) for the strict-typecheck gate. Both skills were
loaded. No cross-boundary or safety-critical review required.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. The unsupported page renders before the application mounts and shows no
data, navigation or shell chrome. No tenant, role or patient-link scope and no
audit event was added or changed. Existing behaviour verified: the primary action
still performs a document load (`assignHomeLocation`) rather than SPA navigation.

## Migration and rollout considerations

None. Presentation-only change to an existing page; no contract, generated
client or deployment topology change.

## Test evidence

Red first (new test against the unchanged component):
- `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-system/UnsupportedBrowserPage.responsive.test.tsx`
  — `Test Files 1 failed (1)`, `Tests 7 failed | 2 passed (9)`; failures were
  `Error: UnsupportedBrowserPage has no responsive page wrapper`.

Green (after the component change):
- `node_modules/.bin/vitest run tests/responsive/features-system/UnsupportedBrowserPage.responsive.test.tsx`
  — final lines: `Test Files 1 passed (1)`, `Tests 9 passed (9)`.
- `node_modules/.bin/vitest run tests/features/system/systemPages.test.tsx tests/main.test.tsx`
  — final lines: `Test Files 2 passed (2)`, `Tests 37 passed (37)` (existing tests
  still pass; jsdom logs the pre-existing "Not implemented: navigation" notice
  when the Go home action calls `location.assign`).

Mandatory frontend gate:
- `bash apps/staff-web/check.sh` — final lines:
  `Test Files 159 passed (159)`, `Tests 1900 passed (1900)`,
  `No files with missing coverage. 207 files fully covered.`,
  `Statements 100% (3723/3723)`, `Branches 100% (1892/1892)`,
  `Functions 100% (1258/1258)`, `Lines 100% (3557/3557)`.
  (This single command also ran `gen:api:check`, `typecheck`, `lint`, `build` and
  the WEB-08 bundle-size gate before the coverage run; all passed.)

Typecheck final lines (paste as required by the frontend definition of done):
- `npm run typecheck` →
  `> aurevia-staff-web@0.1.0 typecheck`
  `> tsc --noEmit -p tsconfig.json`
  (no `error TS…` output; exit 0).
- `bash scripts/frontend-typecheck-check.sh` →
  `> aurevia-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json`
  then `> aurevia-staff-web@0.1.0 lint` / `> eslint . --max-warnings 0`,
  final line `frontend typecheck: passed (or no frontend files changed)` (exit 0).
- `npm run gen:api:check` → final line `gen:api: generated client matches the contract`.

Entry point → test trace: `src/main.tsx` `renderEntry()` renders
`<ThemeProvider><I18nProvider><UnsupportedBrowserPage/></I18nProvider></ThemeProvider>`
when `isSupportedBrowser(...)` is false. The new test renders that same provider
tree with `matchMedia` stubbed per band and asserts `div.page[data-breakpoint]`,
the English/Arabic heading and detail, the single "Go home"/"الصفحة الرئيسية"
button, keyboard focus order and the RTL document direction. Pixel-level reflow
checks are intentionally left to the Playwright tasks (jsdom cannot lay out px).

Repository gate `scripts/check.sh` (run once, captured):
- `bash scripts/check.sh` → exit 1 on its first policy step with the final line
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker false positive (OPS-CW-003-01), confirmed once:
  `git ls-files -v opencode.json` → `S opencode.json` (skip-worktree) and
  `cmp -s opencode.json .github/opencode.ci.json` → worktree file equals the CI
  runtime profile installed by `agent-run.sh`. The committed `git show HEAD:opencode.json`
  profile was not edited or regenerated (protected file). The remaining stages
  were run individually:
  - `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`,
    `check-wiring.py`, `check-migrations.py --require-baseline` — all exit 0.
  - `bash scripts/frontend-check.sh` — exit 0 (`frontend standards: policy and tool commands passed`).
  - `bash scripts/python-coverage.sh` — exit 1: 6 failures all in `test_check_pack`
    (the same `opencode.json is stale` skip-worktree artifact) and 2 errors in
    `test_worker_gate`/`test_worker_gate_coverage`
    (`FileNotFoundError: ... 'apps/api/target/surefire-reports'`, no Java build in
    this frontend worker). Ran 1127 tests. Both categories are documented
    worker-image limits, unrelated to this staff-web change; the integrator owns
    the full gate.
  - `apps/api/check.sh` and `db/check.sh` were not run: they execute the full Java
    verify / integration suite, which is the integrator's and Delivery's job and
    exceeds this worker's time budget.

## Coverage

- Command: `bash apps/staff-web/check.sh` (Vitest v8 coverage, per-file thresholds
  100/100/100/100). Result: `No files with missing coverage. 207 files fully
  covered.` — Statements 100% (3723/3723), Branches 100% (1892/1892), Functions
  100% (1258/1258), Lines 100% (3557/3557).
- Changed file verified explicitly from `apps/staff-web/coverage/coverage-summary.json`:
  `src/features/system/UnsupportedBrowserPage.tsx` — lines 5/5 (100%), functions
  3/3 (100%), statements 5/5 (100%), branches 0/0 (100%).

New/changed production branches and their tests:
- No new production branches were introduced in `UnsupportedBrowserPage.tsx`
  (v8 reports 0 branches for the file). The component uses `useBreakpoint()`
  without a conditional layout switch, so there is no uncovered side to test.
- The three values surfaced by `data-breakpoint` (`compact`, `medium`, `expanded`)
  are each exercised by the parameterized test
  `keeps one centred page column and a single action at %s`; the logical-property
  and wrapping assertions are covered by
  `wraps long English and Arabic text with logical properties at %s` for all three
  bands; the Arabic RTL rendering is covered by
  `mirrors the Arabic notice under right-to-left without a direction override`;
  the keyboard order by `preserves the single-action keyboard order at every
  breakpoint`; the unchanged document-load action by
  `keeps the document-load action while adding responsive layout` and by the
  pre-existing `tests/features/system/systemPages.test.tsx` case.
- No threshold, exclusion, skip or ignore was added; no test was weakened.

## Clinical and architecture review

Not required. The page contains no clinical data, signing, prescribing, critical
results, patient association or cross-organization sharing. No architecture
boundary, contract or state machine changed.

## Findings

- Pre-existing (out of scope, in HEAD at `aff9f6e6`): committed conflict markers
  in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java` (from `origin/swarm/BUILD-37-469`). They
  were present before this change, are outside this task's scope, and were left
  untouched. `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/staff-web` is clean.
- jsdom logs a harmless `Not implemented: navigation (except hash changes)` when
  the Go home action calls `window.location.assign` in the existing system-pages
  test; this is pre-existing and does not fail the suite.
- `scripts/check.sh` and `scripts/python-coverage.sh` red results are the
  documented skip-worktree CI-profile and missing-surefire-reports worker-image
  limits, not defects of this change (see Test evidence). No protected file was
  edited to work around them.

## Lessons for workers

- For a data-free page, the existing `div.page` utility plus `useBreakpoint()` and
  logical inline styles are enough to satisfy WEB-07/UX-01 in jsdom; do not add a
  new class to `responsive.css` (out of scope) — record a missing class here
  instead. No class was missing for this page.
- `useTheme()` is available on this page because both render sites (`main.tsx`
  and the tests) wrap it in `ThemeProvider`; reuse `tokens.background`,
  `tokens.foreground`, `tokens.border` and `tokens.spacing.lg` as `StateViews`
  does, and keep every inline property logical (`textAlign: 'start'`,
  `overflowWrap: 'anywhere'`, `minInlineSize: 0`).
- jsdom cannot evaluate media queries or paint pixels: mock `matchMedia` for the
  two `min-width` queries from `BREAKPOINT_MEDIA_QUERIES`, assert
  `data-breakpoint`/classes/roles/attributes, and leave pixel assertions to the
  Playwright tasks.
- In a cloud worker, an `opencode.json is stale` failure is the CI profile under
  `skip-worktree`; confirm with `git ls-files -v opencode.json` and
  `cmp` against `.github/opencode.ci.json`, then run the remaining gate stages
  individually instead of regenerating the protected file.
- `bash apps/staff-web/check.sh` is the single mandatory frontend gate: it runs
  typecheck, lint, build, bundle budget and 100%-coverage in one command. Paste
  the `tsc --noEmit` final lines into the notes.
