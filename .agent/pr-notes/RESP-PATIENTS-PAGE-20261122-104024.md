# RESP-PATIENTS-PAGE — responsive `PatientsPage`

## Summary

Made `apps/staff-web/src/features/patients/PatientsPage.tsx` responsive without changing
behaviour, text, API calls or exported names. The page now uses the shared `page` and
`stack` classes for a logical, safe-area-aware grid, a `stack`/`grid-2` toolbar so the
search form and register action stack on a phone and sit side by side from medium, and a
labelled, keyboard-scrollable `.table-region` around the patient results table on compact
viewports. `useBreakpoint()` drives the layout and is exposed as `data-breakpoint` for
jsdom assertions; `data-part`/`data-layout` hooks make the structure observable without
new CSS. No clinical column or field is hidden, and no stylesheet was edited.

## Affected requirements

- WEB-07 — responsive supported clients down to 320 px; the page resolves compact/medium/expanded
  from the breakpoint queries and stacks its toolbar on a phone.
- UX-01 — WCAG 2.2 AA: logical properties only, 44 px `.touch` targets, 16 px `.field` inputs,
  labelled scroll region for the table, no hidden clinical data, LTR and RTL coverage.

Acceptance mapping: 320/360/390 px → compact (stacked toolbar, labelled table region);
768/820/1024 px → medium (side-by-side toolbar, plain table); 1280 px → expanded; each
case asserted in English and Arabic with roles, labels, keyboard order and no physical
inline styles.

## Risk class

Routine — contained staff-web presentation change plus a new structural test. No domain,
data, authorization or contract boundary changed.

## Review owner

Senior React and Vite (bounded review) and UX reviewer (RTL/accessibility bounded review).
Both were dispatched as simulated reviewers; results recorded under Findings.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

Unchanged. `PatientsPage` still takes the tenant from `useActiveOrganization()` and keys
`PatientsContent` by organization id; search/register calls and headers are untouched. No
audit event added or changed. All fixtures are synthetic (`alphaOrganizationId`,
`syntheticAlphaPatients`); no patient data or credential appears in code, tests or notes.

## Migration and rollout considerations

None. Presentation-only change; no schema or API contract change.

## Test evidence

Observed with executed tools in `apps/staff-web` (Node 26, Vitest 5.1.1):

- `./node_modules/.bin/vitest run tests/responsive/features-patients/PatientsPage.responsive.test.tsx`
  → `Test Files  1 passed (1)` / `Tests  21 passed (21)`.
- `./node_modules/.bin/vitest run src/features/patients/PatientsPage.test.tsx`
  → `Test Files  1 passed (1)` / `Tests  27 passed (27)` (existing behaviour tests still green).
- `bash apps/staff-web/check.sh` → completed: `Test Files  159 passed (159)`,
  `Tests  1912 passed (1912)`, then the coverage summary below.
- `npm run typecheck` → final lines:
  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no `error TS…`, exit 0).
- `npm run lint` → final lines:
  ```
  > orevia-staff-web@0.1.0 lint
  > eslint . --max-warnings=0
  ```
  (no warnings, exit 0).
- `git merge --no-edit origin/main` → `Already up to date.`; conflict-marker check limited to
  tracked files (see Findings).

Entry point to test trace: `/patients` route → `PatientsPage` → tenant-keyed `PatientsContent`
→ `useBreakpoint()` reads the stubbed `matchMedia` → root `[data-part="patients-page"][data-breakpoint]`
→ `[data-part="toolbar"]` class and `data-layout` → `ApiPage` results → `[data-part="results"]`
→ `PatientsScreen` `<table>`. `PatientsPage.responsive.test.tsx` asserts each node at compact,
medium, expanded, at all seven widths and under RTL.

## Coverage

`bash apps/staff-web/check.sh` (Vitest v8, `perFile: true`, thresholds 100/100/100/100):
`Statements 100% (3723/3723)`, `Branches 100% (1898/1898)`,
`Functions 100% (1258/1258)`, `Lines 100% (3557/3557)`, `No files with missing coverage.`
No threshold, exclusion or skip was changed.

New branches in `PatientsPage.tsx` and the test that takes each side:

1. `breakpoint === 'compact' ? 'stack' : 'grid-2'` — compact side: "resolves the band and
   exposes the page grid and toolbar structure" at compact; non-compact side: the same test
   at medium and expanded, plus the RTL expanded test.
2. `breakpoint === 'compact' ? 'stacked' : 'side-by-side'` (`data-layout`) — same three band
   tests assert the attribute value on both sides.
3. `breakpoint === 'compact' ? <div className="table-region" role="region" …> : <div …>` —
   compact side: "keeps the full clinical table and adapts only its scroll treatment" at
   compact and the RTL compact test; non-compact side: the same test at medium and expanded.

The `searchErrorText === undefined` and `registeredName === undefined` branches are
pre-existing and covered by `PatientsPage.test.tsx`. `useBreakpoint()` itself is unchanged.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing
or critical result. Simulated UX review covers RTL and keyboard behaviour; real device/zoom
and Arabic-clinician acceptance remain separate launch gates and are not claimed here.

## Findings

- Pre-existing, unrelated defect observed at HEAD while reconciling `origin/main`: committed
  conflict markers exist in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java` (`git grep -nE '^(<<<<<<<|>>>>>>>) '`). These files are
  outside this task's scope and were not touched; reported for the owning worker/integrator.
- No missing responsive class: every class needed (`page`, `stack`, `grid-2`, `table-region`,
  `touch`, `field`) already exists in `apps/staff-web/src/theme/responsive.css`.
- The results table keeps a single DOM and accessibility tree at every band; only its scroll
  treatment changes, so no clinical column is added, removed or hidden.
- Two simulated specialist reviews ran on the final diff. Senior React: no blocking findings;
  two low test-hardening notes (keyboard-order test omitted the new tab stop; `as never` mock
  cast) — both addressed by asserting the compact region's tab position/focus and by passing
  the synthetic fixture without a blanket cast. UX reviewer: three hardening notes — the
  hidden-data check now includes row headers and rejects `hide-compact`/`only-compact` classes,
  the compact focus-order assertion now includes the scroll region and the first row link, and
  the width matrix now asserts toolbar layout per band as well as the `.touch` submit wrapper.
  The reviewer's pixel/wrapping/zoom items remain real-browser Playwright evidence and are not
  claimed here.

## Lessons for workers

- In this codebase the responsive classes and `useBreakpoint()` are the intended lever for a
  page: assert `data-breakpoint`, `data-part` and class names in jsdom rather than adding CSS
  or trying to measure pixels.
- `getByRole('status')` is ambiguous on this page because `ApiPage`'s loading state also has
  `role="status"`; query the registration status by its `data-testid`.
- A `describe.each` band test that performs a search must actually render the page in its
  helper; an unrendered helper produces a misleading "no accessible roles" red.
