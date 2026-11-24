# RESP-APP-PATIENT-RESOURCE — tablet, landscape and large-text layout for PatientResource

## Summary

The shared patient resource boundary (`PatientResource`) now renders its state
views inside a presentational responsive container. The current window width
selects a single column (compact/regular) or a two-pane row (large, where a
list plus a detail can sit side by side), content is capped at 1200 dp and
centred on large windows, safe-area insets are applied through logical
`paddingStart`/`paddingEnd` (swapped for RTL), and no fixed heights, line caps
or font multipliers are introduced, so text reflows at 200%. A new synthetic
test file renders every window class × font scale × orientation, asserts the
structure and accessibility labels, and covers the RTL branch. No data, cache,
navigation, API call, exported name, text or dependency changed.

## Affected requirements

- PAT-01 — appointments list layout: window-driven two-pane-capable container,
  capped/centred large-window content, 44 pt floors, 200% text reflow.
- PAT-02 — released reports layout and no persistent clinical cache: the change
  is presentational only; the loader still keeps one in-flight request and no
  clinical data between requests. No storage, cache or persistence added.
- UX-01 — native accessibility and RTL: state-view roles/labels/focus order are
  untouched, insets use logical properties, and the RTL inset mapping is tested.

Acceptance mapping:

| Story step | Where satisfied | Test |
|---|---|---|
| compact/regular/large, portrait/landscape, font scale 1.0/1.5/2.0 | `contentStyle` from `windowClassFor(width)`; no scale branch | `lays out $windowClass at font scale $fontScale (landscape=$isLandscape)...` (18 cases) |
| max readable width, centred on large | `maxWidth: 1200`, `alignSelf: 'center'` only when large | same matrix |
| two panes on large for list+detail | `flexDirection: 'row'` only when large | `shows a list and a detail as two panes on a large window and stacks them when compact` |
| 44×44 touch targets | `minHeight: 44` on the container; existing ScreenStateView control keeps `minHeight: 44` | matrix + `keeps state-view accessibility roles, labels and target size...` (9 cases) |
| nothing clipped at 200% text | no `height`, `maxHeight`, `numberOfLines`, `maxFontSizeMultiplier` or `adjustsFontSizeToFit` added | matrix asserts those style keys are absent |
| safe-area insets respected | `paddingTop`/`paddingBottom` and logical `paddingStart`/`paddingEnd` from the inset hook | matrix (LTR) + RTL test |
| focus order / screen-reader labels unchanged | wrapper has only `testID`, no accessibility props | `keeps state-view accessibility roles, labels and target size...` |
| right-to-left mirrors | inset pair swapped with `I18nManager.isRTL`; logical properties only | `mirrors the safe-area insets with logical padding under right-to-left` |
| no persistent clinical cache (PAT-02) | no storage/cache code added; loader logic byte-for-byte unchanged | existing `PatientResource.test.tsx` suite (late responses discarded, one request at a time) |

## Risk class

Routine (contained presentational change to one shared patient component and one
new test file). No auth, clinical, release or data-boundary change. Reviewed by
a bounded simulated senior-mobile specialist; no human, clinical or patient
feedback is claimed.

## Review owner

Senior React Native and Expo (simulated `orevia-senior-mobile` subagent,
session `ses_dffd17877ffeTUEyWk9it2YsJa`); outcome recorded in Findings.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None changed. The component still delegates 401 handling to `onSessionExpired`
and never reads or stores a patient identifier itself. No audit event added or
changed. All test data is synthetic (`SYN-ROW-1`, `SYN-ROW-2`, `SYN-ROW`).

## Migration and rollout considerations

None. Pure client presentation change; safe to roll back by reverting the two
files. RTL depends on the existing app-start `I18nManager.forceRTL` boundary and
the existing build-level RTL configuration (decision 0005 / UX-01), neither of
which this change alters.

## Test evidence

Environment: Node v22.23.5, npm 10.9.9, branch `swarm/RESP-APP-PATIENT-RESOURCE-495`,
HEAD `36cf64ce`, clean tree before edit. All commands run from the repo root
unless noted.

