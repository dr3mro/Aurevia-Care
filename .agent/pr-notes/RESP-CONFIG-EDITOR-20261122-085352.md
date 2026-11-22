# RESP-CONFIG-EDITOR — Staff web: responsive `ConfigEditor` (features-admin)

## Summary

Made `apps/staff-web/src/features/admin/ConfigEditor.tsx` responsive without changing behavior, text, API calls or exported names. The editor now uses the shared `responsive.css` classes and the `useBreakpoint` hook: the outer section is a `.stack` (single column, wrapping children) carrying `data-part`/`data-breakpoint`; the versions table keeps its labelled, keyboard-focusable scroll region and exposes the active band; the configured-value cell is marked and wrapped; the approval/badge row wraps with `flexWrap`; the physical `width: 100%` became logical `inlineSize: 100%`; the config-key line wraps with a logical property. Added a new responsive test file that mocks `matchMedia` for compact/medium/expanded and asserts structure, labels, roles, keyboard order, 16px/coarse-pointer field classes and the RTL case. Synthetic fixtures only; no dependency added and no file outside scope changed.

Entry-point-to-test trace: `ConfigEditor` is imported by `ConfigPage.tsx` and `AdminScreen.tsx` (admin feature routes). The new test renders the component inside `ThemeProvider` + `I18nProvider` — the providers the shell uses — and drives it through the normal `onPropose`/`onApprove` props.

## Affected requirements

WEB-07, UX-01.

## Risk class

Routine (docs/WORKFLOW.md): contained presentation change in one component plus a new test file. No behavior, data, contract, authorization or migration change.

## Review owner

Senior React and Vite (`smartclinic-senior-react` skill loaded); strict TypeScript verified via `npm run typecheck` (`smartclinic-senior-typescript` skill loaded).

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope touched; no audit event added or changed. No real patient data or credentials in code, tests or notes.

## Migration and rollout considerations

None. Presentation-only change; no reserved migration version used.

## Test evidence

Evidence source: executed tool commands (local Vitest, tsc, eslint, Vite build, coverage) — not a reviewer, simulated user or human participant.

- `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`
  → `Test Files  1 passed (1)` / `Tests  6 passed (6)`.
  (Red first: before the component change 5 of 6 tests failed on missing `data-part`/`data-breakpoint`, physical `width: 100%`, and field wrapper attributes.)
- `cd apps/staff-web && node_modules/.bin/vitest run src/features/admin/admin-components.test.tsx`
  → `Test Files  1 passed (1)` / `Tests  26 passed (26)` (existing tests still pass).
- `cd apps/staff-web && bash check.sh` → all stages passed:
  - `gen:api: generated client matches the contract`
  - `npm run typecheck` — clean
  - `npm run lint` — clean
  - `npm run build` + `node scripts/check-bundle-size.mjs` — clean
  - `npm run test:coverage` → `Test Files  159 passed (159)`, `Tests  1897 passed (1897)`.
  - (Console stack traces in the log come from pre-existing negative tests in `I18nProvider.test.tsx` / `ThemeProvider.test.tsx` that intentionally render without a provider; all tests passed.)
- Typecheck final lines (mandatory paste), from `cd apps/staff-web && npm run typecheck`:
  ```
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no diagnostics — every `error TS…` resolved; the first run reported TS2418 at test lines 39-40 and was fixed by typing `bandQueries` with an explicit `{ medium: boolean; expanded: boolean }` shape.)
- Lint final lines, from `npm run lint`:
  ```
  > smartclinic-staff-web@0.1.0 lint
  > eslint . --max-warnings 0
  ```
- `git merge --no-edit origin/main` (start, after implementation, and before finish) → `Already up to date.`; no conflict markers in either scoped file.

Not run: `scripts/check.sh`. It calls `apps/api/check.sh`, which is `mvn -q -B verify` (the full Java/integration gate, >10 minutes) — explicitly forbidden for workers. The canonical frontend gate `bash apps/staff-web/check.sh` was run instead and passed.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (its `npm run test:coverage` step, V8 per-file thresholds, 100% enforced).

Result:
```
No files with missing coverage.
207 files fully covered.
4 empty files skipped.
Statements   : 100% ( 3722/3722 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3556/3556 )
```

`ConfigEditor.tsx` is fully covered. No new executable branch was added to the component — the edits are JSX attributes, a class name, a `useBreakpoint()` call and inline style objects (no `if`/ternary/`&&`/`switch`/`catch`). Existing branches in the component stay covered by the existing suite: empty value and non-calendar date by `admin-components.test.tsx` (`blocks an empty or partial draft…`, `rejects a non-calendar effective date…`), successful sync submit by `proposes a complete local draft`, the promise/`accepted !== false` path by the `ConfigEditor real-API adaptations` tests, empty versions by `shows a defined empty state…`, and the approved/`onApprove`/effective/approver/`effectiveFrom === ''` conditionals by `shows tenant-scoped versions, effective dates, approval and approver`. The new responsive test additionally drives the breakpoint hook through compact → medium → expanded via `matchMedia` change events while checking the draft survives.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of information, critical results, cross-organization sharing or state-machine change. Synthetic fixtures only. All layout claims are simulated DOM assertions; pixel-level checks (320/360/390/768/820/1024/1280 px, 44×44 targets, 200% zoom) remain to the Playwright tasks, per the story (jsdom cannot lay out pixels).

## Findings

- Pre-existing, outside my scope: `HEAD` already contains unresolved conflict markers in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java` (committed from `origin/swarm/BUILD-37-469`). Verified present in `HEAD`, not introduced by this change, and left untouched. The Java/Delivery gate will fail until the owning worker or integrator resolves them.
- `responsive.css` has no dedicated class for a clinical configuration-value cell or a mixed badge+action row, so logical inline styles (`overflowWrap: 'anywhere'`, `minInlineSize: 0`, `flexWrap: 'wrap'`) were used rather than editing the stylesheet, as the story directs.
- `AdminTableRegion` (out of scope) uses inline `overflowX: 'auto'` instead of the shared `.table-region` class; its labelled, keyboard-focusable scroll semantics are otherwise correct and were left unchanged.

## Lessons for workers

- A test under `apps/staff-web/tests/responsive/features-admin/` reaches `src` via `../../../src` (three levels up), not `../../src`.
- `FormField` sets `data-breakpoint` / `data-coarse-pointer` on its wrapping `.stack` div, not on the `<input>`; assert on `input.closest('.stack')`.
- Setting `input.value` directly and dispatching `input` does not trigger React's `onChange`; use `fireEvent.change` to preserve typed state in a resize test.
- With `noUncheckedIndexedAccess`, a `Record<string, boolean>` yields `boolean | undefined` on index access (TS2418 in a computed property). Give breakpoint maps an explicit `{ medium: boolean; expanded: boolean }` shape.
- jsdom serializes logical inline properties (e.g. `padding-inline`, `inline-size`) into the `style` attribute, so logical-only assertions are reliable; pixel geometry is not.
