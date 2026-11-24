# RESP-APP-CONVERSATION-THREAD — tablet, landscape and large-text layout for `ConversationThread`

## Summary

`ConversationThread` now renders its content in the cross-platform `react-native-safe-area-context` safe-area view, a scroll container and a single detail column. The wrapper is resolved from the installed library (real insets on iOS and Android), with a module-shape fallback for the repository's default-only Jest double; a test asserts the rendered native node is `RNCSafeAreaView`, not a plain `View`. The column reuses the existing pure window classifier (`windowClassFor`) so a `large` window caps the reading width at 720 dp and centres the column; compact and regular windows keep full width. System font scale above 1 grows only the column's bottom padding, so wrapped text is never shrunk, truncated or given a fixed height. No behaviour, copy, API call, export, dependency or clinical cache changed. A new table-driven test renders the component across six window/orientation combinations × three font scales (18 cases), plus an RTL structure case and a no-clipping case.

Entry point → test trace: `ConversationThread` (exported component) → `ScreenFrame` → `SafeAreaView` → `ScrollView` → `View testID="conversation-thread-content"` → `ConversationThread.tablet.test.tsx` asserts labels, width cap, text room, touch targets and the safe-area/scroll wrappers.

## Affected requirements

- **PAT-01** (appointments/patient self-access) and **PAT-02** (released reports, no persistent clinical cache): presentation-only change; no storage, download, auth or API path touched. `src/app/layout/useWindowClass.ts` is reused, not duplicated.
- **UX-01**: safe-area wrapper, 200% text reflow with no `numberOfLines`/`adjustsFontSizeToFit`/`maxFontSizeMultiplier` below 2, unchanged roles/labels/focus order, RTL direction and Arabic copy preserved.

Acceptance mapping:
1. Read component, existing test, decision 0005 and `useWindowClass.ts` — done (tool: file reads).
2. Compact/regular/large × portrait/landscape × 1.0/1.5/2.0 — `it.each` matrix covers all 18 combinations. Max readable width and centring only on large; a thread is the *detail* half of the inbox-and-thread pair, so two-pane composition stays at the navigator/inbox boundary (out of scope). Safe area via the installed cross-platform library view (both platforms); 48 pt (≥44) send target; no fixed height or text truncation; labels and focus order unchanged; RTL preserves `direction: 'rtl'`, Arabic labels and test-ID order; no cache added.
3. New test file asserts structure, accessibility labels and RTL; the existing `ConversationThread.test.tsx` passes unchanged.
4. No behaviour, text, API call, exported name or dependency changed.

## Risk class

Routine (contained presentation change in one patient screen; safety-critical clinical/data boundaries untouched). `docs/WORKFLOW.md`.

## Review owner

Senior React Native and Expo (`aurevia-senior-mobile`); independent UX review remains a separate launch gate. A bounded senior-mobile **simulated review** completed (static file reads only; the reviewer ran no commands). It found one acceptance gap — RN's built-in `SafeAreaView` is iOS-only — which was fixed by using the installed cross-platform library view and proving the rendered native node type in the test.

## Data changes

None. No migration, backfill, table, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None added or changed. The component only renders callbacks from the authenticated host; no new tenant, role or patient-link scope and no audit event.

## Migration and rollout considerations

None. Presentation-only; no release or deployment action.

## Test evidence

Evidence source for every line below: **executed tool commands** (no human or clinical participant; no simulated-user run was needed for a layout-only slice).

- `npx jest src/features/conversations/ConversationThread.tablet.test.tsx --ci --runInBand` (before implementation): **20 failed / 20** — red because `conversation-thread-content` did not exist.
- `npx jest src/features/conversations/ConversationThread.tablet.test.tsx src/features/conversations/ConversationThread.test.tsx --ci --runInBand`: **Test Suites: 2 passed, 2 total; Tests: 29 passed, 29 total**.
- `npm run typecheck` (final lines): `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit` — exit 0, no `error TS…`.
- `npm run lint` (final lines): `> patient-mobile@0.1.0 lint` / `> eslint .` — 0 errors, 2 pre-existing warnings in `src/features/tracker/presentation.test.ts` (`import/no-duplicates`), unrelated to this change and present before it.
- `bash apps/patient-mobile/check.sh` (mandatory gate, re-run after the review fix, exit 0) final lines:
  `Test Suites: 63 passed, 63 total`
  `Tests:       669 passed, 669 total`
  `Snapshots:   0 total`
  `Time:        4.357 s, estimated 10 s`
  `Ran all test suites.`
  Typecheck final lines from the same run: `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit` (no `error TS…`).
  Coverage row from the same run: `All files | 100 | 100 | 100 | 100`.

## Coverage

- `npx jest src/features/conversations/ConversationThread.tablet.test.tsx src/features/conversations/ConversationThread.test.tsx --coverage --collectCoverageFrom='src/features/conversations/ConversationThread.tsx' --ci --runInBand`
  → `ConversationThread.tsx | 100 | 100 | 100 | 100 |` (statements, branches, functions, lines), 29 tests passed.