- Red phase: `cd apps/patient-mobile && npx jest src/app/PatientResource.tablet.test.tsx --runInBand --no-coverage` → 21 failed / 21 passed 0, `Unable to find an element with testID: patient-resource-layout` (wrapper absent).
- Green targeted: `npx jest src/app/PatientResource.tablet.test.tsx src/app/PatientResource.test.tsx src/app/patientPages.test.tsx --runInBand --no-coverage` → 3 suites passed, 57 tests passed.
- Typecheck (`cd apps/patient-mobile && npm run typecheck`) final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  Exit 0, no `error TS…`.
- Lint on touched files: `npx eslint src/app/PatientResource.tsx src/app/PatientResource.tablet.test.tsx` → no output, exit 0. (`npm run lint` reports two pre-existing warnings in `src/features/tracker/presentation.test.ts`, unrelated and untouched.)
- Root worker safeguard: `bash scripts/frontend-typecheck-check.sh` → exit 0,
  `frontend typecheck: passed (or no frontend files changed)`; the only lint
  output is the two pre-existing `features/tracker/presentation.test.ts`
  warnings.
- Mandatory gate: `bash apps/patient-mobile/check.sh` → exit 0. Final lines:
  ```
  Test Suites: 63 passed, 63 total
  Tests:       678 passed, 678 total
  Snapshots:   0 total
  Time:        15.641 s
  Ran all test suites.
  ```
  Re-run for the recorded exit code: `bash apps/patient-mobile/check.sh` → `check.sh EXIT=0`.
- Entry-point-to-test trace: `RootNavigator` → `AppointmentListPage` /
  `ReleasedReportsPage` / `ConversationInboxPage` (`app/patientPages.tsx`) →
  `PatientResource` → `ScreenStateView`. The new file drives the same component
  directly at the public props boundary; `patientPages.test.tsx` proves the
  existing callers still render through it.

Evidence sources: executed tool commands (Jest, tsc, eslint, check.sh) and the
generated `coverage/coverage-final.json`; one simulated senior-mobile reviewer.
No reviewer, user or human-participant claim beyond that.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (pinned install, `tsc --noEmit`,
lint, Jest with global 100% thresholds). Result: `All files | 100 | 100 | 100 | 100`,
63 suites / 678 tests green. `coverage/coverage-final.json` confirms
`src/app/PatientResource.tsx` at 100% statements, 100% functions and every
branch with both sides executed (line numbers from the produced file):

| Branch | Side A — test | Side B — test |
|---|---|---|
| `??` inset-hook resolution (L53: named export vs default export) | my new test mocks `react-native-safe-area-context` with the named `useSafeAreaInsets` | existing `PatientResource.test.tsx`, `patientPages.test.tsx` and all other suites hit the global headless mock's `default.useSafeAreaInsets` |
| `isEmpty(data) ? empty : ready` (L95, pre-existing) | `shows the empty message...` (existing suite) | `shows loading and then the loaded rows` (existing + all new ready-state cases) |
| `if (active)` on success (L94, pre-existing) | all resolved loads | `ignores a late success after the component has unmounted` (existing suite) |
| `if (!active) return` on failure (L99, pre-existing) | `ignores a late failure after the component has unmounted` (existing suite) | all in-flight failures |
| `if (failure.kind === 'unauthenticated')` (L103, pre-existing) | `calls onSessionExpired once when the transport reports 401` (existing suite) | `shows the error state...`, `maps an unknown thrown value to the offline state`, new `keeps state-view...` cases |
| `maxWidth: isLarge ? 1200 : undefined` (L132) | matrix `windowClass=large` (6 cases) | matrix compact/regular (12 cases) |
| `alignSelf: isLarge ? 'center' : 'stretch'` (L133) | matrix large | matrix compact/regular |
| `flexDirection: isLarge ? 'row' : 'column'` (L134) | matrix large + two-pane test | matrix compact/regular + two-pane test |
| `justifyContent: isLarge ? 'center' : 'flex-start'` (L135) | matrix large | matrix compact/regular |
| `paddingStart: isRtl ? insets.right : insets.left` (L139) | `mirrors the safe-area insets...` (isRTL=true → right) | matrix (isRTL=false → left) |
| `paddingEnd: isRtl ? insets.left : insets.right` (L140) | `mirrors the safe-area insets...` (isRTL=true → left) | matrix (isRTL=false → right) |

