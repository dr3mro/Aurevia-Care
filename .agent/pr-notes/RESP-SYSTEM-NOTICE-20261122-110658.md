# RESP-SYSTEM-NOTICE — Staff web: responsive `SystemNotice`

## Summary

Made the shared, data-free `SystemNotice` surface responsive from 320 px upward
in both directions without changing its text, props, behaviour or exports. The
section now carries the `page` and `stack` utilities from `responsive.css` and
exposes the resolved layout through `data-breakpoint` via the existing
`useBreakpoint` hook. Logical inline styles keep long English and Arabic strings
wrappable with no physical side properties. Added a new responsive test file that
mocks `matchMedia` for compact, medium and expanded and asserts structure,
labels/roles, keyboard order and the RTL case.

Files changed (only the task scope):
- `apps/staff-web/src/features/system/SystemNotice.tsx`
- `apps/staff-web/tests/responsive/features-system/SystemNotice.responsive.test.tsx`

## Affected requirements

- WEB-07 — supported clients / compact layout; responsive staff web down to 320 px.
- UX-01 — English and Arabic, RTL, WCAG 2.2 AA (wrapping, logical properties, 44 px touch targets via the shared `touch` class, no hidden fields).

Design contract followed: `docs/design/responsive-layout.md`, decision `0004`,
`apps/staff-web/src/theme/responsive.css`, `apps/staff-web/src/theme/useBreakpoint.ts`.

## Risk class

Routine — contained presentational component plus one new test file. No clinical
data, no persistence, no authorization, no API or contract change.

## Review owner

Senior React and Vite (`aurevia-senior-react`) and Senior TypeScript
(`aurevia-senior-typescript`) skills were loaded and their mandatory
definition of done applied (`apps/staff-web/check.sh` incl. `tsc --noEmit`, no
`any`/`@ts-ignore`). `aurevia-delivery` skill loaded for the swarm loop.
Specialists are advisory in this worker model; checks were executed by the
primary worker, not delegated.

## Data changes

None. No migration, backfill or schema change.

## Authorization boundaries and audit events

None. The component is synthetic/presentational and issues no request.

## Migration and rollout considerations

None.

## Test evidence

Entry point to test: `SystemNotice` is the shared surface rendered by
`NotFoundPage`, `SignedOutPage` and `UnsupportedBrowserPage`. The new test renders
that shared surface directly under `ThemeProvider` + `I18nProvider` with a
deterministic `matchMedia` stand-in and asserts the labelled `<section>` landmark
(`region`), `data-breakpoint`, `page`/`stack` classes, the `<h1>`, `<p>` and single
`Button` (`touch` class), DOM + focus order, the fired action, logical-only inline
styles and Arabic RTL mirroring. Per `docs/design/responsive-layout.md` §13, jsdom
asserts DOM/classes/attributes only; painted-pixel checks belong to the separate
Playwright task `RESP-E2E-RESPONSIVE-LAYOUT`.

Commands run (all from `apps/staff-web` unless noted), with final lines:

- `npm ci --no-audit --no-fund` → `added 486 packages in 6s`.
- Red — `./node_modules/.bin/vitest run --exclude 'e2e/**' tests/responsive/features-system/SystemNotice.responsive.test.tsx`
  → `Test Files 1 failed (1); Tests 8 failed | 1 passed (9)`
  (new assertions on `data-breakpoint`, `page`/`stack` and wrapping styles failed
  against the original component; the action/keyboard regression test passed).
- Green — `./node_modules/.bin/vitest run --exclude 'e2e/**' tests/responsive/features-system/SystemNotice.responsive.test.tsx tests/features/system/systemPages.test.tsx`
  → `Test Files 2 passed (2); Tests 38 passed (38)`.
- Full module gate — `bash apps/staff-web/check.sh` → exit `0`:
  `Test Files 159 passed (159); Tests 1900 passed (1900)`.
  Final lines: `No files with missing coverage. 207 files fully covered.` and
  `Statements 100% (3722/3722); Branches 100% (1892/1892); Functions 100% (1258/1258); Lines 100% (3556/3556)`.
