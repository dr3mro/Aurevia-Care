# RESP-REGISTER-PATIENT-FORM — responsive `RegisterPatientForm` (features-patients)

## Summary

Made the presentational patient-registration form responsive with the existing
`responsive.css` classes and the `useBreakpoint` / `usePointerCoarse` hooks. The
root now uses `page` (reading-width cap, logical gutter, safe-area insets), the
six fields are grouped into three labelled `grid-2` sections (identity, date of
birth, language) that stack on phones and become two columns from medium
upward, and the validation summary is held in a `data-part="warning"` region
above the submit bar. The sections preserve the original field order so the
summary links and the tab sequence still agree. No stylesheet, token,
behaviour, text, API call or exported name changed; no new dependency was added.

Source: tool reads and executed commands. No human, patient or clinical
participant was involved; all fixtures are synthetic.

## Affected requirements

- **WEB-07** — supported staff-web clients and responsive layout from 320 px.
- **UX-01** — English/Arabic, RTL mirroring, WCAG 2.2 AA (reflow, target size,
  text scaling, focus order).

Acceptance-to-test mapping (all in `tests/responsive/features-patients/RegisterPatientForm.responsive.test.tsx`):

| Acceptance item | Test / assertion |
|---|---|
| 320, 360, 390, 768, 820, 1024, 1280 px | `keeps every field and logical page layout at $width px ($breakpoint)` — `it.each(widthCases)` |
| Columns stack on phones, side-by-side from medium | each section's `data-layout` is `single-column` at compact and `two-column` at medium/expanded; `grid-2` class present |
| Long English and Arabic strings wrap | `wraps a long English draft without truncation at the narrowest width`; Arabic labels in the RTL test; no `textOverflow`/`whiteSpace` override |
| Coarse pointers 44×44, inputs 16 px | every input keeps `field touch`; both buttons keep `touch`; `data-coarse-pointer` observed (rules live in the shared components/classes) |
| Safe-area insets, logical properties only | root keeps `page`; `physicalStyleFindings` and `hiddenOrDirectionFindings` are empty |
| No clinical field hidden with `display: none` | six inputs asserted at every width; `hiddenOrDirectionFindings` empty |
| 200 % zoom readable | no fixed block size, no truncation, `page` wrapping class; pixel/zoom measurement is left to the Playwright tasks as the story directs |
| Long forms grouped into labelled sections | three `[data-part="section"]` nodes with `role="group"` and localized `aria-label` |
| Warning above submit bar, keyboard order | `keeps the warning above the submit bar and the LTR/RTL keyboard order stable` — DOM-order comparison of the warning region, six summary links, six inputs, submit, cancel, in both directions; link ids equal input ids in order |
| RTL | `mirrors under RTL at phone and desktop widths without a direction override`; the warning/keyboard test also runs with `locale="ar"` and `dir="rtl"` |
| No behaviour/text/export change | `preserves field edits, submit and cancel through the normal DOM events`; unchanged labels and `registrationErrorLinks` export; original field/tab order preserved; existing 1 903-test suite green |

## Risk class

Routine (contained presentational React change; no data, contract or permission boundary).

## Review owner

Senior React and Vite (`orevia-senior-react` skill applied as the implementation
definition of done). A bounded, review-only specialist pass (`orevia-senior-react`
subagent) inspected both scoped files and reported one medium and two low
findings; all three were addressed (see Findings). The subagent had no shell
tool, so its review was static — it did not independently run the gates. No
clinical or human review was required or obtained for this presentation-only
slice.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. Tenant, role and patient-link scope are untouched; no audit event added or
changed. The form remains presentational and performs no network call.

## Migration and rollout considerations

None. No schema, contract or generated-client change (`gen:api:check` green).

## Test evidence

Evidence source: executed commands (tool), not a reviewer, simulated user or
human participant.

- `npm ci --no-audit --no-fund` → `added 486 packages in 4s` (install from the
  existing lockfile only; `node_modules` was absent in the worker. No package or
  lockfile file was changed).
- `npx vitest run tests/responsive/features-patients/RegisterPatientForm.responsive.test.tsx --reporter=dot`
  (red, before implementation) → `Test Files 1 failed (1)`, `Tests 10 failed | 1 passed (11)`.
