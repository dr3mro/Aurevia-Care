# RESP-LONGITUDINAL-CHART — Staff web: responsive `LongitudinalChart` (features-chart)

## Summary

Made the staff-web `LongitudinalChart` responsive from 320 px upward without
changing its text, API calls, behaviour or exported names. The component now
reads `useBreakpoint()` once and passes the band to two small presentational
helpers. On compact viewports each coded problems/allergies/medications table is
wrapped in the existing `.table-region` class as a labelled, keyboard-scrollable
region, so every clinical column stays reachable with no page-level horizontal
scroll. The vitals timeline becomes a vertical single-column `.card-list` on
phones, with each measurement date in a block-level `<time>` on its own line;
tablets and desktops keep the full table with all three columns. Every clinical
value uses logical wrapping inline styles so long English and Arabic strings
wrap at 200 percent zoom. The compact cards are a description list whose
visually hidden `<dt>` labels keep the `kind`, `measurement` and `measuredAt`
column names in the accessibility tree. Only the two scoped files changed; no
stylesheet, dependency, migration or tracker was edited.

## Affected requirements

- **WEB-07** — staff web usable from 320 px: labelled scroll regions on phones,
  full tables from 768 px, no horizontal page scroll, no clinical column hidden.
- **UX-01** — logical properties only, keyboard-reachable labelled regions,
  native table semantics retained, long English and Arabic values wrap, no
  fixed block size on clinical values, labels preserved in the compact card view.

Specification: `PROJECT_V1_SPEC.md` WEB-07 and UX-01. Design authority:
`docs/decisions/0004-responsive-staff-web.md`; `docs/design/responsive-layout.md`
sections 2, 5, 11–13; `docs/design/accessibility-patterns.md`;
`docs/design/rtl-and-localization.md`.

### Acceptance mapping (story point → implementation → test)

| Story requirement | Implementation | Test in `LongitudinalChart.responsive.test.tsx` |
|---|---|---|
| 320/360/390 → compact, 768/820/1024 → medium, 1280 → expanded, LTR and RTL | `useBreakpoint()`; `data-breakpoint` on the section and wrappers | `installMatchMedia('compact' / 'medium' / 'expanded')`; `it.each(['medium','expanded'])` |
| No horizontal page scroll; labelled keyboard-scrollable region on phones | `.table-region` + `role="region"` + `aria-label` + `tabIndex=0` | compact test asserts class, `tabindex`, `tabIndex`, role name |
| Tablets keep the columns; no clinical field hidden | bare table with all eight coded / three vital `<th scope="col">` from medium | medium/expanded test compares full header arrays and no `display:none` cells |
| Long English and Arabic strings wrap; no clipping at 200% zoom | `WRAPPING_STYLE` (`minInlineSize:0`, `overflowWrap:anywhere`, `whiteSpace:normal`), no fixed block size | compact test checks every cell/header; RTL test checks the long Arabic value cell |
| Timeline single column on phones, dates on their own line | `.card-list` with `data-layout="single-column"`, block `<time>` | compact test asserts list, layout, listitem count and time `display:block` |
| Column names survive the card reflow for screen readers | `<dl>` with visually hidden `<dt>` labels | `compact: keeps the three column names with each vital for screen readers` |
| Keyboard order and labels/roles | only the three regions are tab stops; caption/`<th scope>` native semantics | compact test asserts `[tabindex="0"]` labels in clinical order |
| Right-to-left mirrors without a direction branch | logical CSS only; document direction drives layout | RTL compact test (long Arabic, no physical side/alignment/direction) and RTL medium test |
| Responsive to resize without remount | hook state re-renders same component instance | `drops compact regions and cards when the viewport grows past compact` |
| Existing tests still pass | unchanged exports, text and `expanded` fallback path | `components.test.tsx`, `chart-screen.test.tsx`, `ChartPage.test.tsx`, `chart-core.test.ts` all green |

## Risk class

Routine (contained staff-web presentation change with an accessibility
boundary). One targeted read-only review by the simulated `aurevia-senior-react`
specialist; its two findings were fixed in this change. No clinical or human
review was performed or claimed.

## Review owner

Senior React and Vite (`.opencode` specialist, simulated, session
`ses_dfd30296cffeNwipsa6p2j2NU1`) — static review only; the specialist could not
execute checks in its read-only role and relied on the implementing agent's
recorded commands. Senior TypeScript rules were satisfied by the module
`check.sh` typecheck. No real-browser, clinician, patient or human evidence.

## Data changes

