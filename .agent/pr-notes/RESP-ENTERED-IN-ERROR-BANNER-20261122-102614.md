# RESP-ENTERED-IN-ERROR-BANNER — Staff web: responsive `EnteredInErrorBanner`

## Summary

Made the chart entered-in-error alert responsive for the staff web at 320–1280 px in both LTR and RTL without changing behavior, text, API calls or exported names. The banner now exposes the active band via `useBreakpoint()` (`data-breakpoint`), opts into the existing `page` utility for full-column, safe-area-aware logical padding, and uses `grid-2` on the clinical field list so fields stack on a phone and sit side by side from the medium band up. Long English/Arabic values wrap with logical inline styles; no clinical field is hidden and no fixed block size or physical side is introduced. Added a dedicated responsive test file that drives `matchMedia` for compact, medium and expanded and asserts structure, labels, roles, keyboard order and RTL.

## Affected requirements

- WEB-07 — responsive staff web from 320 px upward; no data or action is removed at a breakpoint.
- UX-01 — keyboard/screen-reader behavior, logical properties, RTL mirroring, wrapping and zoom-safe text.

Acceptance mapping:
- 320/360/390/768/820/1024/1280 px LTR+RTL → parameterized `describe.each(['compact','medium','expanded'])` with synthetic `matchMedia` answers for the two `min-width` queries; pixel checks are explicitly left to the Playwright tasks (design guide §13).
- No horizontal scroll, wrapping long EN/AR, no hidden field → `page` + `grid-2` classes, `overflow-wrap: anywhere`, `white-space: normal`, `min-inline-size: 0`, `inline-size: 100%`; asserted in the wrapping test and by the "no `display: none`" loop.
- Stacked phone fields → `grid-2` class on the `<dl>` (single column below 768 px, two columns from 768 px in `responsive.css`); asserted at every band.
- Coarse 44×44 targets and 16 px inputs → the banner has no interactive target or input; the keyboard-order test asserts `container.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')` is empty at every band.
- Safe-area insets and logical properties → `page` utility supplies mapped `--safe-*` logical padding; the wrapping test asserts no inline physical padding and no physical side property.
- 200% zoom readable → no fixed block size or truncation; asserted by the no-`height: <n>px` / no-`display: none` checks.
- RTL → Arabic copy and `chartText('ar', …)` labels with document `dir="rtl"`, `lang="ar"`, `text-align: start`, no direction override or physical side.

## Risk class

Routine — contained, presentation-only change to one staff-web component plus a new test. Behavior, text, API calls and exported names are unchanged. Routed to Senior React/Vite and Senior TypeScript; UX reviewer lens applied to RTL and keyboard behavior.

## Review owner

Senior React/Vite (bounded read-only review requested); UX reviewer lens for RTL and keyboard order. No clinical sign-off required for synthetic presentation-only evidence.

## Data changes

None. No migration, backfill, table, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit behavior changed. No PHI, credential or real identifier appears in the code or tests — all fixtures are synthetic (`synthetic.clinician`, `synthetic.referrer.*`, Arabic synthetic strings).

## Migration and rollout considerations

None beyond the normal scoped frontend release. `responsive.css` is already imported globally by `apps/staff-web/src/main.tsx`, so both utilities are present in every build.

## Test evidence

Entry point to test trace: `EnteredInErrorBanner` is rendered by `apps/staff-web/src/features/chart/NoteEditor.tsx` (when `revision.enteredInError` is set), which is rendered by `NotePage`/`ChartScreen`; the new test renders the component directly through the real `ThemeProvider` and `I18nProvider` with a mocked `matchMedia` and asserts the `role="alert"` structure at each band.

- Red (before implementation): `cd apps/staff-web && npx vitest run tests/responsive/features-chart/EnteredInErrorBanner.responsive.test.tsx`
  - final lines: `Test Files  1 failed (1)` / `Tests  9 failed | 3 passed (12)`; first failure `AssertionError: expected null to be 'compact'` on `data-breakpoint`.
- Green (targeted): `cd apps/staff-web && npx vitest run tests/responsive/features-chart/EnteredInErrorBanner.responsive.test.tsx src/features/chart/components.test.tsx`
  - final lines: `Test Files  2 passed (2)` / `Tests  29 passed (29)` — existing `EnteredInErrorBanner` tests in `components.test.tsx` still pass.
- Full module gate: `bash apps/staff-web/check.sh`
  - final lines: `Test Files  159 passed (159)` / `Tests  1903 passed (1903)`; `No files with missing coverage.` / `207 files fully covered.` / `4 empty files skipped.`; coverage summary `Statements 100% (3722/3722)`, `Branches 100% (1892/1892)`, `Functions 100% (1258/1258)`, `Lines 100% (3556/3556)`.
  - The stderr stack traces in this run come from the pre-existing fail-closed `useI18n`/`useTheme` tests (`tests/i18n/I18nProvider.test.tsx`, `tests/theme/ThemeProvider.test.tsx`) and did not fail the run; all 1903 tests passed.
- Typecheck final lines (`cd apps/staff-web && npm run typecheck`, exit 0):
  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  No `error TS…` output. Lint (`npm run lint`) exit 0. `gen:api:check`, `npm run build` and `scripts/check-bundle-size.mjs` all passed inside the full module gate.
