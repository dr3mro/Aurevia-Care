## Summary

Made the staff-web `FacilitiesPage` responsive using the existing `responsive.css` utilities (`page`, `stack`, `grid-2`) and the `useBreakpoint` hook, and added a dedicated responsive test that stubs `matchMedia` for the compact, medium and expanded bands. The page is now one logical `.page` grid (single full-width column on phones, centred reading width from medium up), the create section is a `.stack`, and the two facility fields stack on phones and sit side by side (`grid-2`) from 768 px. The table keeps real `table`/`caption`/`th scope` semantics inside the existing labelled, keyboard-scrollable `AdminTableRegion`; no clinical column is hidden. Direction stays with the document and all added presentation uses logical properties. No behaviour, text, API call or exported name changed; no stylesheet, dependency, tracker or file outside the two-path scope was touched.

## Affected requirements

- **WEB-07** — supported staff-web widths 320–1280 px: one logical page column with the shared layout classes; no page-level horizontal scroll; table semantics and the full column set preserved at every band.
- **UX-01** — English/Arabic RTL with logical-only presentation, isolated `<bdi>` runs, preserved `role="status"` announcement, DOM keyboard order, 16 px `field` inputs and coarse-pointer `touch` targets from the shared components; text scales because no fixed block size was added.

Acceptance mapping (story steps):
1. Read the component, its existing test, `docs/design/responsive-layout.md`, `apps/staff-web/src/theme/responsive.css` and `apps/staff-web/src/theme/useBreakpoint.ts`.
2. Satisfied by `.page`/`.stack`/`.grid-2` plus the existing child components' `field`, `touch`, `table-region` and isolated `bdi` behaviour; width matrix 320/360/390/768/820/1024/1280 and both directions asserted in the new test.
3. Used only existing classes and the hook; the stylesheet was not edited. The only inline presentation left on the table (`borderCollapse: collapse`, `inlineSize: '100%'`, caption `textAlign: 'start'`) is logical and non-grid — see Lessons for workers for the missing utility class.
4. Added the new mocked-`matchMedia` test at `apps/staff-web/tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx` (structure per band, labels and roles, keyboard order, RTL with long English and Arabic strings, touch targets, width matrix, live width change). Existing tests still pass.
5. No behaviour, text, API call or exported name changed.

## Risk class

Routine — contained staff-web presentation change with a new component test; no API, database, tenant boundary or authorization change (docs/WORKFLOW.md).

## Review owner

`aurevia-senior-react` (bounded simulated review, advisory) plus the `aurevia-senior-typescript` rules for the strict-TypeScript test. The reviewer returned **Approve, no actionable findings** after reading the two scoped files and the theme/design context; it did not run checks and relied on the tool evidence below. Non-blocking observations: the residual table inline styles are logical so they do not violate the shared-class rule; the `display:none` assertions inspect inline styles only (the component applies no hiding class). Evidence is tool reads plus a simulated reviewer; no human, clinical or patient-participant evidence.

## Data changes

None. No migration, backfill, table, lock or volume impact; no Flyway version reserved or used. All test data is synthetic (`alphaOrganizationId`, a synthetic `MAIN` facility with `Synthetic Main Facility` / `UTC`, and the long synthetic English and Arabic strings). No patient data, credentials or raw logs.

## Authorization boundaries and audit events

None added or changed. The component remains tenant-scoped through `useActiveOrganization()` and the unchanged `listFacilities`/`createFacility` calls with the `X-Active-Organization` header; the validation, error-summary and reload behaviour is untouched. No audit event emitted.

## Migration and rollout considerations

None — presentation-only. Pixel-level focus-ring, reflow and virtual-keyboard claims remain with the Playwright tasks (RESP-E2E-*); jsdom asserts classes, attributes, roles and structure only.

## Test evidence

Entry point to test trace: `src/features/admin/FacilitiesPage.tsx` (`FacilitiesPage` → `FacilitiesContent` → `FacilitiesList` → `ApiPage` → `AdminTableRegion` → `<table>`) → `tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx` (renders `FacilitiesPage` under `ThemeProvider` + `I18nProvider` + `ActiveOrganizationProvider`, stubs the transport `send` with a synthetic first page and `matchMedia` for compact/medium/expanded). The unchanged `src/features/admin/FacilitiesPage.test.tsx` still covers behaviour, commands, paging and tenant switching.

Red, before the component change (new test only):

```
cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx --exclude 'e2e/**'
Test Files  1 failed (1)
Tests       16 failed (16)
```

Green after the change (new test only):

```
cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx --exclude 'e2e/**'
Test Files  1 passed (1)
Tests       16 passed (16)
```

New test plus existing FacilitiesPage test:

```
cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx src/features/admin/FacilitiesPage.test.tsx --exclude 'e2e/**'
Test Files  2 passed (2)
Tests       38 passed (38)
```

Mandatory frontend gate (exact command and final lines):

```
cd apps/staff-web && bash check.sh
 Test Files  159 passed (159)
      Tests  1907 passed (1907)
   Start at  09:01:08
   Duration  86.83s (environment 43%, tests 31%, setup 18%, worker 3%, transform 3%, import 2%)
 No files with missing coverage.
 207 files fully covered.
 4 empty files skipped.
Statements   : 100% ( 3721/3721 )
Branches     : 100% ( 1894/1894 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3555/3555 )
```