- Same command after implementation and the review fixes → `Test Files 1 passed (1)`, `Tests 12 passed (12)`, `Duration 686ms`.
- `bash apps/staff-web/check.sh` → all six stages green. Typecheck final lines:

  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```

  (exit 0, no `error TS…` output). Lint final lines:

  ```
  > orevia-staff-web@0.1.0 lint
  > eslint . --max-warnings 0
  ```

  (exit 0). Build final line `✓ built in 111ms`; bundle gate printed `ok` for all
  chunks including `index-MenjO381.js 52529 ok`. Test final lines:

  ```
  Test Files  159 passed (159)
  Tests       1903 passed (1903)
  ```
  Final run exit code `0`.
- `git merge --no-edit origin/main` → `Already up to date.` (at start and after
  the implementation stage). Branch `swarm/RESP-REGISTER-PATIENT-FORM-968`.

Entry-point-to-test trace: the staff web mounts `PatientsPage`, which renders
`RegisterPatientForm` when registration is open (`PatientsPage.tsx:159-168`);
the new responsive test renders that component directly at each mocked
`matchMedia` band and asserts the section/group/input/button structure, roles,
labels and order. Existing `PatientsPage.test.tsx` registration tests still pass
in the full suite, so the page-level entry point is unchanged.

## Coverage

- Command: `bash apps/staff-web/check.sh` (its final `npm run test:coverage`,
  V8 with per-file thresholds of 100 for lines/branches/functions/statements).
- Result: `No files with missing coverage.` / `207 files fully covered.` /
  `Statements 100% (3724/3724)`, `Branches 100% (1894/1894)`,
  `Functions 100% (1258/1258)`, `Lines 100% (3558/3558)`.
- The only new branch is the `sectionLayout` ternary in
  `RegisterPatientForm.tsx`:
  - `breakpoint === 'compact'` → `single-column`: the 320/360/390 px `it.each`
    cases and the RTL phone case.
  - non-compact → `two-column`: the 768/820/1024/1280 px `it.each` cases and the
    RTL desktop case.
- The six existing per-field error-spread ternaries and `errorText`'s
  defined/undefined side are both taken by the empty-errors width cases and by
  the all-errors keyboard-order case; `registrationErrorLinks`' `if` is covered
  on both sides by the same two cases. No new `catch`, `switch`, `&&`/`||`,
  guard or early return was added; no test was skipped and no threshold,
  exclusion or generated-file rule was changed.

## Clinical and architecture review

Not required: no clinical workflow, signed data, patient association,
prescribing, critical result, tenant boundary, state machine or contract is
changed. Presentation-only evidence is simulated; it is not clinical or human
acceptance, and the real-browser pixel checks remain the Playwright tasks in
`RESP-E2E-*`.

## Findings

- **Resolved (specialist review, MEDIUM):** the first regrouping put `language`
  before the two date-of-birth fields, so the DOM/tab order no longer matched
  `FIELD_ORDER` and the summary links. Fixed by using three labelled sections —
  identity (`displayName`, `mrn`, `issuer`), date of birth (`dob`,
  `dobUnknownReason`), language — which preserves the original field order.
  The keyboard-order test now asserts that the six link ids equal the six input
  ids in order, in both directions.
- **Resolved (specialist review, LOW):** RTL keyboard/link association was not
  directly asserted. The warning/keyboard-order test now runs for both LTR and
  RTL with localized names.
- **Resolved (specialist review, LOW):** the hidden-field guard only inspected
  inline styles. It now also fails on the stylesheet's `hide-compact` and
  `only-compact` classes on any element with a class in the dialog.
- The registration form has no duplicate-warning region — the duplicate warning
  messages in `features/patients/messages.ts` belong to `PatientOverview`.
  Rather than invent new text (the story forbids text changes), the equivalent
  warning for this form is the existing `FieldErrorSummary`; it is now wrapped
  in `[data-part="warning"]` and tested to precede the submit button in DOM
  order at every breakpoint.
- jsdom expands the shared `border` shorthand set by `FormField` and `Button`
  into physical `borderLeft`/`borderRight`/… properties. The physical-style
  assertion is therefore scoped to the elements this component owns; the
  shared children have their own responsive tests. No physical property or
  direction override was added by this change.
- The worker image had no `node_modules` for staff web; `npm ci` from the
  committed lockfile restored the toolchain. This is an environment setup step,
  not a source change.
- `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports conflict markers in three
  unrelated Java files (`exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java`,
  `Hl7Segment.java`). They are already committed at base HEAD `7c2008e9` (verified
  with `git show HEAD:…`), which equals `origin/main`; my merges returned
  `Already up to date.` They are out of this story's scope and were not touched.
  The integrator should resolve them by keeping both sides' intent.

## Lessons for workers

- `Form` counts fields recursively, so wrapping `FormField`s in a
  `role="group"` `grid-2` section keeps the count and preserves the shared
  single/two-column decision; expose the layout you chose with a `data-layout`
  attribute so jsdom can assert it.
- In jsdom, `element.style.border = '1px solid …'` reads back as physical
  `borderLeft`/`borderRight`/`borderTop`/`borderBottom`. Scope logical-style
  assertions to the elements your own component owns when shared children use
  a direction-neutral `border` shorthand.
- `FieldErrorSummary` renders nothing for an empty error list, so assert the
  warning *region*, not the alert, in tests that render a clean draft.
- When regrouping a form into sections for layout, pin the summary-link order to
  the rendered input-id order. It is easy to move a field into a different
  section and silently change the tab order, which is a behaviour change.

## Why this is not finished

Not applicable — every required gate passed locally. Full integration, database
and Delivery checks remain the integrator's responsibility.
