# RESP-APPOINTMENT-WORKLIST — responsive `AppointmentWorklist`

## Summary

Made the scheduling worklist adapt from 320 px phones to 1280 px desktops in
English/LTR and Arabic/RTL without changing behaviour, text, API calls or
exported names. One real `<table>` (caption, six `<th scope="col">`, row headers)
now exists at every band; on compact phones each row is styled as a stacked card
inside a labelled, keyboard-scrollable region. Long English and Arabic values
wrap with logical inline styles; the native select gets the existing `.field`
(16 px text) and `.touch` (44 px coarse target) classes. No stylesheet, shared
component, dependency, data or clinical column was changed or hidden.

## Affected requirements

- WEB-07 — supported clients / responsive staff web (decision 0004 supersedes the old 768 px floor in code).
- UX-01 — English/Arabic, RTL, mixed-script isolation, WCAG 2.2 AA structural support.

Entry point to test trace: `SchedulingScreen.tsx:88` renders `AppointmentWorklist`
→ `AppointmentWorklist.tsx` chooses the compact card layout via `useBreakpoint()`
→ `tests/responsive/features-scheduling/AppointmentWorklist.responsive.test.tsx`
asserts cards/region on compact, plain table on medium/expanded, all six columns,
keyboard order, empty state, breakpoint transitions, long strings and RTL.

## Risk class

Routine (contained UI presentation change, no data, authorization or contract
change). One bounded advisory review was routed to `smartclinic-senior-react`;
its three findings (single DOM table, two `if (compact)` blocks, `.card-list` on
tbody) were adopted. Simulated reviewer only; no human or clinical review.

## Review owner

`smartclinic-senior-react` (advisory plan, no blocking findings). Strict
TypeScript/lint/build checks are recorded below.

## Data changes

None. No migration, backfill, lock or volume impact; rollback is a plain revert
of the two scoped files.

## Authorization boundaries and audit events

None changed. Tenant-scoped data still arrives as the `appointments` prop from
`SchedulingScreen`; the component adds no fetch, no write and no new audit event.
Row keys still include the organization id so identical tenant-local ids never
merge. Range buttons and row callbacks are unchanged.

## Migration and rollout considerations

None. Presentation-only change; no deployment or release step beyond the normal
scoped commit.

## Test evidence

Evidence source: executed tool commands unless labelled *simulated reviewer*.

- Red first (before the component change):
  `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-scheduling/AppointmentWorklist.responsive.test.tsx --reporter=dot`
  → `Test Files 1 failed (1); Tests 7 failed (7)` (no compact region/card structure existed).
- Green after implementation (and again after the final `origin/main` merge):
  `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-scheduling/AppointmentWorklist.responsive.test.tsx src/features/scheduling/components.test.tsx --reporter=dot`
  → `Test Files 2 passed (2); Tests 21 passed (21)` (eight new responsive tests plus all thirteen existing component tests; an earlier run before the seven-width matrix had 20).
- Canonical module gate:
  `bash apps/staff-web/check.sh` → `gen:api: generated client matches the contract`;
  typecheck, lint, build and bundle-size gates all exited 0;
  `Test Files 159 passed (159); Tests 1899 passed (1899)`;
  `No files with missing coverage. 207 files fully covered.`;
  `Statements 100% (3732/3732), Branches 100% (1896/1896), Functions 100% (1258/1258), Lines 100% (3566/3566)`.
  (Console "useI18n/useTheme must be used inside provider" messages come from the
  existing provider error-path tests and are not failures.)
- Typecheck final lines (requested by the story):
  `cd apps/staff-web && npm run typecheck` →
  `> smartclinic-staff-web@0.1.0 typecheck`
  `> tsc --noEmit -p tsconfig.json`
  then exit code 0 with no `error TS…` output.
- `git merge --no-edit origin/main` → `Already up to date.` (run at start, after implementation and before finishing); no conflict markers in the two scoped files.
- Fast policy checks: `python3 -I scripts/check-names.py`, `check-secrets.py`, `check-docs.py` → all exit 0.
- `python3 -I scripts/check-pack.py` exited 1 with `opencode.json is stale` — verified as the documented CI-profile false positive: `git ls-files -v opencode.json` → `S` (skip-worktree), worktree file equals `.github/opencode.ci.json`, HEAD bytes differ, and `python3 -I scripts/gen-opencode.py --check` → exit 0 with the committed HEAD bytes restored. The worktree file was restored unchanged; no config file was edited.

jsdom cannot lay out pixels: tests assert classes, attributes, roles and DOM
structure only. Painted 320/360/390/768/820/1024/1280 px, 200%/400% zoom,
safe-area and real coarse-pointer measurements remain with the Playwright tasks
(RESP-E2E-RESPONSIVE-LAYOUT / TOUCH-TARGETS / REFLOW-AND-ZOOM / RIGHT-TO-LEFT).

