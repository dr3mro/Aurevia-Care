# RESP-STAFF-PAGE · Staff web: responsive `StaffPage` (features-admin)

PR #756, tested branch tip 8ac4fcfe75d90745630f31789c8dd7c94d060343.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (TypeScript/frontend and gate lines):

```text
python coverage: WARNING coverage is not installed; the tests ran without the coverage check
FAIL unreferenced-service: apps/api/src/main/java/com/x/NewService.java
FAIL unreferenced-service: apps/api/src/main/java/com/x/OrphanService.java
FAIL unreferenced-service: apps/api/src/main/java/com/synthetic/OrphanService.java
ERROR: test_maven_failure_with_failing_reports_still_fails (test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails)
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_worker_gate_coverage.py", line 554, in test_maven_failure_with_failing_reports_still_fails
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_worker_gate_coverage.py", line 521, in run_main
```

Task: RESP-STAFF-PAGE

Swarm worker 5. Local checks: failed. Agent result: ok.

# RESP-STAFF-PAGE — Staff web responsive `StaffPage` (features-admin)

## Summary

`StaffPage` now uses the shared responsive grid and breakpoint hook instead of
ad-hoc inline grids. The page root carries `page`, the invite fields use `stack`
on compact and `grid-2` from medium, and the staff table is wrapped in the
labelled, keyboard-scrollable `table-region` only on compact phones. Every
clinical cell uses logical wrapping styles so long English and Arabic values
wrap instead of clipping or forcing a page-level horizontal scroll. The active
band is exposed as `data-breakpoint` and key regions as `data-part` so the
structure can be asserted in jsdom, which cannot evaluate media queries.
A new test mocks `matchMedia` for compact, medium and expanded and asserts the
structure, names and roles, keyboard order and right-to-left case. No behaviour,
text, API call or exported name changed; no new dependency; no file outside the
two-file scope was edited. Synthetic fixtures only.

## Affected requirements

- WEB-07: supported client widths. The `page`, `stack`, `grid-2` and
  `table-region` classes in `src/theme/responsive.css` supply the single-column
  phone grid, the 768 px inline-padding and two-column step, and the compact-only
  labelled scroll region. Width changes presentation only; no data or action is
  gated by a breakpoint (ADR-0004).
- UX-01: English and Arabic, RTL, logical properties and accessibility. Long
  English and Arabic runs wrap in isolated `<bdi dir="auto">` runs; no component
  sets a physical direction, hides a field or column, or clips a clinical value.
  Coarse-pointer 44 px targets come from the existing `.touch` rule on buttons
  and inputs, 16 px input text from `.field`, and safe-area padding from `.page`.

Acceptance mapping (story step → implementation → test):

| Story step | Implementation | Test |
|---|---|---|
| 1. Read component, existing test, design guide, stylesheet, hook | Evidence read: `StaffPage.tsx`, `StaffPage.test.tsx`, `docs/design/responsive-layout.md`, `docs/decisions/0004-responsive-staff-web.md`, `src/theme/responsive.css`, `src/theme/useBreakpoint.ts`, `src/theme/breakpoints.ts`, sibling `DataTable`/`Form`/`ApiPage` responsive patterns | — |
| 2. Works at 320/360/390/768/820/1024/1280, LTR and RTL, no scroll/clipping, logical properties, wrap, stacking, 44 px targets, 16 px inputs, safe areas, no hidden column, 200% zoom | `StaffPage.tsx`: `page` root with safe-area padding; `stack`/`grid-2` invite fields; compact-only `table-region` with `role="region"`, `aria-label`, `tabIndex=0`; logical `wrappingStyle` on every header and cell; `cluster` dialog actions | `tests/responsive/features-admin/StaffPage.responsive.test.tsx`: compact/medium/expanded structure, all-band column set and wrapping, keyboard order and touch classes, RTL logical-only scan. Painted-pixel and zoom checks remain Playwright tasks per §13. |
| 3. Use existing classes and hook, no new CSS; missing class → inline logical styles + lesson | Only `page`, `stack`, `grid-2`, `table-region`, `cluster` plus `useBreakpoint()`; no missing class, no stylesheet edit | Tests assert the exact classes; RTL test asserts no physical property and no inline `direction`/`display: none` |
| 4. New test mocks matchMedia for the three bands, asserts structure/labels/roles/keyboard/RTL; existing tests pass | New test file at the scoped path; `matchMedia` stub keyed by `BREAKPOINT_MEDIA_QUERIES` | 6 new tests; `src/features/admin/StaffPage.test.tsx` unchanged, still green (33 tests) |
| 5. No behaviour, text, API or export change | Diff limited to imports, one wrapping constant, hook calls and layout attributes/classes; no handler, message or route edit | Combined targeted run 39 tests green; full suite 1897 tests green |

Entry-point-to-test trace: `StaffPage` is the exported page registered at
`/admin/staff` (existing route test in `StaffPage.test.tsx`). The responsive test
renders that exported component through the real `ThemeProvider` →
`I18nProvider` → `ActiveOrganizationProvider` stack with a mocked transport, so
each assertion follows `StaffPage` → `StaffContent` → `ApiPage` → `StaffList`
to the real table, buttons and fields. The global stylesheet is imported at
`src/main.tsx`, so `responsive.css` classes are live in the built app; jsdom
assertions on classes and attributes are structural evidence only, not painted
layout claims (`docs/design/responsive-layout.md` §13).

## Risk class

Routine presentation-only change (one page component and one new test file,
synthetic fixtures). No tenant, role, clinical value or schema is involved.
Min-width media queries never change what the server authorizes (ADR-0004).

## Review owner

`smartclinic-senior-react` — simulated, read-only, bounded review loaded with
story ID, paths, acceptance criteria and the observed red/green evidence.
Verdict: **no blocking finding**. It confirmed the tests are non-vacuous and
reach the real entry point, that all new inline styles are logical, and that the
compact wrapper preserves `role=region`, `aria-label` and `tabIndex=0` while
existing consumers of `AdminTableRegion` are untouched. It raised one LOW,
non-blocking note: `responsive-layout.md` §5 permits a labelled medium region
"only when content overflows"; this page never renders one at medium because
logical wrapping and `inline-size: 100%` prevent overflow. Skills loaded by the
primary agent: `smartclinic-delivery`, `smartclinic-senior-react`,
`smartclinic-senior-typescript`, `smartclinic-ux-reviewer`.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.
No synthetic patient record was added to the repository; the test members are
inline placeholders (`synthetic.reception`, long synthetic English and Arabic
display strings).

## Authorization boundaries and audit events

None. No tenant, facility, role or patient-link scope; no audit event added or
changed. `data-breakpoint`/`data-part` are informational markup, not a second
layout or authorization source.

## Migration and rollout considerations

None. The change adds only classes, attributes and logical wrapping styles to an
existing page; no contract, build script, migration or deployment input changed.

## Test evidence

All evidence below is from executed tool commands; the specialist review was
simulated and read-only. No simulated-user, clinician, patient or other human
evidence is claimed.

- `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages in 7s` (fresh worker; `node_modules` absent).
- Red first: `cd apps/staff-web && npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/StaffPage.responsive.test.tsx` → `Test Files  1 failed (1)` / `Tests  6 failed (6)` (missing `data-part`/classes, no compact region, no cell wrapping, no dialog band attribute, Arabic fixture not yet set).
- Green after implementation: `cd apps/staff-web && npx vitest run --
