# RESP-MIXEDSCRIPTTEXT — Staff web: responsive `MixedScriptText` component

## Summary

`MixedScriptText` is a leaf text primitive: a single `<bdi lang dir="auto">` that isolates a mixed-script clinical run. It now reads the existing `useBreakpoint` hook, exposes the active band as `data-breakpoint`, and wraps long English/Arabic values with logical inline styles so the run stays isolated, readable and non-scrolling at 320–1280 px in both directions. Exported names, props, text, API calls and rendered semantics are unchanged. A new responsive test covers compact, medium, expanded, a live breakpoint change, keyboard order and RTL; the existing test is untouched and still passes.

Files changed (scope only):
- `apps/staff-web/src/components/MixedScriptText.tsx`
- `apps/staff-web/tests/components/MixedScriptText.responsive.test.tsx`

## Affected requirements

- **WEB-07** — responsive staff web from 320 px; no horizontal page scroll, no hidden clinical content, logical layout.
- **UX-01** — usable layout at all widths, RTL mirroring of flow but not clinical content, keyboard order, touch/zoom readability.

Design references: `docs/design/responsive-layout.md` (sections 5, 11, 12, 13), `docs/design/rtl-and-localization.md` (sections 1.1, 7), `apps/staff-web/src/theme/responsive.css`, `apps/staff-web/src/theme/useBreakpoint.ts`.

Acceptance mapping:
| Requirement / story item | Evidence in this change |
|---|---|
| Compact / medium / expanded structure and labels | `keeps one isolated, wrapping run and the correct breakpoint at every layout` — asserts `BDI`, `dir=auto`, `lang`, full text and `data-breakpoint` for all three bands |
| Long English and Arabic wrap, no clipping, 200% zoom readable | same test asserts `white-space: normal`, `overflow-wrap: anywhere`, no `display:none`, no fixed pixel height; no font size is set so text scales with reader preference |
| Keyboard order preserved | `does not introduce any tab stop or reorder the run across breakpoints` — DOM order preserved, `tabIndex=-1`, zero focusable elements added |
| Right-to-left case, isolation marks intact | `preserves the mixed-script isolation run and logical styling under right-to-left` — `dir=rtl`/`lang=ar` on document, single `BDI` with `dir=auto`/`lang=ar`, no direction override, no physical side |
| Orientation / breakpoint change keeps text | `follows a viewport change without remounting or altering the clinical text` — fires `matchMedia` changes and asserts the breakpoint updates while text is unchanged |
| No new CSS / logical properties | existing `useBreakpoint` hook reused; wrapping uses logical inline styles because `responsive.css` has no dedicated clinical-value class (recorded under Lessons) |

Items that are not properties of this leaf component and are intentionally delegated: columns stacking on phones (`DataTable`/`Form`), 44×44 coarse-pointer targets (interactive controls / `touch` class), 16 px input text (`field` class), and safe-area insets (shell `page`/`app-shell` classes). This component renders no columns, inputs, touch targets or shell chrome; it does not hide any of them. Pixel/reflow checks belong to the Playwright tasks (responsive-layout.md section 13).

## Risk class

Routine (contained shared-component presentation change). No authorization, data, migration or release behaviour touched (`docs/WORKFLOW.md`).

## Review owner

Senior React and Vite (bounded review completed). The simulated `aurevia-senior-react` specialist reviewed both files plus `useBreakpoint.ts`, `breakpoints.ts`, `responsive.css`, the baseline test and the design docs and returned **no blocking findings**: bidi isolation is correct (`<bdi dir="auto" lang>`), logical inline wrapping matches the design recipe, the component adds no tab stop or direction override, exported names/behaviour are unchanged, `data-breakpoint` follows the existing `StateViews` precedent, and the tests are meaningful. Its three non-blocking observations were informational (no CSS consumer for `data-breakpoint` — explicitly required by the task; `LONG_ARABIC` wrapping assertion; inert `min-inline-size` on an inline box). I addressed the test observation by asserting `whiteSpace`/`overflowWrap` on the long Arabic run in the breakpoint-change test. The reviewer had read-only tools and did not re-run the gates; all pass claims below are from executed tool commands. No clinical/QA sign-off required for presentation-only work; no human or patient-participant evidence is claimed.

## Data changes

None. No migration, backfill, lock or volume impact. No clinical or patient data in fixtures — all strings are synthetic (`ليلى Haddad`, `SYN`-style labels, invented facility text).

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit event changed. The component only renders the text passed to it.

## Migration and rollout considerations

None. No reserved migration version used, no schema or contract change. No rollout action; the change is a presentation detail of an existing exported component and is backwards compatible.

## Test evidence

