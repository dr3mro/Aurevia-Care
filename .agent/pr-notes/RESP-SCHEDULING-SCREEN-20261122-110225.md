# RESP-SCHEDULING-SCREEN — Staff web: responsive `SchedulingScreen` (features-scheduling)

## Summary

Made the API-backed scheduling screen responsive using the existing breakpoint hook and the shared classes in `apps/staff-web/src/theme/responsive.css`, and added a new jsdom responsive test file. The root now carries `.page` (logical safe-area padding and reading-width cap) plus `data-breakpoint` / `data-scheduling-view`; the worklist and booking form sit in a `.grid-2` panel grid that stacks on phones and pairs from the medium breakpoint; long English and Arabic prose wraps with a logical inline property. No behaviour, text, API call, exported name, dependency or stylesheet changed. Synthetic fixtures only.

Changed files (only these two are in scope):
- `apps/staff-web/src/features/scheduling/SchedulingScreen.tsx`
- `apps/staff-web/tests/responsive/features-scheduling/SchedulingScreen.responsive.test.tsx` (new)

## Affected requirements

- **WEB-07** — staff web usable from 320 px upward; three layout bands; width alone must not trigger the unsupported-browser page. Mapped to `useBreakpoint()` + `.page` + `.grid-2`; asserted by the per-band tests and the 320/360/390/768/820/1024/1280 px matrix.
- **UX-01** — WCAG 2.2 AA reflow. Mapped to logical-only inline styles, `.page` safe-area padding, `.grid-2` `minmax(0, 1fr)` tracks, logical `overflowWrap`, no `display: none` on any clinical field/column, and preservation of the full column/field/label/role set and DOM keyboard order at every band. The shared `FormField`/`Button` supply `.field` (16 px text) and `.touch` (44 px coarse target).

Acceptance items "no horizontal page scroll", "no clipped or overlapping text" and "text at 200 percent zoom stays readable" are **structural only** in jsdom (classes, attributes, logical properties, no fixed block size). Pixel/reflow proof belongs to the Playwright projects in `docs/design/responsive-layout.md` §13 and is not claimed here.

## Risk class

Routine (contained presentation-only change to one feature component and one new test). The screen composes child components owned by separate responsive tasks, so the child-level gaps in Findings are coordination notes, not changes made here.

## Review owner

Senior React and Vite (code, typecheck, structure) and UX reviewer (keyboard, screen reader, English/Arabic RTL). Both were run as **simulated, bounded, read-only subagent reviews**; their reports are in this note. No human, clinical, simulated-user or patient-participant evidence was supplied.

## Data changes

None. No migration, backfill, lock or volume impact; no table or SQL object touched.

## Authorization boundaries and audit events

None changed. Tenant isolation is untouched: both `appointmentsForTenant(items, organizationId)` calls, all callbacks and all handlers remain as they were. No audit event added or altered. No patient data or credential appears in source, tests or this note.

## Migration and rollout considerations

None. Presentation-only CSS-class and attribute changes; no release or deployment action.

## Test evidence

Evidence provenance: executed tool commands unless explicitly labelled **simulated reviewer**.

1. Environment: `npm ci --no-audit --no-fund` → `added 486 packages in 5s`.
2. Red (before the production change): `cd apps/staff-web && ./node_modules/.bin/vitest run --exclude 'e2e/**' tests/responsive/features-scheduling/SchedulingScreen.responsive.test.tsx` → `Test Files 1 failed (1)`, `Tests 17 failed | 3 passed (20)`; failures were `responsive scheduling screen root was not rendered` because the component had no responsive contract attributes.
3. Focused green after implementation: same command → `Test Files 1 passed (1)`, `Tests 20 passed (20)`.
4. Existing regression suite: `./node_modules/.bin/vitest run --exclude 'e2e/**' src/features/scheduling/scheduling-screen.test.tsx` → `Test Files 1 passed (1)`, `Tests 7 passed (7)`.
5. Typecheck final lines (`npm run typecheck`, exit 0):

   ```
   > aurevia-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```

   No `error TS…` appears anywhere in the full check log.
