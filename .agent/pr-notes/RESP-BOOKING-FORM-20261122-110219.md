# RESP-BOOKING-FORM — Staff web: responsive `BookingForm`

## Summary

Made `BookingForm` responsive within its two-file scope without changing its
behaviour, text, API calls or exported names. It now consumes `useBreakpoint()`
and `usePointerCoarse()`: booking fields stack in one column on phones and reuse
the stylesheet's existing `grid-2` two-column grid from the medium breakpoint,
with observable `data-layout`, `data-breakpoint` and `data-coarse-pointer`
attributes on a component-owned region. The resource hint and the polite
conflict status moved into a `stack` region whose paragraphs carry logical-only
wrapping styles (`min-inline-size: 0`, `overflow-wrap: anywhere`,
`white-space: normal`), so long English and Arabic strings wrap instead of
forcing horizontal scroll. The shared `Form`, `FormField`, `.page`, `.field` and
`.touch` classes already supply native inputs, 16 px text, 44 px coarse-pointer
targets, safe-area padding, focus reservation and the sticky action footer. No
stylesheet, migration, dependency or protected file was changed.

A new jsdom test file mocks `matchMedia` at 320, 360, 390, 768, 820, 1024 and
1280 px, for LTR and RTL, and asserts classes, data attributes, labels, roles and
keyboard order. Painted pixel/zoom checks remain with the Playwright tasks.

## Affected requirements

- **WEB-07** — supported clients / responsive staff web: compact, medium and
  expanded bands; columns stack on phones and use two columns from 768 px;
  coarse-pointer targets; logical properties; no hidden clinical field.
- **UX-01** — Arabic/RTL mirroring, long-string wrapping, zoom-safe layout,
  keyboard/screen-reader structure.

Decision `0004-responsive-staff-web.md` supersedes the 768 px floor in the WEB-07
spec text; this change follows the decision (render down to 320 px).

## Risk class

Routine — contained presentation change in one staff-web component, with
accessibility sensitivity. No clinical value, authorization, data or API
contract is changed. Reviewed by simulated senior React and senior TypeScript
specialists (see below); not human or clinical acceptance.

## Review owner

Senior React and Vite (implementation), Senior TypeScript (strict typing).
Both were invoked as read-only specialist reviewers; their outcomes are below.
UX and clinician review remain separate launch gates.

## Data changes

None. No migration, backfill, lock, volume impact, schema or contract change.
Synthetic values only (`synthetic-organization-1`, `synthetic-value`,
`2026-03-09T07:00:00Z`); no patient data, real identifier or credential appears
in the code or tests.

## Authorization boundaries and audit events

None changed. The component still only builds a local draft and calls the
injected `onBook` with the same `BookingInput` shape. Conflict detection still
uses the tenant-scoped `appointments` array via `conflictingAppointment`. No
tenant, role, patient-link or audit behaviour is touched.

## Migration and rollout considerations

None. Presentation-only change; normal commit revert is the rollback.

## Test evidence

Environment: staff-web `node_modules` was absent; installed with
`npm ci --no-audit --no-fund` (486 packages, exit 0) before running checks.

- **Red first** — `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-scheduling/BookingForm.responsive.test.tsx --exclude 'e2e/**'`
  - Before implementation: `Test Files  1 failed (1)` / `Tests  9 failed | 8 passed (17)` — every failure was a missing `booking-fields` / `booking-hints` region.
- **Scoped green** — `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-scheduling/BookingForm.responsive.test.tsx src/features/scheduling/components.test.tsx --exclude 'e2e/**'`
  - Final lines: `Test Files  2 passed (2)` / `Tests  30 passed (30)`. The untouched existing `components.test.tsx` BookingForm tests still pass.
- **Typecheck (required frontend final lines)** — `cd apps/staff-web && npm run typecheck`
  - Final lines:
    ```
    > smartclinic-staff-web@0.1.0 typecheck
    > tsc --noEmit -p tsconfig.json
    ```
    `TYPECHECK_EXIT=0`, no `error TS…`. An unused `ReactElement` type import found on the first run was removed and the check re-run clean.
- **Mandatory full check** — `bash apps/staff-web/check.sh` (from the repo root), exit 0. Every step ran:
  - `npm run gen:api:check` — `gen:api: generated client matches the contract`.
  - `npm run typecheck` — pass (see above).
  - `npm run lint` — `eslint . --max-warnings 0`, pass.
  - `npm run build` — pass.
  - `node scripts/check-bundle-size.mjs` — pass.
  - `npm run test:coverage` — `Test Files  159 passed (159)` / `Tests  1908 passed (1908)`; `No files with missing coverage.` / `207 files fully covered.` / `4 empty files skipped.` The `ThemeProvider.test.tsx` console error stack in the output is an intentional negative test; its file and suite pass.
