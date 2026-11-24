# RESP-SCHEDULING-PAGE — responsive staff-web `SchedulingPage`

## Summary

The API-backed scheduling page now uses the shared responsive page utility instead of a local inline grid. Its root renders with `className="page"`, `data-scheduling-page`, `data-breakpoint` from `useBreakpoint()` and one logical inline override (`maxInlineSize: 'none'`) so the worklist table keeps the full inline width. No data, text, API call, command behaviour or exported name changed; no dependency, stylesheet, tracker or workflow file was touched. The new jsdom contract test covers compact/medium/expanded, the seven requested widths, both directions, roles/labels/DOM keyboard order and the rejected-command banner.

Evidence provenance: **tool** (commands I executed) for all test/coverage results; **simulated reviewer** (bounded `orevia-senior-react` plan and `orevia-ux-reviewer` review) for design feedback. No human, clinical or patient-participant evidence. All fixtures are synthetic (`apt-001`, `patient-001`, a long synthetic English name and a long synthetic Arabic name); no patient datum or credential appears in code, tests or notes.

## Affected requirements

| Requirement | Acceptance item | How it is met / evidence |
|---|---|---|
| WEB-07 | Compact layout from 320 px, no horizontal page scroll, no hidden clinical column | `.page` supplies a `minmax(0, 1fr)` single-column grid, centred reading width and logical safe-area padding; `maxInlineSize: 'none'` lets the worklist use all inline space. Test asserts `data-breakpoint`, `.page`, all six `columnheader`s, no `display:none` at every band and the 320/360/390/768/820/1024/1280 matrix. Painted overflow is a Playwright task (see Findings). |
| WEB-07 | Coarse pointers get 44×44 targets; inputs use 16 px text | All page-owned buttons keep `.touch` with no inline `min-*` override; the five booking inputs keep `.field touch` with no inline `font-size`. Asserted in the per-band keyboard-order test. The worklist's assign `<select>` is sibling-owned (Findings N2). |
| UX-01 | English and Arabic, RTL, logical properties, long strings wrap, safe-area insets | Root uses logical `.page` padding; no component sets `direction`; `<bdi dir="auto">` isolates the clinical value. Tests assert logical-only inline styles, `overflow-wrap: anywhere`, `white-space: normal`, `dir=rtl`/`lang=ar` and long English/Arabic row labels at compact and expanded. |
| UX-01 | 200 % zoom remains readable | No fixed block size or font size was added; wrapping is delegated to existing child components. jsdom cannot measure zoom — left to the Playwright reflow/zoom tasks (responsive-layout.md §13). |

`docs/decisions/0004-responsive-staff-web.md` remains the authority for "fully usable from 320 px"; the spec's WEB-07 text still names 768 px and needs a person to amend it (documented in `docs/design/responsive-layout.md` lines 16–23). No spec text was changed here.

## Risk class

Routine (contained staff-web presentation change with one targeted implementation plan and one independent UX review). `docs/WORKFLOW.md` cross-boundary clinical/security reviews are not triggered: no authorization, data, migration or clinical decision changed.

## Review owner

Senior React/Vite specialist (bounded implementation plan, simulated) and UX reviewer (independent, simulated). Both were advisory only; all edits are mine. No clinical sign-off is required for presentation-only work.

## Data changes

None. No migration, backfill, schema, lock or volume impact. Synthetic fixtures only.

## Authorization boundaries and audit events

None changed. Tenant scope still comes from `useActiveOrganization()` and the existing `X-Active-Organization` request header; the page continues to call `listAppointments`, `book` and `checkIn` through the existing API module. No audit event added.

## Migration and rollout considerations

None. The change is a single component's presentation wrapper and is safe to roll back with the commit. Feature flags, progressive rollout and environment policy are unchanged.

## Test evidence