- Full gate `bash apps/patient-mobile/check.sh` → `All files | 100 | 100 | 100 | 100`, 669 tests passed.
- New/changed branches and the test that takes each side:
  - `windowClass === 'large' ? styles.wideContent : null` — true: the `large portrait` and `large landscape` matrix rows assert `maxWidth: 720` and `alignSelf: 'center'`; false: the four compact/regular rows assert both are undefined.
  - `Math.max(0, fontScale - 1)` (numeric floor, not a JS branch) — 0 at scale 1.0, positive at 1.5 and 2.0; all three scale rows assert the exact `paddingBottom` (0 / 8 / 16).
  - `safeAreaContext.SafeAreaView ?? ...default.SafeAreaView` (module-shape resolver added after review) — named-export side: `ConversationThread.tablet.test.tsx` mocks the named export and all 20 cases assert the wrapper's rendered type is `RNCSafeAreaView`; default-export side: existing `ConversationThread.test.tsx` runs under the shared default-only double and passes.
  - No `if`, `switch`, `catch`, `||`, `&&`, optional chain or defaulted parameter was added; no unreachable branch was introduced.

## Clinical and architecture review

Not required for this presentation-only slice (no signed data, cross-organization sharing, patient association change, prescribing or critical result). Decision 0005 and the accessibility patterns were read; real VoiceOver/TalkBack and Arabic-clinician passes remain a G5 launch gate and are not claimed here.

## Findings

- **Shared Jest double cannot supply named safe-area exports.** `apps/patient-mobile/jest.setup.ts` mocks `react-native-safe-area-context` with `jest.requireActual('.../jest/mock')`, whose export is a *default* object. Calling `useWindowClass()` (named `useSafeAreaInsets`) made the out-of-scope existing `ConversationThread.test.tsx` fail with `useSafeAreaInsets is not a function`; `RootNavigator.test.tsx:94-107` documents the same defect. Therefore the component uses `useWindowDimensions()` plus the pure `windowClassFor()` from the same hook module (same 600/840 breakpoints, re-renders on resize), and resolves the library's cross-platform `SafeAreaView` from either export shape once at module scope. No existing test or setup file was edited. A follow-up story should repair the shared mock so screens can call `useWindowClass()` directly.
- **Review finding fixed (simulated senior-mobile review).** The first implementation used React Native's `SafeAreaView`, which is iOS-only (RN 0.88 implements it as a plain `View` on Android), so "safe-area respected" was not met on Android and the original test's truthy check gave false assurance. It was replaced with the already-installed `react-native-safe-area-context` `SafeAreaView` (native insets on both platforms, no provider needed), and the test now asserts the rendered host node type is `RNCSafeAreaView`. The reviewer ran no commands, so its code claims are static-review evidence; the fixes were verified by the executed gates above.
- **Non-blocking review note.** Focus order / labels "unchanged" is argued by inspection (the added wrappers are non-accessible Views; no label or role moved) and by LTR/RTL test-ID-order equality, not by a byte diff against the pre-change accessibility tree.
- **Two-pane composition boundary.** The story's two-pane example is "inbox and thread"; `ConversationThread` is only the detail half. Adding an inbox list here would duplicate `ConversationInbox`, change the accessibility tree and violate "do not change behaviour". The large-window detail column is capped and centred, leaving the list/detail composition to the navigator (not in scope).
- **Pre-existing lint warnings** in `src/features/tracker/presentation.test.ts` are unrelated and did not block the gate (lint is not run with `--max-warnings=0` in this module).
- **Pre-existing committed conflict markers on `main`.** `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds markers committed in HEAD (`origin/main`, `0b8eab6`) in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java` (`origin/swarm/BUILD-37-469` sides). `git merge --no-edit origin/main` reported "Already up to date" and my branch has no conflicts; these markers are not from this story and were left untouched (out of scope). Integrator/owner should repair them.
- **Pre-existing `check-pack.py` result in this worker.** `python3 -I scripts/check-pack.py` exits 1 with `opencode.json is stale: run scripts/gen-opencode.py`; `git status` shows `opencode.json` unmodified by me. This matches the delivery skill's documented CI-profile-under-skip-worktree artifact and the open OPS-CW-003-01 note, not a change from this story; the protected generator/config were not edited.
- **`scripts/check.sh` intentionally not run.** It calls `apps/api/check.sh` → `mvn -q -B verify` (all integration tests, >10 minutes), which the execution request explicitly forbids for a worker. The mandatory module gate `bash apps/patient-mobile/check.sh` was run and is green. Its fast policy checks that apply to a Markdown notes file were run individually: `check-docs`, `check-secrets`, `check-names` and `check-wiring` all exit 0.

## Lessons for workers

- Before importing a hook into a component, render its *existing* test file alone: this app's shared safe-area Jest mock exposes only a default export, so named safe-area hooks require a per-file mock (see `RootNavigator.test.tsx`). A green new test does not prove old tests still pass.
- When a story's two-pane acceptance criterion names a pair of screens, check whether this component is the list or the detail; a detail-only screen should cap/centre its column, not invent a second pane.
- In this module, `bash apps/patient-mobile/check.sh` is the only accepted frontend gate; Jest alone is never done, and the typecheck final lines must be pasted into the notes.
- React Native's own `SafeAreaView` is iOS-only; use the installed `react-native-safe-area-context` view for Android/tablet work, and assert the rendered native node type (`RNCSafeAreaView`) so a plain `View` cannot masquerade as a safe-area wrapper.
