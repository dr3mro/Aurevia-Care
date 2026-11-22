# RESP-CONFIG-EDITOR — fix pass (issue #723 / PR #722)

## Summary

Fix pass for task `RESP-CONFIG-EDITOR`, continuing from the earlier findings
instead of starting over. On branch `swarm/RESP-CONFIG-EDITOR-876` the responsive
`ConfigEditor` change was already implemented and committed (`1db42e32`) together
with its new responsive test file; fix attempt 1 (`3320649a`) re-verified it and
wrote a hand-off note, but wrote that note to
`.agent/pr-notes/RESP-CONFIG-EDITOR-fix-20261122-091414.md` instead of the path
named in the prompt. The missing artifact was this note at
`.agent/pr-notes/RESP-CONFIG-EDITOR-fix-20261122-092135.md`; that is the reason
the earlier fix did not finish cleanly.

I re-read the two scoped files, the existing admin test, the responsive design
contract, `responsive.css` and `useBreakpoint.ts`, then re-ran the canonical
frontend gate from a clean dependency install. Every stage passed with 100%
coverage. No production code change was required in this fix pass: the scoped
files are already correct and complete, so the only new file here is this note.

Evidence source: **executed tool commands** (Vitest, `tsc --noEmit`, ESLint,
Vite build, bundle-size gate, V8 coverage) run locally by me. **No reviewer,
simulated user or human participant evidence is claimed.** All fixtures are
synthetic (for example `alphaOrganizationId`, `syntheticConfigurations`,
`'Synthetic retained draft'`). No real patient data or credentials appear in
code, tests or notes.

Entry-point-to-test trace: production entry points
`apps/staff-web/src/features/admin/AdminScreen.tsx` and
`ConfigPage.tsx` render `ConfigEditor`; the new test renders that component
inside the shell providers (`ThemeProvider` + `I18nProvider`) and drives it
through its normal `onPropose` / `onApprove` props. Trace:
`AdminScreen`/`ConfigPage` → `ConfigEditor` → `useBreakpoint()` and
`AdminTableRegion`/`Form`/`FormField`/`MixedScriptText` →
`tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`.

## Affected requirements

**WEB-07, UX-01.** Acceptance mapping:

| Story step | Acceptance | Where satisfied |
|---|---|---|
| 1. Read component, existing test, layout doc, stylesheet and hook | Done | `ConfigEditor.tsx`, `admin-components.test.tsx`, `docs/design/responsive-layout.md`, `src/theme/responsive.css`, `src/theme/useBreakpoint.ts` |
| 2. Usable 320–1280 px, LTR/RTL, long strings wrap, columns stack, 44px coarse targets, 16px inputs, safe areas, logical properties, no hidden clinical column, 200% zoom, single-column phone editor | Done | `.stack` single column; `.field`/`.touch` classes on inputs; logical `inlineSize`/`overflowWrap`/`flexWrap` inline styles; `data-breakpoint` on section, table and form; labelled `tabindex=0` `AdminTableRegion`; `data-layout="single-column"` fields; no `display:none` on clinical content. Asserted by the six new responsive tests. Pixel geometry (320/360/390/768/820/1024/1280 px, 44×44, 200% zoom) is deferred to the Playwright tasks because jsdom cannot lay out pixels. |
| 3. Reuse `responsive.css` classes and the hook; use logical inline styles for a missing class; do not edit the stylesheet | Done | `useBreakpoint()`, `.stack`, `.page`, `.field`, `.touch`; missing clinical-value/badge-row classes handled with logical inline styles and recorded under Findings/Lessons. `responsive.css` untouched. |
| 4. New test file mocks `matchMedia` for compact/medium/expanded; structure, labels/roles, keyboard order, RTL | Done | `tests/responsive/features-admin/ConfigEditor.responsive.test.tsx` — 6 tests, including a live compact→medium→expanded change event that proves the typed draft survives (no remount). |
| 5. No change to behaviour, text, API calls or exported names | Done | Diff is JSX attributes, a class name, a `useBreakpoint()` call and inline style objects. No prop, handler, message key or export changed. |

## Risk class

**Routine** (`docs/WORKFLOW.md`): a contained presentation change in one
component plus one new test file. No behavior, data, contract, authorization or
migration change.

## Review owner

Senior React and Vite (`smartclinic-senior-react` skill loaded); strict
TypeScript verified with `npm run typecheck` (`smartclinic-senior-typescript`
skill loaded). No clinical or defensive-security review required (see Clinical
and architecture review).

## Data changes

None. No migration, backfill, lock/volume impact, rollback or forward-fix; no
reserved migration version used.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope touched; no audit event
added or changed.

## Migration and rollout considerations

None. Presentation-only change.

## Test evidence

All evidence is from **executed tool commands**; no reviewer, simulated user or
human participant. Commands run from `apps/staff-web` after `npm ci` installed
the pinned dependencies (the module `check.sh` performs this install
automatically when `node_modules/.bin/vitest` is absent).

- `node_modules/.bin/vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx`
  → `Test Files  1 passed (1)` / `Tests  6 passed (6)` (exit 0).
  (Red-first evidence from the first pass: before the component change, 5 of 6
  tests failed on missing `data-part`/`data-breakpoint`, physical `width: 100%`
  and field-wrapper attributes.)
