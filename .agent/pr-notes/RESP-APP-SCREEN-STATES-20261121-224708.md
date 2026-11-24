## Summary

Made the shared patient-app state view (`ScreenStateView`) responsive for tablets, landscape and 200% text without changing any behaviour, text, API call or exported name. A state placeholder now takes its breakpoint from the app's authoritative `windowClassFor` rule, adds safe-area insets to its padding, caps and centres its column on `large` windows, and uses logical `paddingStart`/`paddingEnd` so RTL mirrors. The retry control is enforced at 44×44 points. Added a dedicated matrix test file covering 3 window classes × 3 font scales × 2 orientations (18 layouts) plus RTL, every state and the unchanged ready-state structure. No dependency, migration or persistent cache was added.

## Affected requirements

PAT-01, PAT-02, UX-01.

## Risk class

Routine — presentation-only styling of an existing shared component. No data, authorization, transport or clinical-state change (docs/WORKFLOW.md).

## Review owner

`orevia-senior-mobile` and `orevia-ux-reviewer` (simulated specialist reviews, both returned; senior-mobile reported no actionable findings, UX reported one out-of-scope pre-existing text gap and three low/evidence items). No human, clinical or real-patient review; that remains a G5 gate.

## Data changes

None. No migration, backfill, lock or volume impact, no rollback or forward-fix.

## Authorization boundaries and audit events

None. The component remains stateless: it receives an already-authorized `ResourceState` and renders generic labels plus an opaque correlation id; no new audit event, tenant/patient scope or storage access.

## Migration and rollout considerations

None. Purely presentational; safe-area insets and breakpoints are read at render time and recomputed by React Native on dimension change, so rotation and iPad Split Screen/Slide Over reflow without a reinstall.

## Test evidence

Evidence provenance: all commands below were executed by the primary agent (tool evidence). Specialist findings are simulated reviews, not human or patient evidence. All fixtures are synthetic (`TransportError('server', { correlationId: 'corr-7' })` etc.); no patient data or credential appears.

Environment commands and final lines:

- `cd /__w/Aurevia-Care/Aurevia-Care && git merge --no-edit origin/main` → `Already up to date.` (run at start, after implementation, and before finishing).
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → `added 1408 packages in 25s`.
- `npx jest src/app/screenStates.test.tsx src/app/screenStates.tablet.test.tsx --runInBand` → `Test Suites: 2 passed, 2 total`; `Tests: 57 passed, 57 total`.

Frontend definition of done (module gate, includes typecheck):

- `bash apps/patient-mobile/check.sh` → exit 0.
- Typecheck final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  (no `error TS…`; `npm run typecheck` alone also exits clean).
- Repository frontend policy gate (root tsconfig enables `exactOptionalPropertyTypes`, stricter than the module tsconfig): `bash scripts/frontend-check.sh` → `frontend standards: policy and tool commands passed`. During the first run this gate reported `apps/patient-mobile/src/app/screenStates.tablet.test.tsx(150,6): error TS2375` because the helper passed `onRetry={undefined}` to an optional prop; the helper now takes a required callback (all call sites already pass one). Re-run is green.
- Lint final lines:
  ```
  > patient-mobile@0.1.0 lint
  > eslint .
  ✖ 2 problems (0 errors, 2 warnings)
  ```
  The two warnings are pre-existing `import/no-duplicates` warnings in `src/features/tracker/presentation.test.ts` (lines 1 and 2), unrelated to this change and untouched.
- Jest final lines:
  ```
  Test Suites: 63 passed, 63 total
  Tests:       690 passed, 690 total
  Snapshots:   0 total
  Time:        6.892 s
  ```

Entry point → test trace: `src/app/PatientResource.tsx:78` renders `<ScreenStateView>` → `src/app/screenStates.tsx` `ScreenStateView` → `useStateLayout()` (`useWindowDimensions()` width → `windowClassFor()` from `src/app/layout/useWindowClass.ts`, safe-area insets from `react-native-safe-area-context`) → `stateContainerStyle()` → the rendered host `View` carrying `testID="screen-error"`. `src/app/screenStates.tablet.test.tsx` asserts that host node's style, roles, labels and text order across all 18 layouts; the pre-existing `screenStates.test.tsx` continues to assert every state mapping and retry/correlation behaviour.

## Coverage

- Command: `cd apps/patient-mobile && npx jest src/app/screenStates.test.tsx src/app/screenStates.tablet.test.tsx --runInBand --coverage --collectCoverageFrom='src/app/screenStates.tsx'`
  Result: `screenStates.tsx | 100 | 100 | 100 | 100` (statements, branches, functions, lines); `All files | 100 | 100 | 100 | 100`.
- Module gate: `bash apps/patient-mobile/check.sh` → `All files | 100 | 100 | 100 | 100` and `screenStates.tsx | 100 | 100 | 100 | 100`, with the global 100% threshold in `jest.config.ts` unchanged. No threshold, exclusion, skip, `@ts-ignore` or dependency was added or changed.

New/changed branches and the test that takes each side:

| Branch | True side | False side |
|---|---|---|
| `typeof SafeAreaContext.useSafeAreaInsets === 'function'` (module-load shim) | `screenStates.tablet.test.tsx` mocks the package with a named `useSafeAreaInsets` | existing `screenStates.test.tsx` uses the shared `jest.setup.ts` default-only mock |
| `windowClass === 'large'` in `stateContainerStyle` | 6 `large` layouts in the `it.each(layouts)` matrix (and the RTL/loading cases) | the 12 `compact`/`regular` matrix layouts and the existing `screenStates.test.tsx` renders |

`Math.max(insets.left, insets.right)` is a built-in call, not a branch. All pre-existing branches (`state.kind` switch, `screenStateForError` switch, `correlationId` and `onRetry` ternaries) remain covered by the unchanged existing test file.