6. Lint of the two changed files: `./node_modules/.bin/eslint src/features/scheduling/SchedulingScreen.tsx tests/responsive/features-scheduling/SchedulingScreen.responsive.test.tsx --max-warnings 0` → `LINT_EXIT=0` (no output).
7. Logical-property assertion sanity check: `node -e "...physicalStylePattern..."` → `padding-left: 8px;` / `margin-right: 4px;` / `text-align: right;` / `left: 0;` → `true`; `padding-inline: 8px;` / `border: 1px solid red;` / `text-align: start;` → `false`. The assertion is not vacuous.
8. Full canonical gate: `cd apps/staff-web && bash check.sh` → `CHECK_EXIT=0`; `Test Files 159 passed (159)`; `Tests 1911 passed (1911)`; `No files with missing coverage.`; `207 files fully covered.`; `Statements 100% (3723/3723)`, `Branches 100% (1896/1896)`, `Functions 100% (1258/1258)`, `Lines 100% (3557/3557)`. The log contains intentional React console errors from `tests/i18n/I18nProvider.test.tsx` and `tests/theme/ThemeProvider.test.tsx` guard tests (`useI18n must be used inside I18nProvider` / `useTheme must be used inside ThemeProvider`); those tests assert the guards, and the suite still exits 0.

The repository-wide `scripts/check.sh` was intentionally **not** run: it invokes `apps/api/check.sh` (the full Java/integration suite) and `apps/patient-mobile/check.sh`, which this worker is explicitly told not to run and which are irrelevant to a staff-web-only change. The canonical module gate `bash apps/staff-web/check.sh` was run instead (twice, both exit 0).

Entry point to test trace: `SchedulingPage` renders `SchedulingScreen` (`src/features/scheduling/SchedulingPage.tsx:149`) on the scheduling route; the new file renders the same component directly with synthetic `syntheticAppointments` / `alphaOrganizationId` and a deterministic `matchMedia` stand-in, asserting structure at each band. Existing behaviour at the same entry point is asserted in `src/features/scheduling/scheduling-screen.test.tsx`.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (runs `vitest run --coverage --exclude 'e2e/**'` with V8, per-file thresholds 100/100/100/100). Result: 100% on all counters; 207 files fully covered, including `src/features/scheduling/SchedulingScreen.tsx`. No threshold, exclusion or skip was changed; no test disabled.

New/changed branches in `SchedulingScreen.tsx` and the test that takes each side:

| Branch | True side test | False side test |
|---|---|---|
| `stacked = breakpoint === 'compact'` / `data-scheduling-view` ternary (`'stacked-panels'` vs `'side-by-side-panels'`) | `responsive SchedulingScreen at compact width > exposes the breakpoint and stacks or pairs…`; matrix widths 320/360/390; RTL case | `… at medium width > exposes…`, `… at expanded width > exposes…`; matrix widths 768/820/1024/1280 |
| `data-layout` ternary (`'stacked'` vs `'side-by-side'`) | same `at compact width > exposes…` test | same `at medium/expanded width > exposes…` tests |
| `overflowWrap` prose style (no branch; static) | asserted in `uses logical properties only…` and the RTL test at every band | n/a |

Unchanged branches (handlers, `useEffect`, tenant filtering) remain covered by the seven tests in `src/features/scheduling/scheduling-screen.test.tsx`, which passed in the same full run. The new responsive test file alone does not call those handlers; the 100% figure is for the whole suite, as `check.sh` runs it.

## Clinical and architecture review

No signed data, cross-organization sharing, patient association, prescribing or critical result is changed. The responsive refactor is presentation-only. A simulated UX reviewer inspected keyboard/screen-reader and English↔Arabic behaviour and returned no blocking finding in the two scoped files after the test-assertion fix below; it explicitly stated that jsdom cannot confirm painted focus rings, real media queries or screen-reader announcements, so this is not clinical or human acceptance. No physician review was requested for this presentation-only story.

