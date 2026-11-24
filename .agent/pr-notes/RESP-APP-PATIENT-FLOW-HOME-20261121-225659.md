## Summary

Responsive presentation for the patient flow home on tablets and landscape windows
and at 200% text. `PatientFlowHome` now derives its layout class from the shared
window-width classifier, bounds and centres content on large windows, honours
safe-area insets (logical `paddingStart`/`paddingEnd` that mirror in RTL), wraps
so text reflows, and gives every pressable a 48 pt/dp minimum target. A new synthetic test renders the component across six window
configurations (each layout class in portrait and landscape) at font scales
1.0, 1.5 and 2.0, plus selection/settings, Arabic RTL and empty-flow cases.
No behaviour, text, API call, exported name or dependency changed and no
clinical cache was added (PAT-02).

## Affected requirements

- PAT-01 — appointments flow remains reachable with identical label, press
  handler and generic notice preview.
- PAT-02 — presentation only; no persistent clinical cache, no new credential
  or download path; secure-storage wording unchanged.
- UX-01 — English/Arabic RTL mirroring (logical insets and `row-reverse`),
  unchanged screen-reader labels and focus order, 44 pt/48 dp touch targets,
  safe-area insets and 200% text reflow.
- Decision `docs/decisions/0005-patient-app-tablets.md` (window width is the
  single layout input; two panes only for genuine list-plus-detail screens).

## Risk class

Routine (contained presentation change in one patient-app component). Reviewed
by the senior-mobile and UX specialists; no clinical data, authorization,
migration or API boundary changed.

## Review owner

Senior React Native and Expo (`orevia-senior-mobile`); UX/accessibility
(`orevia-ux-reviewer`). Both reviews are simulated specialist review, not
human or patient evidence.

## Data changes

None. No migration, backfill, schema, lock or volume impact. No real or
synthetic patient record is stored; all fixtures are literals in the tests.

## Authorization boundaries and audit events

Unchanged. The component still renders only the server-authored
`FlowPresentation` and never derives locale, authorization or clinical content.
No audit event added or removed.

## Migration and rollout considerations

None. Presentation-only change; no feature flag, migration or rollout gate.
Reverts cleanly to the previous single-file component and test.

## Test evidence

Evidence source: executed tool commands (no human, clinical or patient
participant). Entry-point to test trace: the component is rendered directly by
`src/features/flows/patient-flow-home.test.tsx` (existing) and
`src/features/flows/PatientFlowHome.tablet.test.tsx` (new); its window class is
computed by the shared `windowClassFor` classifier, independently exercised in
`src/app/layout/useWindowClass.test.ts`. No prop, text or handler changed, so
existing navigation/patient-page consumers are unaffected.

Red phase (new test on the unmodified component):

- `npx jest src/features/flows/PatientFlowHome.tablet.test.tsx --no-coverage --runInBand`
  → Test Suites: 1 failed, 1 total; Tests: 20 failed, 1 passed, 21 total.
  Failing assertions were the new responsive styles (`flexWrap`, `maxWidth`,
  `alignSelf`, inset padding) and touch-target sizes.

Green phase (after the smallest change, then after the UX reviewer's RTL
padding fix — identical final results):

- `npx jest src/features/flows --no-coverage --runInBand` →
  Test Suites: 3 passed, 3 total; Tests: 40 passed, 40 total.
- `npm run typecheck` (`tsc --noEmit`) → exited 0, no `error TS…`.
- `npm run lint` (`eslint .`) → 0 errors; 2 pre-existing `import/no-duplicates`
  warnings in `src/features/tracker/presentation.test.ts` (untouched, out of
  scope).
- `bash apps/patient-mobile/check.sh` → EXIT 0. Final lines:
  `> patient-mobile@0.1.0 typecheck`, `> tsc --noEmit`,
  `> patient-mobile@0.1.0 lint`, `> eslint .`,
  `✖ 2 problems (0 errors, 2 warnings)`,
  `> patient-mobile@0.1.0 test:coverage`,
  `All files          |     100 |      100 |     100 |     100 |`,
  `Test Suites: 63 passed, 63 total`,
  `Tests:       670 passed, 670 total`,
  `Time:        6.239 s`,
  `Ran all test suites.`

## Coverage

Command: `bash apps/patient-mobile/check.sh` (Jest with the module's enforced
100% global threshold: branches/functions/lines/statements all 100; no
threshold, exclusion or skip changed).

Result: `All files 100/100/100/100`; `...features/flows 100/100/100/100`;
`...tFlowHome.tsx 100/100/100/100`. No test skipped or disabled.

New/changed branches and the test that takes each side:

1. `windowClass === 'large'` → `maxWidth: MAX_READABLE_WIDTH` and
   `alignSelf: 'center'`. True: `PatientFlowHome.tablet.test.tsx` large
   portrait, large landscape, large RTL and empty-flow-large cases. False:
   compact portrait, compact landscape, regular portrait and regular landscape
   cases assert `maxWidth: '100%'` and `alignSelf: 'stretch'`.
