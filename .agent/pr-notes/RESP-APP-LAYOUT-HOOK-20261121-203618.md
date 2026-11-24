## Summary

Added the patient app's single window-size classification boundary as two new files: a pure `windowClassFor(width)` and a `useWindowClass()` hook built on React Native `useWindowDimensions` plus the app's existing `react-native-safe-area-context`. Layout follows the window, not the device, so rotation, split-screen and Slide Over recompute from the new width. No dependency, configuration, migration or tracker changes.

## Affected requirements

- PAT-01 (appointments), PAT-02 (reports, no persistent clinical cache), UX-01 (accessibility/RTL) — `PROJECT_V1_SPEC.md` lines 85–86, 90.
- Decision `docs/decisions/0005-patient-app-tablets.md` (600 dp / 840 dp breakpoints; layout from window width; text scaling to 200%).
- Task `RESP-APP-LAYOUT-HOOK` (TODO.md:3672), requirements PAT-01, PAT-02, UX-01.

## Risk class

Routine. Contained new presentation helper plus tests; reads native window metrics and safe-area insets only. No clinical data, authorization, persistence or contract change.

## Review owner

Senior React Native and Expo (bounded simulated subagent review — plan only, no edits) under the standing Senior TypeScript and Test Engineer rules. No human, clinician or patient-participant evidence is claimed.

## Data changes

None. No migration, backfill, lock/volume impact or rollback. Introduces no persistent clinical cache (PAT-02).

## Authorization boundaries and audit events

None. The helper reads window dimensions, font scale and safe-area insets; it touches no tenant context, role, patient link, credential or audit event.

## Migration and rollout considerations

None. Presentation-only. The hook re-renders on React Native dimension changes, which is what makes rotation and iPad multitasking pick up a new layout class (decision 0005).

## Test evidence

Evidence source: executed tool commands (below); the specialist was a simulated subagent that returned a bounded plan and made no edits. One specialist inference was refuted by a real command (see Findings).

- Red first: `cd apps/patient-mobile && npx jest src/app/layout/useWindowClass.test.ts --runInBand --coverage=false` → `Cannot find module './useWindowClass' from 'src/app/layout/useWindowClass.test.ts'` (production file did not exist yet).
- Green: same command after adding the hook → `Test Suites: 1 passed, 1 total`, `Tests: 8 passed, 8 total`.
- Typecheck: `cd apps/patient-mobile && npm run typecheck` → exit 0, no output beyond the script line; **no `error TS…`**. Final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- Lint: `cd apps/patient-mobile && npm run lint` → exit 0, 0 errors, 2 pre-existing `import/no-duplicates` warnings in `src/features/tracker/presentation.test.ts` (out of scope).
- Module gate (initial, before repair): `bash apps/patient-mobile/check.sh` → `Test Suites: 1 failed, 59 passed, 60 total`; `Tests: 1 failed, 613 passed, 614 total` — the `src/features/domain-layout.test.ts` domain-set invariant. Repaired in "Repair attempt 1" below; the final gate is green (60/60 suites, 614/614 tests). Typecheck and lint inside `check.sh` passed.
- Entry point to test trace: `useWindowClass.ts:windowClassFor` (pure classifier) → `windowClassFor.test` cases `classifies width 599/600/839/840 as …`; `useWindowClass.ts:useWindowClass` → real `useWindowDimensions` (driven by spied `Dimensions.get` and a spied `Dimensions.addEventListener('change')` listener fired inside `act`) → `useWindowClass.test.ts` hook cases; `useSafeAreaInsets` is mocked locally and asserted by `forwards the system font scale and safe-area insets`.

## Coverage

- Command: `cd apps/patient-mobile && npx jest src/app/layout/useWindowClass.test.ts --runInBand --coverage --collectCoverageFrom='<rootDir>/src/app/layout/useWindowClass.ts'`
- Result: `All files | 100 | 100 | 100 | 100`; `useWindowClass.ts | 100 | 100 | 100 | 100` for statements, branches, functions and lines. No uncovered line or branch in the changed file.
- New branches and the test that takes each:
  - `windowClassFor`: `if (width < 600)` true → `classifies width 599 as compact`; false → covered by the 600, 839 and 840 cases.
  - `windowClassFor`: `if (width < 840)` true → `classifies width 600 as regular` and `classifies width 839 as regular`; false → `classifies width 840 as large`.
  - No other branch was added: `isLandscape: width > height` is a comparison expression (not an Istanbul branch), and there is no ternary, `&&`, `||`, `switch`, `catch` or optional-chain fallback in the new production file.
  - Hook lines (`useWindowDimensions`, `useSafeAreaInsets`, returned object) execute in all four hook tests; `width`/`height` are asserted in `reports the compact class and portrait orientation for a narrow window`, `fontScale`/`insets` in `forwards the system font scale and safe-area insets`, both orientation values in the landscape and dimension-change tests.

## Findings