None. No migration, schema, backfill, lock or volume impact. Synthetic fixtures
only (`syntheticAlphaChart`, invented English/Arabic strings); no patient data,
real identifier or credential appears in code, tests or notes.

## Authorization boundaries and audit events

None. The component is read-only presentation with no commands, authorization
checks or audit events; tenant scope is unchanged.

## Migration and rollout considerations

None. Presentation-only change using the existing `useBreakpoint()` hook and
`responsive.css` classes; no feature flag, config or release action introduced.
No rollback beyond reverting the commit.

## Test evidence

Evidence provenance: executed tool commands (Vitest, tsc, ESLint, Vite, bundle
budget, coverage) plus one simulated specialist review. No human or real-browser
evidence is claimed. All commands run from `apps/staff-web` unless stated.
Final logs: `/tmp/opencode/staff-web-check-RESP-LONGITUDINAL-CHART-final.log`.

- Red-first, before implementation:
  `./node_modules/.bin/vitest run tests/responsive/features-chart/LongitudinalChart.responsive.test.tsx --reporter=dot`
  → `Test Files  1 failed (1)`, `Tests  7 failed (7)` (missing region/card/wrapping
  structure).
- Green targeted after implementation:
  `./node_modules/.bin/vitest run tests/responsive/features-chart/LongitudinalChart.responsive.test.tsx src/features/chart/components.test.tsx src/features/chart/chart-screen.test.tsx src/features/chart/ChartPage.test.tsx src/features/chart/chart-core.test.ts --reporter=dot`
  → `Test Files  5 passed (5)`, `Tests  70 passed (70)`.
- Green targeted after the review fixes:
  `./node_modules/.bin/vitest run tests/responsive/features-chart/LongitudinalChart.responsive.test.tsx src/features/chart/components.test.tsx --reporter=dot`
  → `Test Files  2 passed (2)`, `Tests  26 passed (26)`.
- `bash apps/staff-web/check.sh` (repository root) → `CHECK_EXIT=0`, all stages:
  - `gen:api:check` → `gen:api: generated client matches the contract`
  - `npm run typecheck` → final lines:
    ```
    > aurevia-staff-web@0.1.0 typecheck
    > tsc --noEmit -p tsconfig.json
    ```
    No `error TS…` output.
  - `npm run lint` → final lines:
    ```
    > aurevia-staff-web@0.1.0 lint
    > eslint . --max-warnings 0
    ```
  - `npm run build` → `✓ built in 137ms`
  - `node scripts/check-bundle-size.mjs` → every chunk `ok`, ending
    `index-BOx_kSbY.js                                   52516  ok`
  - `npm run test:coverage` →
    ```
     Test Files  159 passed (159)
          Tests  1900 passed (1900)
    No files with missing coverage.
    207 files fully covered.
    ```
- Changes are committed on `swarm/RESP-LONGITUDINAL-CHART-960` as
  "Make LongitudinalChart responsive across staff-web breakpoints"; no push,
  tag or `gh` call was made.
- `git merge --no-edit origin/main` run at the start, after each stage and
  before finishing: `Already up to date.` each time.
- `python3 -I scripts/check-docs.py` (repository root) → exit 0, no output.

### Entry-point-to-test trace

Route `/patients/:id/chart` (`src/features/chart/routes.ts`) → `ChartPage.tsx` →
`ChartScreen.tsx:206` renders `LongitudinalChart` →
`src/features/chart/LongitudinalChart.tsx`. Test entry:
`tests/responsive/features-chart/LongitudinalChart.responsive.test.tsx` renders
that export inside `ThemeProvider` and `I18nProvider` with a deterministic
`matchMedia` stub, then queries the accessibility tree (`getByRole('region' |
'table' | 'columnheader' | 'cell' | 'list' | 'listitem')`), classes, `tabIndex`
and `data-*` attributes. No API call is involved.

## Coverage

Command: `npm run test:coverage` (Vitest v5.1.1, v8 provider, `perFile: true`,
build-failing 100% thresholds) via `bash apps/staff-web/check.sh`.
Result: `Statements : 100% ( 3736/3736 )`, `Branches : 100% ( 1898/1898 )`,
`Functions : 100% ( 1260/1260 )`, `Lines : 100% ( 3570/3570 )`;
`No files with missing coverage. 207 files fully covered.` The touched
production file `src/features/chart/LongitudinalChart.tsx` has no missed line or
branch. No threshold, exclusion, skip or ignore comment was added or changed.

New or changed branch points and the test that takes each side:

| Branch | True side | False side |
|---|---|---|
| `CodedEntryTable` `breakpoint === 'compact'` (scroll region vs bare table) | `compact: wraps coded tables…`, compact empty state, RTL compact | `medium`/`expanded` `it.each`, RTL medium, existing `components.test.tsx` (no `matchMedia` → `expanded` fallback) |
| `VitalTimeline` `breakpoint !== 'compact'` (table vs cards) | `medium`/`expanded` `it.each`, RTL medium | compact tests (`stacks the timeline`, labels, empty state, RTL compact) |
| `entries.length === 0` (coded empty state) | `compact: keeps a defined empty state…`; existing `components.test.tsx` empty test | all populated-table tests |
| `vitals.length === 0` in the table path | existing `components.test.tsx` empty test (default `expanded`) | `medium`/`expanded` `it.each` |
| `vitals.length === 0` in the card path | `compact: keeps a defined empty state…` | `compact: wraps coded tables…` and compact label test |
| `locale === 'ar' && entry.displayLocal` (pre-existing ternary, still covered) | RTL compact and existing Arabic test | English tests and Arabic entries without a local display |
| Media-query change listener updates layout | `drops compact regions and cards…` (fires medium query inside `act`) | initial compact render in the same test |

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical-result behaviour is touched. Accessibility structure was
checked in jsdom; painted pixel layout, real coarse-pointer sizing and 200/400
percent zoom remain real-browser Playwright and named accessibility gates per
`docs/design/responsive-layout.md` section 13.

## Findings

- **Reviewer F1 (fixed):** the first compact card implementation dropped the
  `kind` / `measurement` / `measuredAt` column names from the accessibility tree.
  Fixed with a description list per card using visually hidden `<dt>` labels built
  from the existing `chartText` strings; a named test now asserts all three labels
  exist for every vital.
- **Reviewer low-impact gap (fixed):** the medium band had no RTL test. Added
  `medium: keeps every column and logical-only styles under right-to-left`.
- **Design interpretation, no code change:** "columns stack on phones" is met by
  the vitals timeline cards; the coded problems/allergies/medications tables stay
  labelled inline-scroll regions on phones, which is the DataTable pattern in
  `docs/design/responsive-layout.md` section 5 and keeps every column reachable
  without hiding data. A product-owner decision would be needed to convert those
  clinical tables into cards instead.
- **N/A at this scope:** there are no buttons, inputs or links in this component,
  so the 44×44 coarse-pointer target, 16 px input text and safe-area bullets are
  owned by the shell and the `.touch` / `.field` rules in `responsive.css`, not by
  local code. No fixed block size is placed on a clinical value.
- **Pre-existing, outside scope, not touched:** `git grep -nE '^(<<<<<<<|>>>>>>>) '`
  finds six committed conflict markers in three `apps/api/.../exchange/hl7/`
  files. They predate this change and are outside the declared scope; `git merge
  --no-edit origin/main` reported `Already up to date.` and introduced none.
- **Environment:** `apps/staff-web/node_modules` was absent; `npm ci --no-audit
  --no-fund` was run once before the baseline, after which `gen:api:check` and
  the full gate were green. No dependency or lockfile was changed.
- No production defect was found by the simulated Senior React reviewer beyond
  the two items above; no patient data or credential was observed.

## Lessons for workers

- A `matchMedia` stub must return one stable state object per query so the hook's
  initializer and effect share listeners, and media-change fires must be wrapped
  in `act()` or React will not flush the re-render before the assertion.
- jsdom may leave `style.minInlineSize` empty even when React emitted
  `min-inline-size: 0px`; assert on the style attribute text instead of relying
  on that camel-case property.
- Symmetric shorthands (`padding: 4px`, `border: 1px solid …`, `margin: 0`) are
  direction-neutral. When checking logical properties, assert only against
  physical side declarations and `text-align: left|right`, and allow `start`.
- `responsive.css` has no `.clinical-value` class although the design guide shows
  one; use the three logical inline wrapping declarations and record the missing
  class rather than editing the stylesheet from a narrow story scope.
- A responsive switch from table to cards changes the accessibility tree. Keep
  the column names with a `<dl>` and visually hidden `<dt>` labels so a screen
  reader still identifies each clinical value, and add a named test for it.
- Run `bash apps/staff-web/check.sh` once at the end: it includes the typecheck,
  and a `error TS…` in a test file still makes the task unfinished.
- Write notes only to this file; it is the hand-off and pull-request description
  and is committed with the change.
