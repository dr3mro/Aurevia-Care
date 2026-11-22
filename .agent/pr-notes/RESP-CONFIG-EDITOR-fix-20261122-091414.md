# RESP-CONFIG-EDITOR — fix pass (hand-off from issue #723 / PR #722)

## Summary

This is fix attempt 1 of 3 for task `RESP-CONFIG-EDITOR`, continuing from the
earlier findings rather than starting over. On this branch the responsive
`ConfigEditor` change was already implemented and committed (`1db42e32`) with its
new responsive test. I re-read the implementation, the existing tests and the
responsive layout contract, then re-ran the canonical frontend gate from a clean
dependency install: every stage passed with 100% coverage. No production code
change was required in this fix pass; the scoped files are correct and complete.
The missing artifact was this hand-off note at the fix-pass path required by the
prompt (the first pass wrote `RESP-CONFIG-EDITOR-20261122-085352.md`).

Evidence source: **executed tool commands** (Vitest, `tsc --noEmit`, ESLint,
Vite build, bundle-size gate, V8 coverage) run locally by me. No reviewer,
simulated user or human participant evidence is claimed. All fixtures are
synthetic (`alphaOrganizationId`, `syntheticConfigurations`).

Entry-point-to-test trace: production entry points
`apps/staff-web/src/features/admin/AdminScreen.tsx:109` and
`ConfigPage.tsx:86` render `ConfigEditor`. The new test renders that component
inside the shell providers (`ThemeProvider` + `I18nProvider`) and drives it
through its normal `onPropose` / `onApprove` props.

## Requirements and acceptance mapping

Requirements: **WEB-07, UX-01**. Risk class: **Routine** (contained presentation
change in one component plus a new test file; no data, contract, authorization
or migration change — `docs/WORKFLOW.md`).

| Story step | Acceptance | Where satisfied |
|---|---|---|
| 1. Read component, test, layout doc, stylesheet, hook | Done | `ConfigEditor.tsx`, `admin-components.test.tsx`, `docs/design/responsive-layout.md`, `src/theme/responsive.css`, `src/theme/useBreakpoint.ts` |
| 2. Usable 320–1280 px, LTR/RTL, wrapping, stacking, 44px targets, 16px inputs, safe areas, logical properties, no hidden column, 200% zoom | Done | `.stack` single column; `.field`/`.touch` classes; `inlineSize`/`overflowWrap`/`flexWrap` logical inline styles; `data-breakpoint` on section and table; `AdminTableRegion` labelled `tabindex=0` region; no `display:none` on clinical content. Asserted by the six new responsive tests. Pixel geometry is deferred to the Playwright tasks (jsdom cannot lay out pixels). |
| 3. Reuse `responsive.css` classes and hook; use logical inline styles for missing classes; do not edit stylesheet | Done | `useBreakpoint()`, `.stack`, `.field`, `.touch`, `.page`; missing clinical-value/badge-row classes handled with logical inline styles and recorded under Findings/Lessons. `responsive.css` untouched. |
| 4. New test file mocks `matchMedia` for compact/medium/expanded; structure, labels/roles, keyboard order, RTL | Done | `tests/responsive/features-admin/ConfigEditor.responsive.test.tsx` — 6 tests, including a live compact→medium→expanded change event that proves the typed draft survives (no remount). |
| 5. No change to behaviour, text, API calls or exported names | Done | Diff is JSX attributes, a class name, a `useBreakpoint()` call and inline style objects. No prop, handler, message key or export changed. |

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope touched; no audit event
added or changed. No real patient data or credentials in code, tests or notes.

## Migration and rollout considerations

None. Presentation-only change; no reserved migration version used.

## Test evidence

Commands run from `apps/staff-web` after `npm ci --no-audit --no-fund`
(`added 486 packages in 7s`). All evidence is from executed tools.

- `node_modules/.bin/vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`
  → `Test Files  1 passed (1)` / `Tests  6 passed (6)`.
- `bash check.sh` → ran every stage to completion under `set -e`:
  - `gen:api: generated client matches the contract`
  - `npm run typecheck` — clean
  - `npm run lint` — clean
  - `npm run build` — `✓ built in 167ms`
  - `node scripts/check-bundle-size.mjs` — all assets `ok`
  - `npm run test:coverage` → `Test Files  159 passed (159)` / `Tests  1897 passed (1897)`.
  (Console stack traces in the coverage log come from pre-existing negative
  tests in `ThemeProvider.test.tsx` / `I18nProvider.test.tsx` that intentionally
  render without a provider; all tests passed.)
