# RESP-APP-RELEASED-REPORTS-SCREEN — ReleasedReportsScreen responsive layout

## Summary

Presentation-only responsive layout for `ReleasedReportsScreen`. The screen now derives its
layout from the current window: a centred, capped content column on large windows, safe-area
insets (mirrored under RTL), and a two-pane report row on large windows where the report
summary and its result detail sit side by side (`reports and detail` in decision 0005).
Gutters grow with the system font scale, panes keep a 48 pt minimum, and text is never
truncated or shrunk. No text, API call, exported name, state, cache or dependency changed.

Files (only the scoped paths):
- `apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tsx`
- `apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tablet.test.tsx` (new)

## Affected requirements

| ID | Acceptance in this story | Evidence |
|---|---|---|
| PAT-01 | The window-width classification is reused from `src/app/layout/useWindowClass.ts` (`windowClassFor`), so the same 600/840 dp rule covers appointments and reports on rotation/split-screen. No appointment behavior changed. | Matrix test at compact/regular/large × 1.0/1.5/2.0; `windowClassFor` unit tests unchanged. |
| PAT-02 | No persistent clinical cache, no new storage/state, no changed download flow; revision, issuer, effective date, correction and download labels are unchanged. | Existing `reports-screen.test.tsx` integration test (authorises the exact tapped revision) still passes; source has no storage import. |
| UX-01 | Max readable width centred on large; two panes on large; 48 pt panes and 48 pt download target; safe-area insets respected; no `numberOfLines`/`adjustsFontSizeToFit`/`maxFontSizeMultiplier < 2`; identical accessibility-label order; RTL mirrors insets and row direction. | `ReleasedReportsScreen.tablet.test.tsx`: 9-case matrix + Arabic RTL case + no-result/no-provider case. |

Decision 0005 (patient tablets) is the governing design record; `docs/design/accessibility-patterns.md`
section 9 (target size, text scaling, RTL) and `docs/design/rtl-and-localization.md` section 1
(logical properties) were applied.

## Risk class

Routine. Presentation only — no clinical decision, authorization, data, contract or release change.
Specialist routing: bounded advisory plan from `smartclinic-senior-mobile` (report only, no edits);
QA/integration gate remains with the integrator. No clinical or human participant evidence.

## Review owner

Senior React Native and Expo specialist (advisory plan received). Independent QA is the integrator's
full gate; no named clinician/patient review was available and none is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event touched. The component remains
presentational: all report data and download authorization still arrive from the authenticated client.

## Migration and rollout considerations

None. Existing app users keep the same data flow; the responsive structure is a pure render change.

## Test evidence

All commands were executed by the primary agent (tool evidence). Entry-point-to-test trace:
`ReleasedReportsScreen` → `ScreenFrame` → `reports-content` wrapper → `report-panes-<id>` →
`report-summary-<id>` / `report-detail-<id>`; the new test renders the exported screen directly,
drives `Dimensions.get` (via `useWindowDimensions`), injects `SafeAreaInsetsContext`, and asserts
the rendered tree and accessibility labels. No simulated user or human participant was involved.

- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → `added 1408 packages` (exit 0).
- `./node_modules/.bin/jest --ci --runInBand src/features/reports/ReleasedReportsScreen.tablet.test.tsx src/features/reports/reports-screen.test.tsx`
  → `Test Suites: 2 passed, 2 total`, `Tests: 21 passed, 21 total` (red first: 11 failures on the
  unimplemented structure; existing 10 report tests stayed green throughout).
- `npm run typecheck` final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  (no output after the command — exit 0, zero `error TS…`).
- `npm run lint` final lines: `✖ 2 problems (0 errors, 2 warnings)`; both warnings are pre-existing
  `import/no-duplicates` in `src/features/tracker/presentation.test.ts`, not in a scoped file.