`gen:api:check`, strict typecheck, ESLint, the production build and the WEB-08 bundle-size gate all passed inside `check.sh` (the script uses `set -euo pipefail`).

Typecheck final lines (explicitly re-run and pasted as required):

```
cd apps/staff-web && npm run typecheck
> aurevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
typecheck exit: 0
```

Lint final lines:

```
cd apps/staff-web && npm run lint
> aurevia-staff-web@0.1.0 lint
> eslint . --max-warnings 0
```

`git merge --no-edit origin/main` was run at the start and again before finishing, both "Already up to date"; no conflict markers were introduced in either scoped file. The `Uncaught [Error: useTheme must be used inside ThemeProvider]` lines in the full Vitest log come from the existing intentional negative tests in `tests/theme/ThemeProvider.test.tsx`, not from failures. Evidence source: executed tool commands and reads; no reviewer, simulated user or human participant ran the runtime checks.

## Coverage

Command and result: `cd apps/staff-web && bash check.sh` → `Statements 100% (3721/3721)`, `Branches 100% (1894/1894)`, `Functions 100% (1258/1258)`, `Lines 100% (3555/3555)`, 207 files fully covered, `No files with missing coverage.` Per-file thresholds in `vitest.config.ts` were not changed; no exclusion, ignore comment or skipped test was added.

New or changed production lines and branches in `FacilitiesPage.tsx` (the only production file touched):

- `import { useBreakpoint }` / `const breakpoint = useBreakpoint();` in both `FacilitiesContent` and `FacilitiesList` — no branch in this component; exercised at all three bands by `renders one logical page with every facilities section at %s`.
- Outer `<div className="page" data-breakpoint={breakpoint}>` replaced the raw inline grid style — asserted for class, `data-breakpoint`, absence of a `style` attribute and every section at compact/medium/expanded, and after a live width change.
- `<section className="stack">` replaced the raw inline grid style — asserted for class, no `style` attribute and the create-facility `aria-label` at all three bands.
- **`breakpoint === 'compact' ? 'stack' : 'grid-2'`** on the fields wrapper — the only new production branch. Compact → `stack` is asserted by `renders one logical page with every facilities section at compact`; medium and expanded → `grid-2` are asserted by the same test at those bands, and medium again by `follows a width change without losing the page wrapper or the rendered data`.
- `<table data-breakpoint={breakpoint}>` replaced `width: '100%'` with logical `inlineSize: '100%'` and dropped the token-only caption padding — `data-breakpoint` is asserted at all three bands and the caption/`th scope`/column set are asserted in the same test; no branch added.
- `useTheme` and its `tokens` usage were deleted from this file because the inline grids were removed; no replacement branch was introduced.

No branch in the touched production file is uncovered. The 100% report covers the whole module, including `FacilitiesPage.tsx`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or critical-result behaviour is present or changed. No clinical reviewer or human participant was involved; this is simulated engineering evidence only.

## Findings

- **Pre-existing, out-of-scope defect observed at HEAD (`1a4ac855`)**: literal conflict markers are committed in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java` (for example `git show HEAD:.../Hl7Parser.java | grep -nE '^(<<<<<<<|>>>>>>>) '` returns lines 14 and 18). They were present before this task, are unrelated to staff-web, and were not touched; recorded here so the integrator can route them (likely to BUGS.md / the owning BUILD-37 worker). `git status` for this task shows only the two in-scope paths.
- The three labelled table regions/child components (`AdminTableRegion`, `Form`, `FormField`, `MixedScriptText`) are owned by their own RESP stories and were not modified; their shared classes are asserted here through the rendered output.
- No missing layout class blocked this story: `.page`, `.stack` and `.grid-2` all exist in `responsive.css`, so no side-aware inline style was needed for layout.

## Lessons for workers

- Copy the `AdminScreen.responsive.test.tsx` pattern: one shared `matchMedia` state map per query with `vi.stubGlobal`, `vi.unstubAllGlobals()` in `afterEach`, and a `media.fire(query, true)` to prove the breakpoint hook stays live across a width change.
- `FacilitiesPage` has two regions named "Facilities" (the `ApiPage` section and the `AdminTableRegion` scroll region). Query `getAllByRole('region', …)` and pick the one with `tabindex="0"` (or filter by role/tabindex) instead of `getByRole`, which throws on multiple matches.
- The table is populated asynchronously through `useApi`, so `await screen.findByRole('table', …)` before asserting columns; synchronous `getByRole` sees the Loading state and fails.
- `responsive.css` has no utility for a full-width table's `border-collapse: collapse` + `inline-size: 100%` or a caption's `text-align: start`. Per the story rule the stylesheet was not edited; logical inline styles were used instead. A future `.data-table` / `.table-full` utility would remove those inline styles — this is the one missing class worth recording.
- Reuse the existing `data-breakpoint` attribute convention on page wrappers so jsdom tests can assert the band without painting pixels; keep pixel, focus-ring and virtual-keyboard claims in the Playwright E2E stories.