## Coverage

Command: `bash apps/staff-web/check.sh` (runs `npm run test:coverage` with
per-file thresholds of 100 for lines, branches, functions and statements).
Result: 100% bundle-wide, and for the touched component
`AppointmentWorklist.tsx` — lines 24/24, branches 10/10, functions 8/8,
statements 25/25 (from `coverage/coverage-summary.json`, tool evidence).

New branches and the test that takes each side:

| Branch | True side | False side |
|---|---|---|
| `if (compact)` layout block: set `card-list` on tbody, `cardStyle` stacking, `layout='cards'` | `shows compact rows as labelled stacked cards…` asserts `.card-list` on tbody, `flex-direction: column`, `padding-inline`, `data-layout="cards"` | `uses a plain full table…` and `maps each supported width…` assert empty tbody class, `data-layout="table"`, no card styling |
| `if (compact)` region wrapper: `role="region"`, `aria-label`, `tabIndex=0` | `shows compact rows…` and `keeps the same keyboard order…` assert the region is the third tab stop | `uses a plain full table…` and `maps each supported width…` assert `queryByRole('region')` is null |
| `appointments.length === 0` (existing) | `keeps the range toggle reachable and preserves the defined empty state on compact` + medium empty render | every populated render, including the seven-width matrix |
| `appointment.state !== 'booked'` (existing) | `keeps the same keyboard order…`: first two check-in buttons disabled | same test: third button enabled and click reports `apt-001` |
| `appointment.assignee ?? text('unassigned')` (existing) | populated day fixture has a checked-in row with an assignee | other rows show `Unassigned` and select value `''` |
| `isTerminal(appointment.state)` (existing) | existing `components.test.tsx` terminal-row test (apt-005 select disabled) | `keeps the same keyboard order…` asserts all three day selects enabled |

No threshold, exclusion, skip or generated-file rule was changed.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, prescribing, critical
results or state-machine change. UX-01 accessibility is asserted structurally
(roles, names, logical properties, no hidden field, Arabic isolation); real
screen-reader, Arabic-clinician and painted zoom checks remain launch gates.

## Findings

- Pre-existing, outside task scope (tool evidence): committed `origin/main`
  contains unresolved conflict markers in `Hl7ParseException.java:6/10`,
  `Hl7Parser.java:14/18` and `Hl7Segment.java:10/14` under
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/`. The merge was
  "Already up to date", so these arrived from another worker's commit, not from
  this change. They were not edited here; the integrator/owner should resolve
  them before the Java gate runs.
- Safe-area insets are owned by the shell's `.app-shell`/`.page` logical padding in `responsive.css` (covered by `tests/theme/responsiveStylesheet.test.ts`); this worklist deliberately does not add a second inset or a `1200px` reading cap, because tables/worklists use the full content column (design `responsive-layout.md` section 3).
- No dedicated card-row utility exists in `responsive.css`: `.card-list > *`
  styles direct children but cannot stack a table row's cells. Per story rule 3
  the stylesheet was not edited; logical inline styles supply the stacking
  (recorded as a lesson below).
- `scripts/check.sh` was deliberately not run: it invokes `apps/api/check.sh`
  (full Maven verify/OWASP) and all integration suites, which the execution
  request forbids for a worker and which cannot finish in the time budget. The
  canonical frontend gate `apps/staff-web/check.sh` was run instead.

## Lessons for workers

- `.card-list > *` in `responsive.css` only borders/pads direct children; with a
  real table, put the class on `<tbody>` so each `<tr>` becomes a card, and stack
  its cells with logical inline styles (`display: flex`, `flexDirection: column`,
  `paddingInline`, `minInlineSize: 0`, `overflowWrap: anywhere`). A missing
  dedicated "table-row card / 44px native select" utility class should be raised
  in a stylesheet story, not added by a component worker.
- In a jsdom matchMedia stub, answer `false` to **both** `BREAKPOINT_MEDIA_QUERIES.medium` and
  `.expanded` for compact; a missing query makes `useBreakpoint()` fall back to
  `expanded`, so compact tests silently exercise the wrong branch.
- `flexDirection: 'column'` serialises to `flex-direction: column`, so a plain
  `/direction\s*:/` RTL assertion gives a false positive; anchor it with
  `/(?:^|;)\s*direction\s*:/`.
- `screen.getByRole('table', …).closest('div[style]')` is a stable way to reach
  the worklist wrapper only if the compact region itself has no inline `style`
  (classes and data attributes are enough).
- Testing Library's `render()` returns `RenderResult`, not `ReactElement`; type
  test helpers with `import type { RenderResult } from '@testing-library/react'`
  so `tsc --noEmit` passes on the first run.

No human, clinical or patient-participant evidence is claimed. All fixtures are
synthetic; no credential or patient datum appears in this note.