## Findings

1. **Tautological logical-properties assertion (simulated senior-react reviewer, medium; fixed).** The first draft checked camelCase keys with `CSSStyleDeclaration.getPropertyValue()`, which returns `''` unconditionally for those names, so a physical-style regression could not fail. Fixed in the in-scope test by asserting the raw `style` attribute against `physicalStylePattern` (direction-specific physical declarations only; symmetric `border`/`padding` shorthands remain allowed). Verified with a Node sanity check (Test evidence item 7). The same weakness exists in `tests/app/OrgSwitcher.responsive.test.tsx` and `tests/components/Dialog.responsive.test.tsx`; they are outside this scope and were not edited.
2. **Pre-existing merge-conflict markers in the base branch (tool evidence; not fixed, out of scope).** `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`. `git grep … HEAD -- <path>` and `git grep … origin/main -- <path>` show they are already committed in both HEAD and `origin/main` (e.g. `>>>>>>> origin/swarm/BUILD-37-469`). They are not from this story and would break a Java compile; the integrator/owner must resolve them. No file outside the declared scope was touched.
3. **Nested `.page` (simulated senior-react reviewer, low; coordination note).** `BookingForm` renders the shared `Form`, which also sets `className="page"`, so the screen now nests two `.page` elements. The outer one is required here for screen-level safe-area padding; the inner one belongs to `RESP-FORM`/`RESP-BOOKING-FORM`. Coordinate with `RESP-SCHEDULING-PAGE` so exactly one screen-level `.page` is owned. Not a blocker for this story's criteria.
4. **Worklist child gaps owned by another task (simulated UX reviewer, high; not changed here).** `AppointmentWorklist` still renders a bare six-column `<table>` with no `.table-region`/`role="region"`/`tabIndex={0}`, its per-row `<select>` has no `.field touch`, and row action names do not include patient identity. These are exactly the scope of the in-progress `RESP-APPOINTMENT-WORKLIST` task (agent swarm-1). Changing them here would breach this story's scope.
5. **Announcement and repeated-action findings (simulated UX reviewer; not changed here).** Recommendations to use `displayLocalName` with isolated `<bdi>` runs in the status region and to make repeated identical announcements distinct would change visible/spoken text and behaviour, which story step 5 explicitly forbids. They need an owner decision and a separate story. The same applies to the reported silent submit/conflict handling in `BookingForm` (`RESP-BOOKING-FORM`).
6. **Identifier/time cells not isolated (simulated UX reviewer, medium; child task).** Latin `resourceId`, `assignee` and formatted `<time>` runs in `AppointmentWorklist` are not wrapped in `MixedScriptText`; owned by `RESP-APPOINTMENT-WORKLIST`.

## Lessons for workers

- `CSSStyleDeclaration.getPropertyValue()` needs a hyphenated CSS name; passing a camelCase key silently returns `''` and makes a "logical properties only" assertion vacuous. Assert on the raw `style` attribute, or use typed property access — and remember jsdom expands a symmetric `border: 1px solid …` shorthand into physical `borderLeft`/`borderRight`, so allow symmetric shorthands.
- `responsive.css` wraps direct children of `.grid-2` but not of `.page`, and there is no `.clinical-value` class to opt into. Keep long prose wrapping with a logical inline `overflowWrap: 'anywhere'` (the same approach `OrgSwitcher` uses) instead of editing the stylesheet.
- `useBreakpoint()` falls back to `expanded` when `matchMedia` is unavailable. Existing tests that do not stub it therefore render the side-by-side variant; keep queries independent of the presence of compact-only wrappers.
- This screen renders two `role="status"` regions (scheduling status and booking conflict). Always query them by accessible name.
- Before treating `git grep` conflict markers as damage from your own merge, check whether they are already committed in HEAD and `origin/main` with `git grep … HEAD -- <path>`; pre-existing markers in the base branch are an owner/integrator problem, not a reason to edit out-of-scope files.