Entry point → test trace: route `/schedule` lazily loads `SchedulingPage` (`src/features/scheduling/routes.ts`) → `useApi`/`listAppointments` through `send` (mocked with a synthetic `AppointmentPageResponse`) → `SchedulingScreen` → `AppointmentWorklist` + `BookingForm`. The new test mounts that real component tree through `ThemeProvider → I18nProvider → ActiveOrganizationProvider` with a deterministic `matchMedia` stand-in and asserts from the rendered DOM, so the page root, roles and command-error path are reached through a normal entry point.

Commands run (final lines quoted):

- `npm ci --no-audit --no-fund` → `added 486 packages in 4s` (module dependencies were absent in the worker; no lockfile change).
- RED — `cd apps/staff-web && npx vitest run tests/responsive/features-scheduling/SchedulingPage.responsive.test.tsx` → `Test Files 1 failed (1)`, `Tests 20 failed (20)`; every failure was `Error: responsive scheduling page wrapper was not rendered`. The failure was caused by the missing responsive wrapper, not by a fixture problem.
- GREEN, focused — `cd apps/staff-web && npx vitest run tests/responsive/features-scheduling/SchedulingPage.responsive.test.tsx src/features/scheduling/SchedulingPage.test.tsx` → `Test Files 2 passed (2)`, `Tests 44 passed (44)`.
- Typecheck, first pass → `tests/responsive/features-scheduling/SchedulingPage.responsive.test.tsx(118,18): error TS6133: 'patientId' is declared but its value is never read.` Fixed by removing the unused parameter (no production change).
- Typecheck, final — `cd apps/staff-web && npm run typecheck` final lines:
  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  No diagnostics; `TYPECHECK_EXIT=0`.
- `cd apps/staff-web && bash check.sh` → `Test Files 159 passed (159)`, `Tests 1912 passed (1912)`, `CHECK_EXIT=0`. The stack traces in the log come from existing provider-guard tests (`I18nProvider.test.tsx`, `ThemeProvider.test.tsx`) that deliberately render outside their providers; all tests passed.
- `bash scripts/frontend-check.sh` → root `Test Files 1 passed (1)`, `Tests 1 passed (1)`, final line `frontend standards: policy and tool commands passed`, `FRONTEND_CHECK_EXIT=0`.
- `python3 -I scripts/check-names.py` → `NAMES_OK`; `python3 -I scripts/check-secrets.py` → `SECRETS_EXIT=0`; `python3 -I scripts/check-docs.py` → `DOCS_EXIT=0`.
- `cd apps/staff-web && npx prettier --check src/features/scheduling/SchedulingPage.tsx tests/responsive/features-scheduling/SchedulingPage.responsive.test.tsx` → `All matched files use Prettier code style!`, `PRETTIER_EXIT=0` (after `npx prettier --write` on those two scoped files).
- `git merge --no-edit origin/main` at start, after implementation and before finish → `Already up to date` each time. `git status --short` shows only the two scoped paths.

`scripts/check.sh` was intentionally **not** run: it invokes `apps/api/check.sh` (full `mvn verify` with all integration tests and OWASP), which the worker prompt forbids. The module gate plus `scripts/frontend-check.sh` cover this frontend change; the integrator owns the full repo gate.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (step `npm run test:coverage`, Vitest v8 coverage, `thresholds.perFile: true`, 100 % lines/branches/functions/statements). Result:

```
No files with missing coverage.
207 files fully covered.
4 empty files skipped.
Statements   : 100% ( 3721/3721 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3555/3555 )
```

New/changed production branches in `SchedulingPage.tsx`: **none**. `useBreakpoint()` and the three JSX attributes are branchless; the change removes the `useTheme()` call and the inline `display/grid/gap` style. The pre-existing four-way state ternary, `commandError` guard, `nextCursor` guard and command `try/catch` paths remain exercised by the unchanged `SchedulingPage.test.tsx` (26 tests) and by the new conflict-banner test at every band. Test files are excluded from coverage by `apps/staff-web/vite.config.ts`. No threshold, exclusion, skip or `@ts-ignore` was added or changed.

## Clinical and architecture review