2. `typeof SafeArea.useSafeAreaInsets === 'function'` → real hook vs neutral
   insets. True: the new tablet test's local safe-area mock provides
   `{ top: 24, right: 32, bottom: 48, left: 16 }`, asserted as the four
   paddings. False: the existing `patient-flow-home.test.tsx` runs under the
   global headless mock (named hook absent) and still renders/passes.
3. `presentation.direction === 'rtl'` (pre-existing branch, now computed once
   as `isRtl`) → new RTL case asserts `row-reverse`, swapped logical insets
   (`paddingStart = insets.right`, `paddingEnd = insets.left`), Arabic labels and
   the button focus order; LTR cases assert `row`, `paddingStart = insets.left`
   and `paddingEnd = insets.right` across all 18 matrix renders and the existing
   suite.
4. `flows.length === 0` and
   `presentation.noticeKinds.includes(previewNotice)` (pre-existing branches,
   retained) → new empty-flow case and the existing suite cover both sides.

New production lines (constants, `useFlowHomeLayout`, responsive styles,
touch-target styles) execute in every new-test render; coverage is measured on
the real component with 100% on every metric.

## Findings

- The story's two-pane list-plus-detail rule (inbox/thread, appointments/detail,
  reports/detail) is not applicable to `PatientFlowHome`, which is a flow menu
  with no detail pane; implementing it would require the out-of-scope detail
  screens. The applicable tablet rules (bounded centred width, touch targets,
  200% reflow, insets, RTL, unchanged labels/focus, no cache) are implemented
  and asserted here.
- The repo's shared `jest.setup.ts` mocks `react-native-safe-area-context` with
  the library mock module, which under this pinned version exposes only a
  `default` key; the named `useSafeAreaInsets` binding is therefore undefined
  in tests without a local mock. The existing flow-home test cannot be edited
  (outside scope), so the component resolves the hook once at module load with
  a neutral-inset fallback. This keeps existing tests green and is covered on
  both sides. Touching the shared Jest setup or the existing test was avoided.
- Simulated `orevia-senior-mobile` review: only two Low, non-blocking
  findings — (a) the module-load safe-area fallback is test-shim debt that
  should be deleted when `jest.setup.ts` is fixed in a separate story, and
  (b) `useFlowHomeLayout` duplicates the small body of `useWindowClass` because
  the hook itself cannot be called; a shared pure `layoutFor(...)` helper is a
  future refactor. Confirmed: hooks rules, both sides of the feature-detect
  branch, responsive values, no clipping props, unchanged roles/labels/order
  and exported API, no new dependency or cache.
- Simulated `orevia-ux-reviewer` review: one Medium finding — physical
  left/right padding does not mirror consistently on iOS and Android
  (`docs/design/rtl-and-localization.md` §9.2 / line 40). **Fixed in scope** by
  switching to logical `paddingStart`/`paddingEnd` with direction-aware inset
  values, asserted in both LTR and RTL cases; the full module gate was re-run
  green after the fix. Two Low notes: the 200% test is structural (prop
  absence) rather than a device reflow pass — every rendered text node,
  including header and settings label, is now checked and the real
  VoiceOver/TalkBack pass remains a G5 gate; and `accessibilityLanguage="ar"`
  is a pre-existing gap that was deliberately not added because the story
  forbids behaviour/prop changes — flagged for a follow-up story. Focus order
  of accessible buttons is now asserted in both directions.
- Pre-existing, outside scope and not touched: committed conflict markers in
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7*.java`
  (present in HEAD via `origin/swarm/BUILD-37-469`); two lint warnings in
  `src/features/tracker/presentation.test.ts`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical-result behaviour changed. No human, clinician or real
patient evidence is claimed; the two specialist reviews above are simulated.

## Lessons for workers

- Before making a component call a hook that uses a globally mocked native
  module, inspect the mock's actual export shape (`import * as M` and list
  keys); the shared Jest mock here exposes only `default`, so a named-hook
  call breaks any test that does not locally mock the module.
- Reuse pure helpers from an existing hook module (here `windowClassFor` and
  the `WindowLayout` type) instead of duplicating breakpoint logic, even when
  the hook itself cannot be called directly.
- Prove responsive acceptance by rendering the component with deterministic
  `Dimensions.get` and safe-area mocks across every class/orientation/font
  scale, then assert both style values and that no truncation props exist.
  A headless renderer cannot prove visual reflow; label the test structural and
  keep the device VoiceOver/TalkBack pass as the real acceptance gate.
- Use logical `paddingStart`/`paddingEnd` (with direction-aware inset values)
  in React Native RTL layouts; physical left/right padding mirrors differently
  on iOS and Android and is banned by `docs/design/rtl-and-localization.md`.
  Reword explanatory comments so a grep-style guard cannot match banned tokens.
