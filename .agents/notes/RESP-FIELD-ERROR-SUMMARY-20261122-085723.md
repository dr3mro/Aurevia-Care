# RESP-FIELD-ERROR-SUMMARY — Staff web: responsive `FieldErrorSummary` (features-admin)

## Summary

Made the shared `FieldErrorSummary` alert responsive at 320–1280 px in both directions without changing its behavior, text, hrefs, roles or public API. The component now uses the existing `stack` and `touch` classes from `apps/staff-web/src/theme/responsive.css` and the `useBreakpoint` / `usePointerCoarse` hooks. Logical inline styles keep long English and Arabic messages wrapping with no horizontal page scroll; there is no direction override, no `display: none` and no new CSS. Added a new responsive Vitest file that mocks `matchMedia` for compact, medium and expanded and asserts structure, labels/roles, keyboard order and RTL across all seven required widths.

Task: `tasks/00253000-RESP-FIELD-ERROR-SUMMARY.md`. Branch: `swarm/RESP-FIELD-ERROR-SUMMARY-883`. Base reconciled with `git merge --no-edit origin/main` → "Already up to date" (0/0), at start and again before finishing. Head before commit: `d8592fa1`.

## Affected requirements

WEB-07 (supported clients / responsive staff web), UX-01 (WCAG 2.2 AA, reflow at 320 px, 44 px coarse-pointer targets, RTL). Design contract: `docs/design/responsive-layout.md`; decision `docs/decisions/0004-responsive-staff-web.md`.

## Risk class

Routine (docs/WORKFLOW.md): a contained, presentation-only change to one shared UI primitive. No behavior, text, API call, export, contract, data or authorization change. The module is reused by admin pages and `RegisterPatientForm`; all callers are unaffected.

## Review owner

Senior React and Vite (`aurevia-senior-react`) — bounded static review returned no blocking findings; its one non-blocking hygiene finding (RTL test leaking `dir`/`lang`) was fixed before the final gate. Orchestrator owns edits and checks. No clinical/human reviewer involved; evidence below is tool and simulated-review only.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role or patient-link scope change; no audit event added or changed. No real patient data or credentials used — all fixture text is synthetic (e.g. `patient-name`, `visit-date`, Arabic synthetic strings).

## Migration and rollout considerations

None. Pure front-end presentation change; no storage or deployment topology change.

## Test evidence

Entry point → test trace: `FieldErrorSummary` is a shared admin feature component used by `ConfigPage`, `AuditPage`, `StaffPage`, `FacilitiesPage` and `features/patients/RegisterPatientForm`; the new test renders the component directly through `ThemeProvider` with synthetic `FieldErrorLink[]` fixtures, and asserts the `role="alert"` region, `aria-label`, `<h3>`, ordered `<a href="#id">` links and their responsive classes/attributes.

Commands run (executed by the orchestrator; tool evidence):

- `npx vitest run tests/responsive/features-admin/FieldErrorSummary.responsive.test.tsx --exclude 'e2e/**'` — RED before implementation: `Test Files  1 failed (1)`, `Tests  10 failed | 2 passed (12)` (missing `stack`/`touch` classes, `data-breakpoint`/`data-coarse-pointer`, logical wrapping styles).
- Same command after implementation — GREEN: `Test Files  1 passed (1)`, `Tests  12 passed (12)`.
- `npm run typecheck` — clean; final lines:
  ```
  > aurevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no `error TS…` output, exit 0)
- `npm run lint` — clean; final lines:
  ```
  > aurevia-staff-web@0.1.0 lint
  > eslint . --max-warnings 0
  ```
  (no warnings, exit 0)
- `bash apps/staff-web/check.sh` (generated-client check, typecheck, lint, build, bundle-size gate, Vitest with coverage) — GREEN, final lines:
  ```
  Test Files  159 passed (159)
       Tests  1903 passed (1903)
  No files with missing coverage.
  207 files fully covered.
  ```
  (exit 0; re-run after the final test-hygiene edit with the same result)

Evidence sources: executed shell commands (tool), bounded static review report (reviewer). No simulated-user walkthrough and no human participant were used for this presentation-only primitive.

## Coverage

Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest v8, `perFile: true`, 100% line/branch/function/statement thresholds). Result:
```
Statements   : 100% ( 3723/3723 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3557/3557 )
```
"No files with missing coverage" — the changed `FieldErrorSummary.tsx` is fully covered. No threshold, exclusion or skip was added.

New/changed branches and the test that takes each:

| Branch | Test |
|---|---|
| `errors.length === 0` true → `return null` | `renders no alert for an empty error list` |
| `errors.length === 0` false → renders the alert | `renders a stacked, labelled alert with tappable links at $width px ($breakpoint)` (all 7 cases) |
| `errors.map` callback with multiple entries | `preserves the keyboard order and focusability of every link` (3 links, DOM order + hrefs asserted) |
| `useBreakpoint()` → `compact` / `medium` / `expanded` | `it.each(widthCases)` at 320/360/390, 768/820/1024, 1280; plus `follows a viewport change without remounting or altering the messages` |
| `usePointerCoarse()` → `true` / `false` | `data-coarse-pointer` asserted in the 7 width cases (true at 320–1024, false at 1280) |
| RTL path (document `dir=rtl`, `lang=ar`) | `mirrors a long Arabic RTL summary without direction overrides or hidden text` |

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or critical-result behavior is changed. The component only lays out an existing form-level validation summary. Painted pixel/zoom behavior remains a Playwright/WEB-10 concern outside this story's jsdom scope.

## Findings

- No blocking defects. `bash apps/staff-web/check.sh` is green with 100% per-file coverage.
- The senior-react reviewer's only finding (RTL test left `dir`/`lang` on `documentElement`) was fixed by removing both attributes in the test's `afterEach`; final targeted run and full gate stayed green.
- Pre-existing, unrelated repository defect observed while checking for conflicts: committed conflict markers exist at HEAD in `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7Parser.java,Hl7ParseException.java,Hl7Segment.java}` (`<<<<<<< HEAD` / `>>>>>>> origin/swarm/BUILD-37-469`). Confirmed with `git show HEAD:apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7Parser.java`. Not introduced by this change and outside this task's scope; left untouched. `git merge --no-edit origin/main` reported "Already up to date", so no merge conflict was introduced here.
- No missing class was needed: `stack` and `touch` from `responsive.css` covered the layout; no stylesheet edit.

## Lessons for workers

- An `<a>` is inline by default and ignores `min-block-size`/`min-inline-size`; to get the `.touch` 44 px coarse-pointer target, give it `display: block` (or flex) in the inline style and let the stylesheet own both target floors — never set an inline `min-height`, which would outrank the `@media (pointer: coarse)` rule.
- For jsdom responsive tests, drive a width-based `matchMedia` stand-in and assert classes, `data-breakpoint` / `data-coarse-pointer`, logical-style strings, roles, hrefs and DOM order; leave pixel measurement to Playwright. Always clean up `dir`/`lang` in the test's own `afterEach`, even when a global setup file also does it, to avoid order dependence.