Evidence source: executed tool commands (Vitest, TypeScript, ESLint, Vite, bundle-size gate, V8 coverage). Reviewer and simulated-user evidence are labelled where present; there is no human or clinical evidence.

Baseline before change: `node_modules/.bin/vitest run --exclude 'e2e/**' tests/components/MixedScriptText.test.tsx` → 1 file / 1 test passed.

Red phase (before implementation): `node_modules/.bin/vitest run --exclude 'e2e/**' tests/components/MixedScriptText.responsive.test.tsx` → **1 file failed, 3 tests failed / 1 passed**; failures were `expected null to be 'compact'` (missing `data-breakpoint`) and `expected '' to be 'normal'` (missing wrapping style).

Green phase: `node_modules/.bin/vitest run --exclude 'e2e/**' tests/components/MixedScriptText.test.tsx tests/components/MixedScriptText.responsive.test.tsx` → **2 files passed, 5 tests passed**.

Mandatory frontend gate — `bash apps/staff-web/check.sh` exits at its first step, `npm run gen:api:check` (`openapi-typescript … --check` → "Generated types are not up-to-date!"). This is pre-existing branch drift from another story's `contracts/openapi.yaml` change (`/api/v1/printing/handouts*` paths and `PrintReason`/`HandoutKind` types); both the contract and `src/api/generated/openapi.d.ts` are outside this task's scope, and `git status` shows them unmodified by me. All remaining `check.sh` steps were therefore run individually and passed:

- `npm run typecheck` → exit 0. Final lines:
  ```
  > aurevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  No `error TS…`.
- `npm run lint` → exit 0. Final lines:
  ```
  > aurevia-staff-web@0.1.0 lint
  > eslint . --max-warnings 0
  ```
- `npm run build` → exit 0 (`✓ built in 168ms`).
- `node scripts/check-bundle-size.mjs` → exit 0 (all chunks `ok`; largest `index-*.js` 52548 bytes gzip, under budget).
- `npm run test:coverage` → exit 0. Final lines:
  ```
  Test Files  139 passed (139)
       Tests  1718 passed (1718)

   % Coverage report from v8
  No files with missing coverage.
  194 files fully covered.
  1 empty file skipped.

  =============================== Coverage summary ===============================
  Statements   : 100% ( 3445/3445 )
  Branches     : 100% ( 1753/1753 )
  Functions    : 100% ( 1176/1176 )
  Lines        : 100% ( 3293/3293 )
  ================================================================================
  ```
  (The `useTheme must be used inside ThemeProvider` console stack traces in this run come from an existing test that intentionally asserts that error; the run is exit 0 with all tests passing.)

Entry-point-to-test trace: screens import `MixedScriptText` (e.g. `src/app/DesignPreview.tsx`, `src/app/AppShell.tsx`, `src/features/patients/PatientOverviewScreen.tsx`, `src/features/chart/LongitudinalChart.tsx`); the full suite renders those screens and passed (1718 tests), proving no behaviour/text regression. The new `tests/components/MixedScriptText.responsive.test.tsx` renders the primitive directly with a deterministic `matchMedia` stand-in for the three bands, so the responsive contract is asserted at the component boundary. Pixel-level checks are left to the Playwright projects per the story.

## Coverage

Command: `npm run test:coverage` (the `test:coverage` step of `apps/staff-web/check.sh`, V8, per-file thresholds 100/100/100/100). Result: 100% statements, branches, functions and lines on all 194 covered files; `No files with missing coverage`.

Touched file from `coverage/coverage-summary.json`:
- `apps/staff-web/src/components/MixedScriptText.tsx` — lines 100%, statements 100%, functions 100%, branches 100%.

New/changed branch audit (AGENTS.md Coverage rules): the production change adds no `if`, ternary, `&&`/`||`, `switch` or `catch`; `useBreakpoint()` is delegated to `src/theme/useBreakpoint.ts`, whose branches are unchanged and already fully covered by existing tests. The single JSX render path is taken by every test that mounts the component (both the existing and the new test files), giving 100% on the changed file. The new test file is excluded from source coverage by the configured `**/*.test.tsx` rule and adds no production code. No threshold, exclusion or skip was changed or added; no test was disabled.

## Clinical and architecture review

Not required: presentation-only, no signed data, cross-organization sharing, patient association, prescribing or critical-result workflow change. Design guidance followed: `docs/design/responsive-layout.md`, `docs/design/rtl-and-localization.md`, `docs/decisions/0004-responsive-staff-web.md`.

## Findings

- **Pre-existing generated-client drift blocks `check.sh` step 1 (tool evidence, out of scope).** `contracts/openapi.yaml` contains `/api/v1/printing/handouts` and `/api/v1/printing/handouts/download` plus `PrintReason`/`HandoutKind` schemas that the checked-in `src/api/generated/openapi.d.ts` lacks (252-line diff against a fresh generation). Neither file is in this task's scope and neither was edited by me; the drift originates in a different story's contract change. Every other `check.sh` step was run manually and passed (see Test evidence).
- **Missing class in `responsive.css` (used logical inline styles instead).** The design guide's `.clinical-value` wrapping rule (`min-inline-size: 0; overflow-wrap: anywhere; white-space: normal`) exists in `docs/design/responsive-layout.md` section 12 but there is no `.clinical-value` class in `apps/staff-web/src/theme/responsive.css`. Per the story I did not edit the stylesheet; I used logical inline styles on the isolation element and recorded the gap under Lessons.
- **Leaf-component scope of responsive acceptance.** Several story items (touch targets, input font size, column stacking, safe-area insets) do not apply to a text-only isolation element; they are owned by the shell, Form and DataTable components and by the Playwright tasks. The component introduces no hidden or fixed-size clinical content and adds no direction override, physical side or focusable element.
- **No clinical/human evidence.** All evidence is tool-generated or from a simulated specialist review; no clinician, patient or unit participant was involved.
- **Unrelated green-suite noise.** `npm run test:coverage` logs expected React error boundaries from `tests/theme/ThemeProvider.test.tsx`; the run is exit 0 with 1718 passing tests.
- **Root `scripts/check.sh` not run by design (worker constraint).** It invokes `apps/api/check.sh` (full Maven/integration verify, >10 min) and `db/check.sh`, which the task forbids for workers. I ran its fast, non-Maven/non-DB steps individually instead. Results: `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline` and `scripts/frontend-check.sh` all exit 0. Two fast steps fail for pre-existing environment reasons unrelated to this scoped TS change: `check-pack.py` reports `opencode.json is stale` (the known cloud-worker CI-profile-under-skip-worktree artifact; `opencode.json` is orchestrator-owned and out of scope), and `scripts/python-coverage.sh` exits 1 on pre-existing Python tooling test failures (its output includes a `TODO.md`/`tracker` worker-gate violation; I did not edit any tracker or `scripts/*.py` — `git status` lists only my two scoped files and this notes file). No generated file, tracker or script was touched.

## Lessons for workers

- When a `check.sh` stops at `gen:api:check` on a staff-web story that does not touch the contract, confirm the drift with a fresh `openapi-typescript` generation to `/tmp` and a `diff`, then run the remaining steps individually. Never regenerate or edit `src/api/generated/openapi.d.ts` from a scoped worker — it belongs to the contract story and causes cross-worker conflicts.
- `responsive.css` has no `.clinical-value` utility despite the design guide naming one; reuse the DataTable/StateViews pattern of logical inline styles (`minInlineSize: 0`, `overflowWrap: 'anywhere'`, `whiteSpace: 'normal'`) and record the missing class instead of editing the shared stylesheet.
- For a breakpoint-aware jsdom test, mirror `DataTable.responsive.test.tsx`: stub `matchMedia` keyed by `BREAKPOINT_MEDIA_QUERIES`, reuse one listeners set per query so the hook's initializer and effect agree, and assert classes/attributes/structure — never painted pixels.
- Adding `useBreakpoint()` to a previously provider-free component is safe: `useMediaQuery` falls back when `matchMedia` is unavailable, so the original no-stub test still passes.

## Why this is not finished

The scoped implementation and its tests are complete and green (5 MixedScriptText tests, 1718 suite tests, 100% coverage on every file, typecheck/lint/build/bundle-size all exit 0). One mandated command cannot complete from this worker's scope:

- Failing command: `bash apps/staff-web/check.sh` → stops at step 1, `npm run gen:api:check` (`openapi-typescript ../../contracts/openapi.yaml --output src/api/generated/openapi.d.ts --check`) → `Generated types are not up-to-date!` (exit 1).
- Most likely cause: pre-existing drift between `contracts/openapi.yaml` (printing/handouts paths and schemas added by another story) and the checked-in `src/api/generated/openapi.d.ts`; both files are outside this story's scope and are unmodified in my working tree.
- What I already tried: generated a fresh copy to `/tmp/opencode/generated-openapi.d.ts` and diffed it (252 lines, all printing-handout paths/schemas); confirmed `git status --short` lists only my two scoped files; ran every remaining `check.sh` step manually — all passed.
- Exact next step: the integrator or the owning printing/contract story regenerates `apps/staff-web/src/api/generated/openapi.d.ts` from `contracts/openapi.yaml` and commits it in its own change; the full `check.sh` will then pass end to end. No action is needed on the MixedScriptText files.