No threshold, exclusion, skip, `@Generated` or ignore comment was added. No
existing test was weakened or rewritten.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical result is touched. The patient self-access boundary
(PRD-03) is unchanged; PAT-02's "no persistent clinical cache" is preserved by
construction. Real patient/device accessibility acceptance remains a G5 human
gate and is not claimed here.

## Findings

- The app's global headless Jest setup mocks `react-native-safe-area-context`
  with `jest.requireActual('react-native-safe-area-context/jest/mock')`, whose
  CommonJS module exposes `useSafeAreaInsets` only under `default`. Calling the
  existing `useWindowClass()` hook (named import) from `PatientResource` made all
  existing `PatientResource` and `patientPages` suites fail with
  `useSafeAreaInsets is not a function`. Fixing that mock would require editing
  `jest.setup.ts`, which is outside this task's scope. The in-scope solution
  reuses the pure `windowClassFor` classifier from `layout/useWindowClass.ts`,
  reads `useWindowDimensions()` directly and resolves the inset hook across the
  named/default export shapes at module load. This keeps one window-width rule
  and one inset source; the classifier is not duplicated, and the resolved
  function is stable per module so hook order cannot change between renders.
- The container is layout-only. It makes any two sibling ready-state panes a
  row on large windows, but the individual list/detail screens own their own
  pane split; wiring an actual inbox+thread or appointment+detail pair into two
  panes belongs to the screen stories, not to this shared loader.
- `scripts/check.sh` was deliberately not run: it loops through `apps/api/check.sh`,
  which is `mvn -q -B verify` (full unit + integration + OWASP/SpotBugs/JaCoCo),
  and the task explicitly forbids the full Java verify and every-integration-test
  commands for a worker. The applicable TODO step-6 gate for this module,
  `bash apps/patient-mobile/check.sh`, was run to completion with exit 0. The
  lightweight policy checks that apply to the notes and names
  (`python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`) also
  pass with exit 0. The integrator/Delivery owns the full repository gate.
- Simulated senior-mobile review (`orevia-senior-mobile`,
  `ses_dffd17877ffeTUEyWk9it2YsJa`, static review only — no commands executed in
  that session): layout logic, RTL mapping, hook-order stability, branch
  coverage and the named/default inset resolution were judged sound. It raised
  one claimed production blocker — "no `SafeAreaProvider` is mounted, so the
  real `useSafeAreaInsets` throws". **Disproved by source inspection**: the
  patient tabs run inside `NavigationContainer` → `Tab.Navigator`, and
  `@react-navigation/bottom-tabs` `createBottomTabNavigator.tsx:56` renders
  `BottomTabView`, whose `BottomTabView.tsx:269` wraps all screen content in
  `SafeAreaProviderCompat`; that component
  (`@react-navigation/elements/src/SafeAreaProviderCompat.tsx`) mounts a real
  `SafeAreaProvider` with `initialWindowMetrics` when no context is present.
  Therefore the inset hook resolves against a provider on every production
  mount path (`RootNavigator` → tabs → `patientPages` → `PatientResource`). No
  app-root change is needed and none is in scope. Advisory items from the same
  review: pane children size to content unless they set `flex: 1` (the real
  screens' `ScreenFrame` does), the pre-existing retry control has only a
  `minHeight` floor (fixing `minWidth` needs `screenStates.tsx`, outside scope),
  and font scale is absorbed by the absence of fixed heights rather than by a
  branch. None is a regression introduced by this change.

## Lessons for workers

- Before a patient-app component calls a hook that reads safe-area insets,
  check whether the headless Jest mock exposes the named export; the global
  setup here only provides it under `default`, so a direct `useWindowClass()`
  call breaks otherwise unrelated suites.
- Testing Library cleans up per `it`: unmount between two renders in the same
  test (wrapped in `await act(async () => …)`) or overlapping `act()` warnings
  hide the state update you are asserting.
- `render()` in this RNTL version is async: type helpers as
  `Awaited<ReturnType<typeof render>>`, otherwise `tsc` reports
  `getByTestId does not exist on Promise<RenderResult>`.
- Prefer reusing the pure classifier (`windowClassFor`) when the hook that wraps
  it depends on a mock unavailable in headless tests; that keeps the single
  window-width rule without duplicating the breakpoint logic.
