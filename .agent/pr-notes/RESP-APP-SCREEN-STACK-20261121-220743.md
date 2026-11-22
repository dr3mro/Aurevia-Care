# RESP-APP-SCREEN-STACK — Patient app `ScreenStack` responsive layout

## Summary

Made the shared patient-app push/pop stack present correctly on compact, regular
and large windows in portrait and landscape at font scale 1.0, 1.5 and 2.0.
Large windows now show a centred two-pane list/detail layout (menu beside the
active screen); narrower windows keep the original one-pane push/pop. Insets are
applied through logical edges, the row mirrors once under native RTL, controls
keep a 44 pt minimum in both axes, and text is never shrunk or truncated. Stack
state, labels, roles, focus order, data flow and exported names are unchanged;
no dependency, API call, text or clinical cache was added.

Files (scope only): `apps/patient-mobile/src/app/ScreenStack.tsx`,
`apps/patient-mobile/src/app/ScreenStack.tablet.test.tsx`.

## Affected requirements

- PAT-01 (appointments/self-access presentation), PAT-02 (no persistent clinical
  cache — stack keeps only ephemeral route-key strings), UX-01 (native a11y,
  200% text, RTL), decisions `0005-patient-app-tablets.md`.

## Risk class

Cross-boundary / safety-adjacent presentation change: it touches a shared
component mounted by the Messages and Account tabs, and the RTL and text-scaling
rules are UX-01 acceptance. No authorization, data or clinical workflow changed.

## Review owner

Senior React Native and Expo (implementation), UX/a11y reviewer (layout, RTL and
screen-reader semantics). Both were simulated reviewer subagents, not humans.

## Data changes

None. No migration, backfill, table, storage key or persistence. PAT-02 preserved:
`useState` holds route keys only and `render()` stays lazy.

## Authorization boundaries and audit events

No change. No tenant, role or patient-link scope; no audit event added.

## Migration and rollout considerations

None. Presentation-only change behind the existing component import; no tag,
deployment or feature flag. Orientation/tablet build configuration belongs to
the already-completed RESP-APP-CONFIG task and was not touched.

## Test evidence

Evidence provenance: **tool** for all commands below; **simulated reviewer** for
the two bounded subagent reviews. No human, clinical or patient-participant
evidence is claimed; VoiceOver/TalkBack and Arabic clinical passes remain G5.

- `npx jest src/app/ScreenStack.tablet.test.tsx src/app/ScreenStack.test.tsx --ci`
  → `Test Suites: 2 passed, 2 total`, `Tests: 19 passed, 19 total`.
- `npm run typecheck` (final lines):
  `> patient-mobile@0.1.0 typecheck`
  `> tsc --noEmit`
  (exit 0, no diagnostics).
- `bash apps/patient-mobile/check.sh` → `EXIT=0`, final lines:
  `Test Suites: 63 passed, 63 total`
  `Tests:       664 passed, 664 total`
  `Ran all test suites.`
  Typecheck final lines from that gate:
  `> patient-mobile@0.1.0 typecheck`
  `> tsc --noEmit`
  Lint final line: `✖ 2 problems (0 errors, 2 warnings)` — both pre-existing
  `import/no-duplicates` warnings in `src/features/tracker/presentation.test.ts`,
  outside this task's scope. Coverage final lines:
  `All files          |     100 |      100 |     100 |     100`
  `  ScreenStack.tsx  |     100 |      100 |     100 |     100`
- `FRONTEND_AUDIT=skip bash scripts/frontend-check.sh` → `EXIT=0`,
  `frontend standards: policy and tool commands passed` (audit skipped locally;
  the integrator/Delivery runs the full audited gate).
- `python3 -I scripts/check-secrets.py`, `check-names.py`, `check-wiring.py` →
  PASS.
- `scripts/check.sh` was deliberately **not** run: it invokes the full
  `apps/api` Maven verify (>10 minutes, integrator-owned) in this worker.

Entry-point-to-test trace: `RootNavigator.tsx:130` (Messages) and `:217`
(Account) mount `ScreenStack`; `RootNavigator.test.tsx` proves composition;
`ScreenStack.test.tsx` proves push/pop at the default 750 dp (regular) window;
`ScreenStack.tablet.test.tsx` drives `Dimensions.get('window')` and
`I18nManager.isRTL`, the component derives the class with `windowClassFor`, and
the assertions reach the single-pane / two-pane view, insets, roles, labels,
touch targets and RTL direction.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (Jest coverage, all production
sources). Result: `ScreenStack.tsx` 100% statements, branches, functions and
lines; `All files` 100% in all four metrics. No threshold, exclusion or skip was
changed; no test was disabled.

Each new or changed branch and its covering test:

| Branch in `ScreenStack.tsx` | Covering test |
|---|---|
| `readInsets ?? (() => NO_INSETS)` — hook present | `ScreenStack.tablet.test.tsx` (named `useSafeAreaInsets` mock) |
| same — fallback when mock is default-only | existing `ScreenStack.test.tsx` (global `jest.setup.ts` mock shape) |
| `isLarge && styles.readable` — large | `...tablet.test.tsx` large matrix rows, two-pane, push and RTL tests |
| same — compact/regular | `...tablet.test.tsx` compact/regular rows and one-pane tests; existing `ScreenStack.test.tsx` |
| `isRTL ? insets.right : insets.left` (paddingStart) | RTL test (true), matrix rows (false) |
| `isRTL ? insets.left : insets.right` (paddingEnd) | RTL test (true), matrix rows (false) |
| `direction: isRTL ? 'rtl' : 'ltr'` | RTL test (true), matrix rows (false) |
| `{atRoot ? null : back}` in detail pane | two-pane test (root), push test (pushed) |
| `{atRoot ? menu : back}` in single pane | matrix/one-pane tests (root), existing `ScreenStack.test.tsx` (pushed) |
| `routes.filter(route => route.key !== rootKey)` both sides | matrix and existing tests (root is filtered; first/second rendered) |
| push and back `setStack` callbacks | existing `ScreenStack.test.tsx` and `...tablet.test.tsx` push test |
| `Math.max(...)` inset clamps (no JS branch) | matrix rows assert padding ≥ each inset |

The removed `I18nManager.isRTL ? 'row-reverse' : 'row'` branch is gone: `row`
already mirrors under native RTL, so reversing explicitly double-flipped the
panes (simulated UX and senior-mobile review finding).

## Clinical and architecture review

No signed data, cross-organization sharing, patient association, prescribing or
critical result is involved. Simulated UX and senior-mobile reviews were
performed; their actionable findings were fixed before the final gate (see
Findings). Real screen-reader and Arabic clinical acceptance are separate G5
gates and are not claimed here.

## Findings

- Fixed during bounded review (both agents, tool-verified afterwards):
  1. RTL panes were explicitly reversed with `row-reverse` while native RTL
     layout already mirrors `row`, so Arabic would show LTR order. Now `row`
     plus an explicit `direction: rtl` on the container; the RTL test asserts
     `flexDirection: 'row'` and `direction: 'rtl'`.
  2. Physical `paddingLeft`/`paddingRight` applied physical insets; replaced
     with `paddingStart`/`paddingEnd` mapped direction-aware so the notch inset
     follows the start/end edge (RTL test asserts the swapped mapping).
  3. Two "landscape" matrix rows used width 900 dp, which `windowClassFor`
     classifies as **large**, so compact/regular landscape were never tested.
     Landscape widths are now 520/760/1024 dp and each row asserts its pane
     structure.
  4. The 960 dp reading cap sat only on the panes; moved to the container so
     the heading shares it, and the test now asserts `maxWidth === 960`.
- Dispositioned: the accessibility guide requires 48 dp Android targets while
  this story's acceptance says "at least 44 by 44 points". Kept 44 as the
  explicit story floor (it satisfies "at least 44"); if 48 dp is required on
  Android, an owner decision and a follow-up story should change it with a
  platform-specific test.
- Harness coupling: `jest.setup.ts` globally mocks
  `react-native-safe-area-context` with a default-only export, so the named
  `useSafeAreaInsets` is undefined in tests that do not override it. The
  component resolves the hook at call time with a zero-inset fallback; both
  sides are covered today, but the fallback becomes unreachable if that mock is
  fixed. Requested as a task below (outside this worker's scope).
- No defect in production behaviour found by the reviews after fixes. No
  patient data, credential or raw log appears in this note.

## Lessons for workers

- Check that a mocked breakpoint width actually lands in the class the test
  claims: `windowClassFor` uses 600/840 dp, so a "compact landscape" 900 dp
  row is really a large-window test.
- Under native RTL, `flexDirection: 'row'` mirrors by itself. Adding
  `row-reverse` on `isRTL` double-flips; prefer logical `paddingStart`/`End`
  and map physical safe-area insets by direction.
- A Jest mock that sets only `exports.default` leaves named imports undefined;
  resolve such hooks defensively or fix the harness in its own task.
- Style-prop assertions in Jest are not evidence of painted RTL layout; record
  them as simulated checks and leave VoiceOver/TalkBack and Arabic clinical
  passes to the G5 gate.

```task-requests
[
  {
    "title": "Fix patient-mobile safe-area Jest mock to expose named exports",
    "details": "jest.setup.ts mocks react-native-safe-area-context with jest.requireActual('react-native-safe-area-context/jest/mock'), which returns a default-only object; named useSafeAreaInsets is undefined. Change the factory to spread the mock's default into named exports (or add a named useSafeAreaInsets) so components can import it normally. Then remove the zero-inset fallback in ScreenStack.tsx (useScreenInsets) and keep 100% coverage. Evidence: ScreenStack.tablet.test.tsx needs its own named override today; the fallback branch is covered only by ScreenStack.test.tsx's broken mock shape.",
    "scope": ["apps/patient-mobile/jest.setup.ts", "apps/patient-mobile/src/app/ScreenStack.tsx"],
    "depends": ["RESP-APP-SCREEN-STACK"]
  }
]
```