- `bash check.sh` → every stage passed under `set -e` (exit 0):
  - `gen:api: generated client matches the contract`
  - `npm run typecheck` — clean
  - `npm run lint` — clean
  - `npm run build` — clean
  - `node scripts/check-bundle-size.mjs` — all assets `ok`
  - `npm run test:coverage` → `Test Files  159 passed (159)` / `Tests  1897 passed (1897)`.
    (Console stack traces in that log come from pre-existing negative tests in
    `ThemeProvider.test.tsx` / `I18nProvider.test.tsx` that intentionally render
    without a provider; every test passed.)

Individual checks, with final lines and captured exit codes:

- `npm run gen:api:check` (exit 0):
  ```
  > smartclinic-staff-web@0.1.0 gen:api:check
  > bash scripts/gen-api-types.sh --check

  ✨ openapi-typescript 7.4.2
  gen:api: generated client matches the contract
  ```
- `npm run typecheck` (mandatory paste; exit 0, no diagnostics — every
  `error TS…` resolved):
  ```
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
- `npm run lint` (exit 0):
  ```
  > smartclinic-staff-web@0.1.0 lint
  > eslint . --max-warnings 0
  ```

- `git merge --no-edit origin/main` at the start of this pass →
  `Already up to date.` No conflict markers in either scoped file
  (`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/staff-web` returned none).

Not run: `scripts/check.sh`. It invokes `apps/api/check.sh`
(`mvn -q -B verify`, the full Java/integration gate), which workers are
explicitly forbidden to run because it exceeds the time budget. The canonical
frontend gate `bash apps/staff-web/check.sh` was run and passed; the integrator
and Delivery run the full gate.

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

`ConfigEditor.tsx` is fully covered. **No new executable branch was added** by
the responsive change — the edits are JSX attributes, a class name, a
`useBreakpoint()` call and inline style objects (no `if` / ternary / `&&` /
`switch` / `catch`). The pre-existing branches in the component remain covered
by `admin-components.test.tsx` and the new responsive test:

| Branch in `ConfigEditor.tsx` | Covering test |
|---|---|
| empty proposed value (`proposedValue.length === 0`) | `admin-components.test.tsx` — `blocks an empty or partial draft…` |
| invalid civil date (`!isValidCivilDate`) | `admin-components.test.tsx` — `rejects a non-calendar effective date…` |
| synchronous `onPropose` returning `false` vs a value (`outcome !== false`) | `admin-components.test.tsx` — `proposes a complete local draft` and the rejection cases |
| promise outcome (`outcome instanceof Promise`) and `accepted !== false` vs `false` | `ConfigEditor real-API adaptations` tests in `admin-components.test.tsx` |
| no versions (`versions.length === 0`) vs mapped rows | `admin-components.test.tsx` — `shows a defined empty state…` |
| `entry.effectiveFrom === ''` vs a date | `shows tenant-scoped versions, effective dates, approval and approver` |
| `entry.approved` vs pending, `onApprove === undefined` vs a handler, and `effective && entry.version === effective.version` vs null | `shows tenant-scoped versions, effective dates, approval and approver` plus the new `keeps approve actions reachable in a stable keyboard order` |
| responsive bands compact / medium / expanded (from `useBreakpoint`) | new test `keeps one labelled, single-column, keyboard-scrollable structure at every band` and `follows a viewport change without remounting or losing the typed draft` |
| coarse pointer branch inside `FormField` | new test `stacks both draft fields with 16px text and coarse-pointer targets` |

The new test file contains no production code, so it adds no coverage
obligation of its own. No threshold, exclusion or test skip was added.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of
information, critical results, cross-organization sharing or state-machine
change. Synthetic fixtures only. All layout claims are simulated DOM
assertions; pixel-level checks remain to the Playwright tasks, as the story
directs.

## Findings

- **Fix-pass scope:** the implementation and its test were already committed
  (`1db42e32`) when this run started. The only reason the previous fix pass did
  not finish cleanly is that its note was written to
  `RESP-CONFIG-EDITOR-fix-20261122-091414.md` rather than the exact path in the
  prompt. This note corrects that; no defect was found in the scoped code.
- **Pre-existing, outside scope:** `HEAD` still contains unresolved conflict
  markers (two per file) in
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java` (from `origin/swarm/BUILD-37-469`). I
  verified they are present in `git show HEAD:<path>`, so they are not introduced
  by this story, and left them untouched. The Java/Delivery gate will fail until
  the owning worker or integrator resolves them; treat that as a separate repair,
  not a defect of this story.
- `responsive.css` has no dedicated class for a clinical configuration-value
  cell or a mixed badge+action row, so logical inline styles
  (`overflowWrap: 'anywhere'`, `minInlineSize: 0`, `flexWrap: 'wrap'`) were used
  rather than editing the stylesheet, as the story directs.
- `AdminTableRegion` (out of scope) uses inline `overflowX: 'auto'` rather than
  the shared `.table-region` class; its labelled, keyboard-focusable scroll
  semantics are otherwise correct and it was left unchanged.
- No new dependency was added. `npm ci` from the committed lockfile installed
  the test dependencies locally.

## Lessons for workers

- A fix pass may find the work already committed. Read the scoped files, the
  first-pass note and `git log` before editing — verify and finalize rather than
  redoing the implementation.
- **Write the hand-off note to the exact path in the prompt.** The first two
  passes used near-miss filenames and the task kept being returned for repair;
  the path `<task-id>-fix-<timestamp>.md` in the prompt wins over any generic
  `<task-id>.md` wording.
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
  when `node_modules/.bin/vitest` is absent; a missing `node_modules` is not a
  broken checkout.