- **Branch currency** — `git merge --no-edit origin/main` at start: `Already up to date.`; after implementation the same merge was re-run with the same result. No conflict markers in either scoped file; `git status --short` shows only the two scoped paths.

Entry-point-to-test trace: `BookingForm` (exported component) → `useBreakpoint()` /
`usePointerCoarse()` (`useMediaQuery` / `matchMedia`) → `[data-part="booking-fields"]`
class + `data-layout` + `[data-part="booking-hints"]` logical inline styles →
assertions in `tests/responsive/features-scheduling/BookingForm.responsive.test.tsx`;
submit / conflict path → `handleSubmit` → `onBook` and `[data-testid="conflict-status"]`
→ same new file plus the existing `src/features/scheduling/components.test.tsx`.

## Coverage

Command: `cd apps/staff-web && npm run test:coverage` (invoked by
`bash apps/staff-web/check.sh`, exit 0).

Result: `Statements 100% (3725/3725)`, `Branches 100% (1894/1894)`,
`Functions 100% (1258/1258)`, `Lines 100% (3559/3559)`; 207 files fully covered;
`No files with missing coverage.` The touched `src/features/scheduling/BookingForm.tsx`
is in that fully covered set. No threshold, exclusion, skip or ignore comment was
added or changed.

| New or changed branch in `BookingForm.tsx` | Test that takes it |
|---|---|
| `breakpoint === 'compact'` true → `className: 'stack'`, `data-layout: 'single-column'` | `stacks fields on phones… at 320 px`, `360 px`, `390 px`, and the RTL test at 360 px |
| `breakpoint === 'compact'` false → `className: 'grid-2'`, `data-layout: 'two-column'` | `stacks fields on phones and uses side-by-side columns from medium at 768 px`, `820 px`, `1024 px`, `1280 px` |
| Existing `ready = resource && start && facility && patient && visit` chain (all five short-circuit sides) | existing `components.test.tsx` submit/conflict/partial-fill tests plus `keeps the conflict status and submit path working at every band` |
| Existing `conflict = ready ? conflictingAppointment(...) : undefined` (both sides) | `warns politely and blocks a same-tenant protected-slot conflict` (true), `does not submit an empty draft…` and the new all-bands test (false) |
| Existing `if (!ready || conflict) return` (both guards and the fall-through) | existing submit, conflict and empty-draft tests plus the new all-bands test |

`useBreakpoint()` / `usePointerCoarse()` add no branch in this component; their
own branches are covered by the theme tests in the same full-coverage run.

## Clinical and architecture review

Not required: the component renders a booking draft form, not a clinical value,
order, result, release decision or patient record; it changes no authorization.
Automated axe and painted pixel/zoom checks are separate Playwright tasks; the
jsdom assertions here read structure, classes, attributes and order only, as
the story requires. Reviewers are simulated, not human participants.

### Simulated review outcomes

Both specialists were read-only and did not edit files; neither ran checks
(no shell in the subagent sessions). They reviewed source against the story.

- **Senior React and Vite (simulated):** confirmed logical-only properties,
  `.field`/`.touch`/`.page` coverage, no hidden clinical field, and no drift in
  behaviour, text, API calls or exported names. Raised one blocker (the native
  date/time picker criterion — see Findings and `task-requests`) and three
  non-blocking items: the hints region was squeezed into one grid cell at
  medium/expanded, the jsdom evidence was at risk of being described as painted
  output, and the submit test did not assert `onBook`.
  - Fixed in this pass: the hints region now spans the grid with the
    direction-neutral inline style `gridColumn: '1 / -1'`; the native-control
    test was reworded to stop claiming a native picker exists; the submit test
    now clicks submit at 320/768/1280 px and asserts the exact `onBook` payload.
  - Label: jsdom results are DOM/class/attribute/style checks only, not painted
    layout or zoom proof (the Playwright projects own those).
- **Senior TypeScript (simulated):** no blocker. Confirmed strict typing, no
  `any`/`@ts-ignore`, no skipped tests, unchanged 100% thresholds/exclusions,
  doc comments, and both sides of the new `fieldLayout` ternary covered. The
  reviewer concurred that the native-picker item is a documented decision,
  not a defect in this change.

## Findings

- **Unmet acceptance item — native date/time picker on coarse pointers (owner
  decision needed).** `BookingForm` has no custom calendar/time picker: the
  `booking-start` field is a native `<input type="text">` from `FormField` at
  every width and pointer type, holding a UTC ISO string. `FormFieldProps`
  (`apps/staff-web/src/components/FormField.tsx:11`) accepts only
  `text | email | password`, and `onBook` must keep receiving the same `...Z`
  ISO string for conflict detection. A genuine native `datetime-local` picker
  would require an out-of-scope `FormField` API change plus UTC value conversion
  and would alter behaviour, which story step 5 forbids. The simulated senior
  React reviewer rated this a blocker. I did not guess at that change; the test
  now states accurately that controls are native `INPUT`s with no custom widget
  (not that a picker exists), and a `task-requests` entry asks for an owner
  decision. All other acceptance items and every automated gate pass.