## Clinical and architecture review

No signed data, cross-organization sharing, patient association, prescribing or critical result is touched; no clinical sign-off is required for this presentation-only change. `orevia-senior-mobile` (no actionable findings) and `orevia-ux-reviewer` (findings below) reviewed the two files against `docs/decisions/0005-patient-app-tablets.md`, `docs/design/accessibility-patterns.md` §9 and `docs/design/rtl-and-localization.md`. These are simulated reviews; real VoiceOver/TalkBack passes on device remain a G5 gate.

## Findings

- **Safe-area Jest mock shape (pre-existing, worked around in scope).** The shared `jest.setup.ts` mocks `react-native-safe-area-context` with `jest.requireActual('.../jest/mock')`, whose `useSafeAreaInsets` is only on the `default` export. A direct named import of the hook throws in existing tests. `jest.setup.ts` is outside this task's scope, so `screenStates.tsx` resolves the hook once at module load from the named export with a fallback to the default export. Production behaviour stays on the named-export path; both sides are covered. A maintainer-owned task request to normalise the shared mock is included below.
- **Two-pane (not applicable here).** `ScreenStateView` renders one message placeholder or delegates the ready state to its caller; it is not a list-plus-detail screen. Decision 0005 reserves two panes for inbox/thread, appointments/detail and reports/detail, which are owned by sibling stories. The ready-state branch is deliberately left unwrapped so those screens keep full control of their layout.
- **UX review F1 (pre-existing, out of scope by explicit instruction).** The correlation line uses the hard-coded English `Reference: ${correlationId}` text. Localizing it would change text and require editing `src/app/messages.ts`, both forbidden by the story ("do not change behaviour, text …", scope limited to two files). Left unchanged; recorded here for a follow-up.
- **UX review F2 (low).** `MIN_TOUCH_TARGET = 44` is applied on Android as well; the platform standard in §9 is 48 dp, while the story's acceptance floor is "at least 44 by 44 points". The story criterion is met; a universal 48 or `Platform.select` is left for a follow-up because the story fixes the 44-point floor and forbids out-of-scope changes.
- **UX review F3 (addressed).** Accessibility roles are now asserted for `loading`, `empty`, `offline` and `session-expired` in the "same container" test.
- **UX review F4 (pre-existing, out of scope).** `messages.signIn` is defined but the session-expired placeholder offers no sign-in control; a global session-expiry flow may own that recovery. No behaviour change was permitted here.
- **Unrelated lint warnings.** Two `import/no-duplicates` warnings in `src/features/tracker/presentation.test.ts` pre-date this change; lint reports 0 errors.
- **Pre-existing conflict markers on `main` (integrator attention).** `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds committed markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java` at HEAD (`d345f387`); `git show HEAD:<path>` confirms they are committed, not a working-tree artifact of this merge (`Already up to date`). My three scoped files contain no markers. Out of scope here; flagged for the integrator/owner.

## Lessons for workers

- Before adding `useWindowClass()` to a shared patient-app component, check the shape of the global `jest.setup.ts` safe-area mock: it exposes `useSafeAreaInsets` only on `default`, so a named import breaks every existing test that renders the component. Reading `windowClassFor()` plus the safe-area hook directly (with a module-load fallback) keeps out-of-scope tests green.
- React Native 0.88 `ViewStyle` fields are readonly: build the conditional style with a spread return (`{ ...safeArea, maxWidth: ... }`) instead of mutating a typed object, or `tsc --noEmit` fails with `TS2540`.
- The module gate's `tsc --noEmit` can be green while the root `scripts/frontend-check.sh` typecheck fails: the root tsconfig enables `exactOptionalPropertyTypes`. Never pass `prop={maybeUndefined}` to an optional prop; make the helper parameter required or spread a conditional object.
- A state placeholder is not a list-plus-detail screen; applying the two-pane rule to it would be wrong. Keep responsive changes presentation-only and leave `ready` children untouched.
- A simulated UX reviewer can raise real localization gaps that the story's "do not change text" rule forbids you to fix; record them under Findings rather than silently widening scope.

```task-requests
[
  {
    "title": "Normalize the patient-mobile safe-area Jest mock export shape",
    "details": "apps/patient-mobile/jest.setup.ts mocks react-native-safe-area-context with jest.requireActual('react-native-safe-area-context/jest/mock'), whose useSafeAreaInsets is only on the default export. Named imports therefore throw in tests that do not add their own mock. Re-export useSafeAreaInsets (and SafeAreaInsetsContext) as named exports from the shared mock so components can call the public API directly; then remove the module-load fallback in src/app/screenStates.tsx (RESP-APP-SCREEN-STATES). Keep 100% coverage.",
    "scope": ["apps/patient-mobile/jest.setup.ts", "apps/patient-mobile/src/app/screenStates.tsx", "apps/patient-mobile/src/app/screenStates.tablet.test.tsx"],
    "depends": []
  },
  {
    "title": "Localize the patient app correlation-id reference label",
    "details": "ScreenStateView renders the hard-coded English text `Reference: ${correlationId}` in the error state (apps/patient-mobile/src/app/screenStates.tsx). localization docs require a message-bundle key per string and an isolated Latin run for the id. Add a `reference` label to ScreenMessages and both locale bundles, render one message with a named placeholder, and update both test files. This was left undone because RESP-APP-SCREEN-STATES forbids text changes and limits scope to two files.",
    "scope": ["apps/patient-mobile/src/app/screenStates.tsx", "apps/patient-mobile/src/app/messages.ts", "apps/patient-mobile/src/app/screenStates.test.tsx", "apps/patient-mobile/src/app/screenStates.tablet.test.tsx"],
    "depends": []
  }
]
```