- Post-review re-verification: applied the three specialist findings below, then re-ran `cd apps/staff-web && npx vitest run tests/responsive/features-chart/EnteredInErrorBanner.responsive.test.tsx src/features/chart/components.test.tsx` → `Test Files 2 passed (2)` / `Tests 29 passed (29)`, and the full `bash apps/staff-web/check.sh` again → exit 0, `Test Files 159 passed (159)` / `Tests 1903 passed (1903)` with the same 100% coverage numbers.
- `python3 -I scripts/check-names.py` → exit 0; `git diff --check` → exit 0.
- `git merge --no-edit origin/main` → `Already up to date.` (run at start and again before finishing; no conflict markers in the changed files).
- `bash scripts/check.sh` → exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Confirmed once as the documented worker-image false positive (AGENTS.md / OPS-CW-003-01): `git ls-files -v opencode.json` → `S` (skip-worktree); the committed local profile matches `gen_opencode.render(...)` exactly, while the worktree file is the CI runtime profile. The committed file was not edited. `python3 -I scripts/gen-opencode.py --check-runtime` → exit 0.
- Remaining `check.sh` stages run individually: `check-docs.py`, `check-secrets.py`, `check-wiring.py`, `check-migrations.py --require-baseline`, `frontend-check.sh` → each exit 0. `bash scripts/python-coverage.sh` → exit 1 for worker-image limits only: `python coverage: WARNING coverage is not installed`, six `test_check_pack.*` failures caused by the skip-worktree CI profile and two errors in `test_worker_gate.MainTest`/`test_worker_gate_coverage.MainTest` from git/gh sandbox fixtures; final line `FAILED (failures=6, errors=2)`. No failed test touches the changed files.

## Coverage

Command: `bash apps/staff-web/check.sh` (Vitest v8, `vite.config.ts` thresholds: per-file lines/branches/functions/statements = 100, `include: src/**/*.{ts,tsx}`).
Result: `Statements 100% (3722/3722)`, `Branches 100% (1892/1892)`, `Functions 100% (1258/1258)`, `Lines 100% (3556/3556)`, `207 files fully covered` including `src/features/chart/EnteredInErrorBanner.tsx`. The new test file lives under `tests/` and is outside the production coverage denominator by the existing documented rule.

New branches in changed production code: none. The component has no conditional, ternary, `catch` or optional chain; the only new expression is `data-breakpoint={useBreakpoint()}`, whose three possible values are asserted by the parameterized tests (`compact`, `medium`, `expanded`) in both the structure and RTL cases. The existing `useBreakpoint` hook was not modified and keeps its own 100% coverage. No threshold, exclusion or skip was added; no test was disabled.

## Clinical and architecture review

Presentation-only staff-web change; no signed data, release, prescribing, tenant policy or critical-result behavior. No architecture boundary, contract or migration changed. RTL evidence is simulated with synthetic Arabic text and is not a substitute for the Arabic-speaking clinician launch gate (design guide §13); recorded as simulated evidence only.

## Findings

- Unrelated to this change: `main` at `HEAD 8c8b589` contains committed Git conflict markers in three API files — `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java` — e.g. `<<<<<<< HEAD` / `>>>>>>> origin/swarm/BUILD-37-469`. Verified with `git grep -nE '^(<<<<<<<|>>>>>>>) '` and `git show HEAD:…/Hl7Parser.java`. These are outside this task's scope and were not touched; the integrator/owner should repair them before relying on a Java build.
- Bounded read-only Senior React review (`orevia-senior-react`) found no blocking issue in the production component. It reported three test-only findings, all applied: (1) replaced the brittle `alert.style.minInlineSize === '0'` read with a serialized `min-inline-size:\s*0(?:px)?` assertion; (2) removed the unreachable `?? VIEWPORTS.compact` fallback in `renderBanner`; (3) hardened the "no hidden field" check by asserting no `.hide-compact` or `.only-compact` element at every band. The reviewer could not execute commands in its sandbox; all checks were run by this worker before and after the fixes. No task outside the two scoped files is needed.

## Lessons for workers

- `responsive.css` has no dedicated banner/clinical-value class and no `.page > *` wrapping rule (only `.grid-2 > *` sets `min-inline-size: 0; overflow-wrap: anywhere`). Per the story rule, the stylesheet was not edited: wrapping uses logical inline styles (`overflowWrap`, `whiteSpace`, `minInlineSize`). A future responsive story could add a documented `.clinical-value` utility.
- Do not add an inline `padding` shorthand to an element that opts into the `page` utility: it silently overrides the safe-area-aware logical padding the class provides. Delegate spacing to the class.
- In jsdom, inline `minInlineSize: 0` is serialized as `'0'`, not `'0px'` — and cssstyle normalization varies, so assert the serialized style declaration with a permissive `0(?:px)?` pattern instead of pinning a property value. `getAllByRole('term')` / `('definition')` work for `<dt>` / `<dd>`.
- A `Record<Layout, …>` lookup is always defined for a `Layout` key: do not add a `?? fallback` that can never run, even in tests.
- In this worker image, `scripts/check.sh` always stops at the check-pack staleness caused by the skip-worktree CI `opencode.json`, and `scripts/python-coverage.sh` lacks the `coverage` module plus fails check-pack/worker-gate sandbox tests. Confirm once, run the other stages individually and do not edit protected files.