- **Pre-existing committed conflict markers outside scope (not from this
  change).** `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports markers in
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java`, while `git status --short` is clean for
  those files — they are committed on the branch, not working-tree damage from
  this story. They are outside the staff-web scope and were left untouched.
  The integrator should decide how to resolve them.
- **`gen:api:check` is green on this branch.** An older worker note
  (`RESP-FORM-20261121-214117.md`) reported a stale generated client; the full
  check here regenerates/compares cleanly, so no drift was worked around.
- **Repo-wide `scripts/check.sh` was deliberately not run.** It loops over
  `apps/api/check.sh`, which is `mvn -q -B verify` (full Java integration and
  coverage gate, over ten minutes). The execution request forbids that command
  and asks for the scoped module check instead, so the mandatory
  `bash apps/staff-web/check.sh` was run in full (all six steps, exit 0) and the
  integrator/Delivery own the full gate.
- **jsdom normalises a numeric `min-inline-size: 0` inline style to `'0'`, not
  `'0px'`.** The new wrapping assertion accepts either spelling and also checks
  the style attribute contains `min-inline-size`.
- **The full check's console error stack is an intentional negative test** in
  `tests/theme/ThemeProvider.test.tsx` ("useTheme must be used inside
  ThemeProvider"); it does not fail the suite.

## task-requests

```json
[
  {
    "title": "Decide native date/time input support for coarse-pointer booking forms",
    "details": "WEB-07/UX-01 ask for native date/time pickers on coarse pointers. BookingForm's start field is a plain native text input holding a UTC ISO instant because FormFieldProps only accepts text/email/password and onBook must keep receiving the same `...Z` ISO string. Owner must decide whether to add a date/time input variant with UTC conversion (a behaviour/API change) or record a permanent documented deviation. RESP-BOOKING-FORM deliberately made no behaviour change and stayed in its two-file scope.",
    "scope": ["apps/staff-web/src/components/FormField.tsx", "apps/staff-web/src/features/scheduling/BookingForm.tsx", "apps/staff-web/tests"],
    "depends": []
  }
]
```

## Lessons for workers

- Put observable `data-layout` / `data-breakpoint` on a region your own component
  owns. `Form` counts `FormField`s recursively, so wrapping five booking fields
  in a grid leaves `Form`'s own `[data-part="fields"]` at `single-column`; the
  meaningful breakpoint assertion belongs on the inner `booking-fields` region.
- `responsive.css` already provides `.stack`, `.grid-2`, `.page`, `.field` and
  `.touch`. For gaps such as hint wrapping, use logical inline styles
  (`min-inline-size`, `overflow-wrap`, `white-space`) and record the missing
  class rather than editing the protected stylesheet. None was needed here
  beyond generic wrapping.
- TypeScript's `noUnusedLocals` fails the typecheck on an unused `import type`
  in a test file; remove it rather than suppressing the rule.
- jsdom cannot paint pixels or evaluate `@media`; assert classes, data
  attributes and DOM order, and leave measured boxes to the Playwright projects.
- Run `npm ci` in `apps/staff-web` first when `node_modules/.bin/vitest` is
  missing; the module `check.sh` does this automatically.
- When a checklist item collides with an explicit "do not change behaviour/API"
  constraint, do not invent a widget to satisfy it. Record the conflict, keep
  the scoped tests accurate about what they prove, and ask for the decision in
  a `task-requests` block.

## Why this is not finished

One acceptance item is unresolved; every automated gate and all other items
pass.

- **Unmet item:** "Date and time pickers use native inputs on coarse pointers."
  No automated check fails — `bash apps/staff-web/check.sh` exits 0 with
  `Test Files 159 passed (159)`, `Tests 1908 passed (1908)` and 100% coverage.
  The gap is a specification/constraint conflict, not a tool failure.
- **Most likely cause:** `FormField` has no date/time input type, and a
  `datetime-local` value cannot carry the UTC `Z` instant that `onBook` and
  `conflictingAppointment` require. Adding one needs an out-of-scope API change
  and value conversion; story step 5 forbids behaviour changes.
- **What I tried:** kept the native text `INPUT` at all widths and pointers,
  added logical wrapping and breakpoint structure, made the test describe the
  absence of a custom picker accurately, and requested an owner decision in the
  `task-requests` block above. The simulated senior React reviewer confirmed
  the conflict.
- **Exact next step:** owner accepts either a dedicated task that adds a
  typed date/time variant to `FormField` with UTC conversion and updated
  scheduling tests, or a documented permanent deviation for this criterion.
  Only then can this story be called fully accepted.