- Final lines, run individually with exit codes captured (`exit: 0` for each):
  - `npm run gen:api:check`:
    ```
    > smartclinic-staff-web@0.1.0 gen:api:check
    > bash scripts/gen-api-types.sh --check
    ✨ openapi-typescript 7.4.2
    gen:api: generated client matches the contract
    ```
  - `npm run typecheck` (mandatory paste):
    ```
    > smartclinic-staff-web@0.1.0 typecheck
    > tsc --noEmit -p tsconfig.json
    ```
    No diagnostics — every `error TS…` resolved.
  - `npm run lint`:
    ```
    > smartclinic-staff-web@0.1.0 lint
    > eslint . --max-warnings 0
    ```
  - `npm run build` → `✓ built in 167ms`; `node scripts/check-bundle-size.mjs`
    → every listed asset `ok` (including `useBreakpoint-teauotZi.js 367 ok`).
- `git merge --no-edit origin/main` (at the start of this fix pass) →
  `Already up to date.` No conflict markers in either scoped file
  (`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/staff-web` returned nothing).

Not run: `scripts/check.sh`. It invokes `apps/api/check.sh` (`mvn -q -B verify`,
the full Java/integration gate, >10 minutes), which workers are explicitly
forbidden to run. The canonical frontend gate `bash apps/staff-web/check.sh`
was run and passed. The integrator and Delivery run the full gate.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (its `npm run test:coverage` step;
V8 per-file thresholds at 100%, enforced by the module's own config).

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

`ConfigEditor.tsx` is fully covered. No new executable branch was added to the
component — the edits are JSX attributes, a class name, a `useBreakpoint()`
call and inline style objects (no `if` / ternary / `&&` / `switch` / `catch`).
Existing branches remain covered by `admin-components.test.tsx`: empty value
(`blocks an empty or partial draft…`) and non-calendar date
(`rejects a non-calendar effective date…`), successful sync submit
(`proposes a complete local draft`), the promise / `accepted !== false` path
(`ConfigEditor real-API adaptations`), empty versions
(`shows a defined empty state…`), and the approved / `onApprove` / effective /
approver / `effectiveFrom === ''` conditionals
(`shows tenant-scoped versions, effective dates, approval and approver`). The
new responsive test additionally drives the breakpoint hook through compact →
medium → expanded via `matchMedia` change events while asserting the draft
survives. The new test file contains no production code, so it adds no coverage
obligation of its own.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of
information, critical results, cross-organization sharing or state-machine
change. Synthetic fixtures only. All layout claims are simulated DOM
assertions; pixel-level checks (320/360/390/768/820/1024/1280 px, 44×44 targets,
200% zoom) remain to the Playwright tasks, as the story directs.

## Findings

- **Fix-pass scope:** the implementation and its test were already committed
  (`1db42e32`) when this run started. I verified rather than rewrote them; the
  only new file in this pass is this note. No defect was found in the scoped
  code.
- **Pre-existing, outside scope:** `HEAD` still contains unresolved conflict
  markers in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java` (from `origin/swarm/BUILD-37-469`).
  Verified present in `HEAD`, not introduced by this change, and left untouched.
  The Java/Delivery gate will fail until the owning worker or integrator
  resolves them — the integrator should treat that as a separate repair, not a
  defect of this story.
- `responsive.css` has no dedicated class for a clinical configuration-value
  cell or a mixed badge+action row, so logical inline styles
  (`overflowWrap: 'anywhere'`, `minInlineSize: 0`, `flexWrap: 'wrap'`) were used
  rather than editing the stylesheet, as the story directs.
- `AdminTableRegion` (out of scope) uses inline `overflowX: 'auto'` instead of
  the shared `.table-region` class; its labelled, keyboard-focusable scroll
  semantics are otherwise correct and were left unchanged.
- `scripts/check.sh` was deliberately not run for the reason given under Test
  evidence; no coverage threshold, exclusion or test skip was changed.

## Lessons for workers

- A fix pass may find the work already committed. Read the scoped files, the
  first-pass note and `git log` before editing — verify and finalize instead of
  redoing the implementation.
- The hand-off note path in the prompt wins over any generic `<task-id>.md`
  wording; for a fix pass use `<task-id>-fix-<timestamp>.md`.
- A test under `apps/staff-web/tests/responsive/features-admin/` reaches `src`
  via `../../../src` (three levels up), not `../../src`.
- `FormField` sets `data-breakpoint` / `data-coarse-pointer` on its wrapping
  `.stack` div, not on the `<input>`; assert on `input.closest('.stack')`.
- Setting `input.value` directly and dispatching `input` does not trigger
  React's `onChange`; use `fireEvent.change` to preserve typed state in a resize
  test.
- With `noUncheckedIndexedAccess`, a `Record<string, boolean>` yields
  `boolean | undefined` on index access (TS2418 in a computed property). Give
  breakpoint maps an explicit `{ medium: boolean; expanded: boolean }` shape.
- jsdom serializes logical inline properties (e.g. `padding-inline`,
  `inline-size`) into the `style` attribute, so logical-only assertions are
  reliable; pixel geometry is not.
- `apps/staff-web/check.sh` self-installs pinned dependencies with `npm ci`
  when `node_modules/.bin/vitest` is absent; a fresh worker should not treat a
  missing `node_modules` as a broken checkout.