- `bash apps/patient-mobile/check.sh` → exit 0. Final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  > patient-mobile@0.1.0 lint
  > eslint .
  > patient-mobile@0.1.0 test:coverage
  All files          |     100 |      100 |     100 |     100 |
  Test Suites: 63 passed, 63 total
  Tests:       660 passed, 660 total
  ```
- `git merge --no-edit origin/main` → `Already up to date.` (run at start, after implementation and
  before finish); no conflict markers in scoped files.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (`npm ci` → typecheck → lint →
`npm run test:coverage -- --ci` with the module's 100% global threshold).
Result: `ReleasedReportsScreen.tsx` `100 | 100 | 100 | 100`; `All files` 100/100/100/100;
660 tests green. Test files are excluded from the source scope by `jest.config.ts`; no threshold,
exclusion, `@Generated`/ignore comment or skip was added.

Every new branch and the test that takes it:

| New branch | Test |
|---|---|
| `windowClass === 'large'` true → `maxWidth: 960`, row panes, `flex: 1` panes | Matrix cases `large / 1`, `large / 1.5`, `large / 2`; Arabic RTL case; no-result case |
| `windowClass === 'large'` false → no `maxWidth`, column panes, natural-height panes | Matrix cases for `compact` and `regular` |
| `locale === 'ar'` true → `paddingStart` from `insets.right`, `paddingEnd` from `insets.left` | `mirrors the panes, safe-area insets and row direction under Arabic RTL` |
| `locale === 'ar'` false → LTR inset mapping | All 9 matrix cases |
| `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context defined | New tablet test's module mock + provider |
| `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context undefined | Existing `reports-screen.test.tsx` (global Jest setup mock) |
| `useContext(...) ?? NO_INSETS` — non-null insets | Matrix + RTL cases (provider value supplied) |
| `useContext(...) ?? NO_INSETS` — null insets | `keeps a single summary pane … and tolerates no provider` (provider value `null`) |
| `report.result === undefined` true/false | No-result case / matrix cases (result Card present) |

Unchanged branches (`error`, empty state, `withdrawn`, optional `title`, `correction`, `isRtl`,
`locale` default, `showTabBar`) remain covered by `reports-screen.test.tsx` and the matrix.
Non-branch additions (content/pane styles, `columnGap` arithmetic, `flexWrap` on pills) execute in
every matrix render.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or
critical result was changed. Presentation-only; no domain contract or state machine changed.
A named VoiceOver/TalkBack pass on device remains a separate launch gate and is not claimed here.

## Findings

1. **Two-pane interpretation (decision, not a defect).** `ReleasedReportsScreen` has no detail prop
   or selection state, so a screen-level list/detail split would require new interaction and risk
   duplicating accessibility labels. The story explicitly names "reports and detail" as a
   list-plus-detail screen; the implementation therefore splits each report row into a summary
   pane (identity, pills, download) and a result-detail pane on large windows. DOM order, labels
   and the existing `report-<id>` direction style are unchanged, so focus order and all existing
   tests are preserved. No new prop, state or exported name.
2. **Out-of-scope test-infrastructure defect (not fixed).** `apps/patient-mobile/jest.setup.ts`
   mocks `react-native-safe-area-context` with `jest.requireActual('.../jest/mock')`, which exposes
   only a `default` export; named `useSafeAreaInsets` is therefore `undefined`. Calling
   `useWindowClass()` from this component made the existing `reports-screen.test.tsx` renders throw
   `TypeError: useSafeAreaInsets is not a function`. Because `jest.setup.ts` is outside this task's
   scope, the component uses `useWindowDimensions()` + the shared pure `windowClassFor` and reads
   `SafeAreaInsetsContext` (with a zero-inset fallback) instead. Production still reacts to
   rotation/split-screen and still receives real safe-area insets; the new test supplies its own
   context so the four inset edges are asserted. A future task should repair the global mock.
3. **Pre-existing, untouched.** `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/*`
   contains committed conflict markers at HEAD (`git grep -nE '^(<<<<<<<|>>>>>>>) '`); unrelated to
   this story and left unchanged. `eslint` reports two pre-existing warnings in
   `src/features/tracker/presentation.test.ts`.
4. No dependency was added; no file outside the two scoped paths was changed.

## Lessons for workers

- In `apps/patient-mobile`, the global safe-area Jest mock only exposes `default`. Before calling
  `useWindowClass()` (or any hook using `useSafeAreaInsets`) from a component with existing tests,
  check that those tests mock the module; otherwise use `useWindowDimensions` + `SafeAreaInsetsContext`
  with a fallback, and assert insets via a provider in the new test.
- `@testing-library/react-native` v14's `render` is async and there is no `UNSAFE_queryAllByType`;
  use `await render(...)` and walk `view.toJSON()` for DOM-order/accessibility assertions.
- Keep the original row style a flat `ViewStyle` object: existing tests read
  `getByLabelText('report-<id>').props.style.direction` directly; wrapping it in an array breaks them.
