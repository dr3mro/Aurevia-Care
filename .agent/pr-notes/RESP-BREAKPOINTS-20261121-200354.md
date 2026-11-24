# RESP-BREAKPOINTS — Staff web breakpoint tokens and `useBreakpoint`

## Summary

Added two new staff-web presentation utilities under `src/theme` plus their unit test: `breakpoints.ts` publishes the three WEB-07 layout classes, the 768/1280 `min-width` thresholds, the corresponding media query strings and a pure `breakpointFor(width)`; `useBreakpoint.ts` provides `useBreakpoint`, `usePointerCoarse` and `useOrientation` over `window.matchMedia`, with listener cleanup and an `expanded` fallback when `matchMedia` is absent. No existing file was changed. Synthetic boolean fixtures only; no patient data or credentials.

## Affected requirements

- WEB-07: responsive layout tokens at 768 px medium / 1280 px expanded; three layout classes (compact/medium/expanded) per ADR-0004.
- UX-01: coarse-pointer query `(pointer: coarse)` (building block for the 44 px coarse-target rule) and orientation query `(orientation: portrait)`; presentation-only, no authorization effect.

Acceptance mapping:

| Story step | Where implemented | Test |
|---|---|---|
| Widths 768/1280, media queries, `breakpointFor` | `src/theme/breakpoints.ts` | `tests/theme/breakpoints.test.ts`: "publishes the documented widths and min-width queries", "maps the boundary widths..." (767/768/1279/1280) |
| Hook returns class, updates on change, cleans up, fallback | `src/theme/useBreakpoint.ts` (`useBreakpoint`, internal `useMediaQuery`) | "reads the current class and updates when a mocked media query fires"; "removes both media query listeners on unmount"; "falls back to expanded when matchMedia does not exist" |
| `usePointerCoarse`, `useOrientation` | `src/theme/useBreakpoint.ts` | "usePointerCoarse tracks the (pointer: coarse) query"; "useOrientation tracks the (orientation: portrait) query" |

## Risk class

Routine (contained new files, no clinical data, no permissions, no schema). Reviewed by a simulated `orevia-senior-react` specialist; no blocking findings.

## Review owner

`orevia-senior-react` (read-only simulated review; files tool-inspected, checks not re-run by the reviewer). Skills loaded: `orevia-delivery`, `orevia-senior-react`, `orevia-senior-typescript`, `orevia-test-engineer`.

## Data changes

None. No migrations, backfills, locks or database access. Test fixtures are boolean media-query states only.

## Authorization boundaries and audit events

None. These are presentation-only helpers; no tenant, role or patient scope and no audit events.

## Migration and rollout considerations

None. New, currently unconsumed module files; no rollout impact until a later story wires them into the shell.

## Test evidence

Entry point → test trace:

- `apps/staff-web/src/theme/breakpoints.ts` → imported by `tests/theme/breakpoints.test.ts`; `breakpointFor` invoked directly at all four boundary widths.
- `apps/staff-web/src/theme/useBreakpoint.ts` → imported by `tests/theme/breakpoints.test.ts`; each hook rendered with `@testing-library/react`'s `renderHook`, `globalThis.matchMedia` stubbed via `vi.stubGlobal`, `change` listeners fired with `act`, listener counts asserted on `unmount()`, and `matchMedia` stubbed to `undefined` for the fallback case.

Exact commands and final lines (all run from `apps/staff-web` unless noted):

- `npm ci --no-audit --no-fund` → `added 486 packages in 4s`.
- Red before implementation: `npx vitest run --exclude 'e2e/**' tests/theme/breakpoints.test.ts` → `Error: Failed to resolve import "../../src/theme/breakpoints" ... Does the file exist?` / `Tests  no tests` (expected: files under test did not exist yet).
- Green after implementation: `npx vitest run --exclude 'e2e/**' tests/theme/breakpoints.test.ts` → `Test Files  1 passed (1)` / `Tests  7 passed (7)`.
- Typecheck (mandatory final lines): `npm run typecheck`:
  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  exit 0, no `error TS…` diagnostics.
- `npm run lint` → `> eslint . --max-warnings 0`, exit 0.
- `npm run build` → `✓ built in 107ms`.
- `node scripts/check-bundle-size.mjs` → every chunk printed `ok` (exit 0).
- Coverage: `npm run test:coverage` → `Test Files  73 passed (73)` / `Tests  949 passed (949)` / `No files with missing coverage.` / `108 files fully covered.` / `Statements  100% ( 2136/2136 )`, `Branches  100% ( 1129/1129 )`, `Functions  100% ( 691/691 )`, `Lines  100% ( 2049/2049 )`.
- `bash check.sh` → stopped at step 1, `npm run gen:api:check`: `✨ openapi-typescript 7.4.2` / ` ✘  Generated types are not up-to-date!` (pre-existing, see Findings). Remaining check steps were run individually above and all pass.

Evidence provenance: all results above came from executed shell commands (tool evidence). The specialist review was simulated and read-only. No human, clinical or patient-participant evidence.

## Coverage

Command: `cd apps/staff-web && npm run test:coverage`, then read `coverage/coverage-summary.json`.

| File | Lines | Branches | Functions | Statements |
|---|---|---|---|---|
| `src/theme/breakpoints.ts` | 100% (7/7) | 100% (4/4) | 100% (1/1) | 100% (7/7) |
| `src/theme/useBreakpoint.ts` | 100% (24/24) | 100% (10/10) | 100% (8/8) | 100% (26/26) |

No threshold, exclusion or test skip was added or changed. Every new branch and its test:

| Branch | Test that takes it |
|---|---|
| `breakpointFor`: `width >= 1280` true | `breakpointFor(1280)` → `expanded` |
| `breakpointFor`: `width >= 1280` false, `width >= 768` true | `breakpointFor(768)`, `breakpointFor(1279)` → `medium` |
| `breakpointFor`: both comparisons false | `breakpointFor(767)` → `compact` |
| `useMediaQuery` initializer `globalThis.matchMedia?.()` defined | all `installMatchMedia(...)` tests (stubbed function) |
| `useMediaQuery` initializer `globalThis.matchMedia?.()` undefined | "falls back to expanded when matchMedia does not exist" |
| `useMediaQuery` ternary `list ? list.matches : fallback` true side | initial `medium` assertion in "reads the current class..." |
| `useMediaQuery` ternary false side | fallback test (`expanded`/`false`/`portrait`) |
| `useMediaQuery` effect `if (!list) return` true side | fallback test (no listener registered) |
| `useMediaQuery` effect `if (!list) return` false side | all `installMatchMedia(...)` tests |
| `useMediaQuery` change listener callback | `act(() => media.fire(...))` in breakpoint/pointer/orientation tests |
| `useMediaQuery` cleanup callback | "removes both media query listeners on unmount" (sizes 1 → 0) |
| `useBreakpoint`: `if (expanded)` true | expanded query fired to `true` → `expanded` |
| `useBreakpoint`: `if (expanded)` false, `if (medium)` true | initial state `medium=true`, `expanded=false` → `medium` |
| `useBreakpoint`: both `if` false | both queries fired to `false` → `compact` |
| `useOrientation` ternary true / false | portrait `true` → `portrait`, fired `false` → `landscape` |
| `usePointerCoarse` true / false | coarse `true` → `true`, fired `false` → `false` |

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or critical results. UX-01 presentation alignment was checked against `docs/decisions/0004-responsive-staff-web.md` and `docs/design/accessibility-patterns.md` by simulated review; real WCAG 2.2 AA audit and real-browser reflow/coarse-pointer passes remain separate gates.

## Findings

- **Pre-existing generated-client drift blocks the wrapper check** (tool evidence): `bash check.sh` stops at `npm run gen:api:check` with `Generated types are not up-to-date!`. Generating to `/tmp/opencode/generated-check.d.ts` and diffing against `src/api/generated/openapi.d.ts` shows `contracts/openapi.yaml` carries `/api/v1/order-entry/drafts`, `/api/v1/order-entry/drafts/{id}` and `/api/v1/order-entry/drafts/{id}/place` that the checked-in client lacks. Neither file is in this story's scope and neither was modified (`git status` shows only the three new files). Next step: regenerate and commit the client under a separate owner/module task, then re-run `bash apps/staff-web/check.sh`.
- The new utilities have no production consumer yet. This is expected for this building-block slice; ADR-0004 assigns shell/navigation/table wiring to later stories.
- ADR-0004 is still `Proposed` and `PROJECT_V1_SPEC.md` WEB-07 still states the old 1280/768 floors; the owner amendment is pending. Code follows the decision as the story instructed.
- The `useTheme must be used inside ThemeProvider` stack traces in the coverage output come from the pre-existing negative-path test `tests/theme/ThemeProvider.test.tsx`; they are logged errors from a passing test, not a regression.
- Simulated senior-react review (low, applied): media query strings now derive from `BREAKPOINT_WIDTHS` so the 768/1280 values have one source of truth; re-ran test, typecheck, lint and coverage after the edit — all green.

## Lessons for workers

- jsdom does not implement `window.matchMedia`. Stub it with `vi.stubGlobal('matchMedia', fn)` and restore with `vi.unstubAllGlobals()` in `afterEach`; reading `globalThis.matchMedia?.()` covers both the server-render fallback and the present case without an unreachable `typeof window === 'undefined'` branch.
- Vitest v8 per-file coverage counts internal (non-exported) hook functions. Assert listener cleanup by recording listener counts on the fake `MediaQueryList` and checking they drop to zero after `renderHook(...).unmount()`.
- `apps/staff-web/check.sh` runs `gen:api:check` first, so unrelated contract/client drift can mask passing scoped gates. Record the wrapper failure distinctly and run typecheck, lint, build, bundle-size and `test:coverage` individually.
- Derive media query strings from the width constants to avoid two independent sources of truth for the same breakpoint values (simulated reviewer finding).

## Why this is not finished

The scoped implementation is complete and every in-scope gate is green (7/7 focused tests, 949/949 module tests, typecheck, lint, build, bundle-size and 100% coverage on both new files). The wrapper `bash apps/staff-web/check.sh` cannot complete because its first step fails:

- Command: `cd apps/staff-web && bash check.sh`
- Error: `✨ openapi-typescript 7.4.2` / ` ✘  Generated types are not up-to-date!` (`npm run gen:api:check`)
- Most likely cause: an earlier story added order-entry draft paths to `contracts/openapi.yaml` without regenerating `apps/staff-web/src/api/generated/openapi.d.ts` at HEAD.
- What was tried: re-ran `bash scripts/gen-api-types.sh --check`; generated to `/tmp/opencode/generated-check.d.ts` with the pinned CLI and diffed (confirmed the three missing `/api/v1/order-entry/drafts*` paths); confirmed `git status` shows no change to either contract or generated client.
- Exact next step: outside this story's three-file scope, regenerate with `cd apps/staff-web && npm run gen:api`, commit `src/api/generated/openapi.d.ts`, then re-run `bash apps/staff-web/check.sh`. This story did not touch that file and must not.