- Pre-existing module-gate failure (tool evidence): `src/features/domain-layout.test.ts` hard-codes 14 `DOMAIN_FOLDERS` and omits the tracked `tracker` feature folder, so `bash apps/patient-mobile/check.sh` fails at HEAD regardless of this change. Verified with `git show HEAD:apps/patient-mobile/src/features/domain-layout.test.ts` (14 entries) and `git ls-files apps/patient-mobile/src/features/tracker` (tracked at HEAD). TRACK-02 (TODO.md:2178) added the folder with scope limited to `src/features/tracker`. Repaired directly in "Repair attempt 1" below after the coverage gate returned this task; no follow-up task is needed.
- Repository-level check artifact (tool evidence): `bash scripts/check.sh` exits 1 at its first step with `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` reports flag `S` (skip-worktree) and the worktree file hash differs from `git show HEAD:opencode.json`; this is the documented cloud-worker CI-profile-under-skip-worktree artifact (MEMORY.md, 2026-11-20 daily notes), not a change from this story. `python3 -I scripts/gen-opencode.py --check` therefore also reports out of date in this worktree. I did not edit the protected generated file; use HEAD truth for policy evidence.
- Pre-existing lint warnings: 2 `import/no-duplicates` warnings in `src/features/tracker/presentation.test.ts` (0 errors). Out of scope.
- Specialist inference refuted by tool evidence: the reviewer suggested `jest.mock('react-native', () => ({ ...jest.requireActual('react-native'), useWindowDimensions: … }))`. The actual run threw a `TurboModuleRegistry.getEnforcing` invariant because spreading the RN barrel eagerly evaluates lazy native getters. Fixed by spying on the real `Dimensions.get` / `Dimensions.addEventListener` and firing a change event, which also tests the real `useWindowDimensions` implementation.
- `@testing-library/react-native` 14's `renderHook` returns a `Promise<RenderHookResult>`; the first green attempt failed with `Cannot read properties of undefined (reading 'current')` until all four hook tests were made `async` and `await`ed.

## Lessons for workers

- To test `useWindowDimensions` deterministically under jest-expo, spy on `Dimensions.get` and `Dimensions.addEventListener('change', …)` and invoke the captured listener inside `await act(async () => …)`. Do not spread `jest.requireActual('react-native')` in a `jest.mock` factory — it evaluates lazy native getters and throws a TurboModule invariant.
- `render` and `renderHook` in `@testing-library/react-native` 14 are async: `await` them before reading `result.current`, and `await rerender(...)`.
- When a module gate fails, check `git show HEAD:<failing test>` before treating it as your defect; an out-of-scope failure belongs in Findings plus a task request, never in an in-scope edit.

## Clinical and architecture review

Not required: this is a presentation-only helper with no signed data, cross-organization sharing, patient linkage change, prescribing or critical-result handling. No clinical sign-off is claimed; simulated senior-mobile review only.

## Repair attempt 1 (2026-11-21) — resolved

The coverage gate returned this task (repair 1 of 2). The failure was not in my production code: `bash apps/patient-mobile/check.sh` failed on the pre-existing structural invariant `src/features/domain-layout.test.ts › names every feature directory after its business domain` — its hardcoded 14-entry `DOMAIN_FOLDERS` omitted the already-tracked business-domain folder `tracker` (added by TRACK-02). Fix: added `'tracker'` to `DOMAIN_FOLDERS` (test-only, one line). No threshold was lowered, no exclusion added, and no production code changed.

Evidence source: executed tool commands only. No reviewer, simulated user or human participant was involved in this repair.

- Fix: `apps/patient-mobile/src/features/domain-layout.test.ts` — single added line `  'tracker',` in `DOMAIN_FOLDERS`; `git diff` shows no other change.
- Green invariant: `cd apps/patient-mobile && npx jest src/features/domain-layout.test.ts --runInBand --coverage=false` → `Test Suites: 1 passed, 1 total`; `Tests: 3 passed, 3 total`.
- Full module gate: `bash apps/patient-mobile/check.sh` (from repo root) → exit code 0; `All files | 100 | 100 | 100 | 100`; `...src/app/layout | 100 | 100 | 100 | 100`; `Test Suites: 60 passed, 60 total`; `Tests: 614 passed, 614 total`. `tsc --noEmit` and lint inside the gate passed (no `error TS…`, no new errors).
- The `types.ts` rows still display `0 | 0 | 0 | 0` because they are type-only modules with no executable statements; the build-failing global 100% threshold passes (`All files 100/100/100/100`), so no uncovered executable line or branch remains.

### Branch-to-test list (every new/changed branch)

- `src/app/layout/useWindowClass.ts` (production, from the original change) — 100% statements/branches/functions/lines:
  - `windowClassFor` `if (width < 600)` true → `classifies width 599 as compact`; false → exercised by the `classifies width 600 as regular`, `classifies width 839 as regular` and `classifies width 840 as large` cases.
  - `windowClassFor` `if (width < 840)` true → `classifies width 600 as regular` and `classifies width 839 as regular`; false → `classifies width 840 as large`.
  - Straight-line hook body (`useWindowDimensions`, `useSafeAreaInsets`, returned object) runs in all four hook tests: `reports the compact class and portrait orientation for a narrow window`, `forwards the system font scale and safe-area insets`, the landscape case and the dimension-change case. `isLandscape: width > height` is a comparison expression, not an Istanbul branch; the file has no ternary, `&&`, `||`, `switch`, `catch` or optional-chain fallback.
- `src/features/domain-layout.test.ts` (test-only repair) — no executable production branch added. The change is a single data value (`'tracker'`) in the expected `DOMAIN_FOLDERS` list; its three invariants (exact domain set, no story-ID directory, each domain exposes an entry point) are asserted by the three existing `it(...)` blocks and now pass. The file is excluded from coverage scope.

`bash scripts/check.sh` still stops on the documented skip-worktree `opencode.json` CI-profile artifact (flag `S`; worktree is the CI profile, HEAD holds the committed local profile). That is an environment/policy-check artifact, not a defect in the delivered source; the integrator/Delivery should judge policy from HEAD truth. No task request is needed — the previously requested structural-test fix is now applied.