Not required: no signed record, cross-organization sharing, patient association, prescribing or critical-result behaviour changed. The simulated UX review confirmed no new accessible-name, focus-order or RTL defect: `className`/`data-*` on a `div` are ARIA-neutral and DOM order is unchanged.

## Findings

- **Resolved — worklist width.** Simulated UX review F2: `.page` caps inline size at 1200 px, which would narrow a six-column worklist at very large viewports. Fixed in scope with the logical inline override `maxInlineSize: 'none'`; the nested `Form`/state panels keep their own reading cap. The test now asserts the override and that no physical inset or gap was added.
- **Non-blocking, shared — nested `.page` padding (UX review F1).** `StateView`/`LoadingState`/`EmptyState` panels carry their own `.page` class (`src/components/StateViews.tsx:30-35`), so a state panel inside this page root receives two safe-area paddings. `ApiPage.tsx` already has the same nesting, so this is a shared-pattern issue; fixing it needs a page-variant class in `responsive.css` or a change to `StateViews.tsx`, both outside this story's scope. No local fix was applied to avoid duplicating the whole page utility inline.
- **Sibling-owned, not fixed here (N1/N2).** `AppointmentWorklist.tsx:49` still renders a bare `<table style={{ width: '100%' }}>` with no `.table-region`, `role="region"` or `tabindex="0"`, and its assign `<select>` (`:93-106`) has neither `.touch` nor 16 px text. These belong to `RESP-APPOINTMENT-WORKLIST` (and the form to `RESP-BOOKING-FORM`), which are `[-]` on this branch and not yet merged. Consequently "no horizontal page scroll at 320 px" and the 44 px/16 px rules cannot be fully proven for the whole scheduling screen until those stories land; the page-level assertions are honest about testing classes/attributes/structure only.
- **Pixel evidence.** jsdom cannot evaluate media queries, paint a focus ring or measure bounding boxes. The seven-width test feeds numbers into the two `min-width` queries and asserts the hook's resolution (not a painted layout); 44 px boxes, 200 %/400 % zoom and Arabic-clinician review remain Playwright/G5 tasks per `docs/design/responsive-layout.md` §13.
- **Pre-existing repository defect (tool evidence, untouched).** `git grep -nE '^(<<<<<<<|>>>>>>>) '` found committed conflict markers in three unrelated files: `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`. `git show HEAD:...Hl7Parser.java` confirms they are in `HEAD`, not introduced by this change and not from the clean `git merge`. They are outside this story's scope and were left untouched for the integrator.
- No new dependency, no tracker edit, no push/tag/`gh` call.

## Lessons for workers

- `.page` supplies safe-area padding and a 1200 px reading cap. For a page that hosts a worklist table, add `maxInlineSize: 'none'` as a logical inline style; `responsive.css` has **no** wide-page/`content--table` class and this story forbids editing it. Ask for a shared class rather than copying all four `padding-*` inset expressions inline.
- jsdom expands the symmetric `border` shorthand into `border-left`/`border-right` longhands. A "logical properties only" test that inspects those longhands reports a false positive; check only side-specific `margin`/`padding` and physical insets, as the existing `DataTable.responsive.test.tsx` does.
- `FormField` exposes `data-breakpoint` on its label/control wrapper, not on the `<input>`; `Button` exposes it on the button itself. Query the closest `[data-breakpoint]` when asserting per-band child state.
- `SchedulingScreen` already owns a region named "Scheduling"; a second labelled region with the same name breaks `getByRole('region', { name: 'Scheduling' })`. Use a stable `data-*` hook on the page root and query by class/attribute.
- Run `npx prettier --check` on the two scoped files before the module `check.sh`: the staff-web gate runs typecheck/lint/tests but not Prettier, and hand-edited JSX attributes drift from the pinned style.
- Vitest 5 has no `basic` reporter; use the default reporter for a single-file run.
- Do not run root `scripts/check.sh` as a frontend worker: it invokes the full `apps/api/check.sh` (`mvn verify` + all integration tests + OWASP). Use `bash apps/staff-web/check.sh` plus `bash scripts/frontend-check.sh`.