- Typecheck — `npm run typecheck` (`tsc --noEmit -p tsconfig.json`) → exit `0`, no output/`error TS…`.
  Final lines:
  ```
  > aurevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (TYPECHECK EXIT: 0 — clean, no diagnostics.)
- From repo root: `python3 -I scripts/check-names.py`, `python3 -I scripts/check-docs.py`,
  `python3 -I scripts/check-secrets.py` → each exit `0`; `bash scripts/frontend-check.sh`
  → `frontend standards: policy and tool commands passed` (exit `0`).

The console noise during `check.sh` (`Not implemented: navigation`,
`useI18n/useTheme must be used inside …Provider`) is emitted by pre-existing tests
and does not fail the run (all 1900 tests pass); it is unrelated to this change.

## Coverage

Command/result: `bash apps/staff-web/check.sh` (includes `npm run test:coverage`)
→ `Statements 100% (3722/3722)`, `Branches 100% (1892/1892)`, `Functions 100%
(1258/1258)`, `Lines 100% (3556/3556)`, `No files with missing coverage`. Specific
touched file from `coverage/coverage-summary.json` — `src/features/system/SystemNotice.tsx`:
lines `4/4` 100%, functions `1/1` 100%, statements `4/4` 100%, branches `0` (the
component has no branchable code).

New/changed branches and their tests:
- No `if`/ternary/`&&`/`||`/`switch`/`catch` was added to the component, so it has
  zero branches to cover. Layout resolution is delegated to the already-covered
  `useBreakpoint` hook; the three resolved values are asserted through mocked
  `matchMedia` states by:
  - `keeps a labelled, stacked notice with one touch action at the compact/medium/expanded layout` (`data-breakpoint` per band).
  - `uses only logical inline styles and hides no content at the compact/medium/expanded layout`.
  - `mirrors an Arabic RTL notice without a subtree direction override` (all three bands under RTL).
- Long-string wrapping styles (h1/p `overflow-wrap`/`white-space`, no physical
  text-align): `keeps the long English and Arabic strings wrappable in every band`.
- Action handler and keyboard/DOM order: `keeps the single action last in the DOM and focus order and fires it`.

No threshold, exclusion or skip was added; no test was disabled. Existing tests
were not altered.

## Clinical and architecture review

Not required. The surface renders only fixed translated system text, no patient
data, no signed data, no order/prescription and no critical result; there is no
authorization or tenant boundary. Evidence is synthetic/simulated (jsdom), not
clinical or human acceptance; painted-layout and Arabic-clinician review remain
separate launch gates.

## Findings

- `SystemNotice` has no per-breakpoint structural variants: it is one notice with
  a title, detail and a single action, so there is no drawer/rail or cards/table
  choice to make. Responsiveness is entirely utility-class (`page`, `stack`,
  `touch`) and logical-inline-style driven; the new test asserts the same correct
  structure at each band and records the resolved class through `data-breakpoint`.
- No clinical column or field exists here, so `display: none` is not applicable;
  the test asserts the notice carries no `display: none` and keeps its detail text.
- The full repository `scripts/check.sh` was not run here: it calls
  `apps/api/check.sh` → `mvn -q -B verify` (the full Java unit + integration
  suite, >10 min), which this worker task explicitly forbids and which is out of
  scope for a frontend-only change. Its applicable stages were run individually
  (see Test evidence) and are green; the integrator/Delivery owns the full gate.
- No defect was found in the component or the shared stylesheet/hook.
- Observation outside this scope (reported, not changed): `git grep -nE '^(<<<<<<<|>>>>>>>) '`
  finds conflict markers in three committed HL7 Java files
  (`apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java`, `Hl7Segment.java`). Both `HEAD` and `origin/main` contain them
  (introduced by `b6b349f0`); my `git merge --no-edit origin/main` was clean
  (`Already up to date`) and introduced no marker. Left untouched as out of scope
  for this frontend story; the integrator/owner should resolve them separately.

## Lessons for workers

- For a small presentational surface, `page stack` plus `data-breakpoint` from
  `useBreakpoint` is a testable responsive contract that needs no structural
  branch and no new CSS — staying branch-free keeps per-file 100% coverage trivial.
- No class was missing for this story: `page`, `stack` and `touch` all exist in
  `responsive.css`. Logical inline styles were used only for wrapping/text-align
  where no dedicated class exists, the same approach `StateViews` uses for its
  note panel. Do not edit the stylesheet from a feature slice.
- Pre-existing jsdom console errors (`Not implemented: navigation`, provider
  fail-closed tests) do not fail Vitest — do not chase them in a worker pass.
- In this swarm, `scripts/check.sh` drags in the full Maven verify through
  `apps/api/check.sh`; for a frontend-only story run the module `check.sh` plus
  the targeted policy scripts instead and state the omission explicitly.
