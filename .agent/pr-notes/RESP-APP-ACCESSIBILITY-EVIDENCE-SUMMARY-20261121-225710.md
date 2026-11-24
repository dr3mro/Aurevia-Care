# RESP-APP-ACCESSIBILITY-EVIDENCE-SUMMARY — responsive evidence summary

## Summary

Made `AccessibilityEvidenceSummary` present correctly on compact, regular and
large windows in portrait and landscape at font scale 1.0, 1.5 and 2.0, without
changing its text, roles, labels, exported names or data behaviour. The summary
now wraps instead of clipping, maps physical safe-area insets to logical inline
padding (RTL aware), keeps a 44 pt floor, and caps/centres a 720 dp reading
measure on large windows. Added a dedicated tablet test file covering the window
matrix, font scales, RTL mirroring and a rotation event. Only the two in-scope
files were touched; no dependency, migration, API call or cache was added.

## Affected requirements

- **PAT-01 / PAT-02** — presentation only; no persistent clinical cache, no
  download, no authorization or API change. Decision 0005 "Boundaries unchanged".
- **UX-01** — 200% text scaling without clipping, 44 pt targets, safe-area
  insets, unchanged focus order and screen-reader labels, RTL mirroring.
- Spec: `PROJECT_V1_SPEC.md` lines 85–86 and 90; decision
  `docs/decisions/0005-patient-app-tablets.md`; design
  `docs/design/accessibility-patterns.md` §9.

Acceptance mapping: story step 1 → component and `useWindowClass.ts` read;
step 2 → container/text styles and inset mapping; step 3 → new
`.tablet.test.tsx`; step 4 → exports/text/behaviour and dependency list unchanged.

## Risk class

**Routine** (contained presentation change, docs/WORKFLOW.md). No clinical
value, permission or release decision is affected. Served as a bounded
senior-mobile review; simulated review only, not clinical or human acceptance.

## Review owner

Senior React Native and Expo (bounded review requested) plus UX reviewer for the
native accessibility patterns. No real patient or clinician evidence in this
change; per-site accessibility/VoiceOver/TalkBack passes remain a G5 gate.

## Data changes

None. No migration, backfill, table, lock or volume impact, rollback or
forward-fix.

## Authorization boundaries and audit events

Unchanged. No tenant, role or patient-link scope is read or written; no audit
event added or changed.

## Migration and rollout considerations

None. Presentation-only change with no storage or configuration.

## Test evidence

Environment: `apps/patient-mobile`, Node/pinned npm lockfile (`npm ci` via
check.sh). All commands executed by the tool; no human or clinical evidence.

- **Red first** — `npx jest src/features/evidence/AccessibilityEvidenceSummary.tablet.test.tsx --runInBand --no-coverage`
  → `Tests: 11 failed, 11 total`; failures were `Expected: "wrap", Received: undefined`
  (missing responsive styles) and missing inset/large-window values.
- **Green target suites** — `npx jest src/features/evidence/AccessibilityEvidenceSummary.tablet.test.tsx src/features/evidence/index.test.tsx --runInBand --no-coverage`
  → `Test Suites: 2 passed, 2 total`, `Tests: 29 passed, 29 total`.
- **Canonical module gate** — `bash apps/patient-mobile/check.sh`
  → `Test Suites: 63 passed, 63 total`; `Tests: 661 passed, 661 total`;
  `Time: 5.528 s`, `Ran all test suites.` (includes `npm ci`, typecheck, lint,
  Jest with enforced 100% global coverage). The extra test is the review-driven
  focus/reading-order assertion.
- **Typecheck final lines** (`npm run typecheck`):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  No `error TS…` output; exit 0.
- **Lint** (`npm run lint`): `✖ 2 problems (0 errors, 2 warnings)` — both
  pre-existing `import/no-duplicates` warnings in
  `src/features/tracker/presentation.ts`, outside this scope. No errors.
- **Frontend worker re-check** — `bash scripts/frontend-typecheck-check.sh`
  → `frontend typecheck: passed (or no frontend files changed)`, exit 0.
- **Fast policy scripts** — `python3 -I scripts/check-names.py`,
  `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-docs.py`
  → each exit 0.
- **Branch reconcile** — `git merge --no-edit origin/main` at start and before
  finish → `Already up to date.`; no conflict markers in either in-scope file.

Entry point to test trace: the exported component
`AccessibilityEvidenceSummary.tsx:69` is rendered directly by
`AccessibilityEvidenceSummary.tablet.test.tsx` via `render(<AccessibilityEvidenceSummary …/>)`
for six window configurations × font-scale assertions, the RTL case and a
`Dimensions` change event; the legacy `index.test.tsx` still renders the same
export. There is no controller/screen in this scope.

There are no patient identifiers, credentials or real clinical payloads in the
fixtures; the report is produced by the existing synthetic `CONFORMING_ELEMENTS`.

## Coverage

Commands and results:
- `cd apps/patient-mobile && npx jest src/features/evidence --runInBand --coverage --collectCoverageFrom='src/features/evidence/AccessibilityEvidenceSummary.tsx'`
  → `AccessibilityEvidenceSummary.tsx | 100 | 100 | 100 | 100`, `All files | 100 |
  100 | 100 | 100`, `Test Suites: 2 passed`, `Tests: 29 passed`.
- `bash apps/patient-mobile/check.sh` (Jest `--coverage`,
  `coverageThreshold.global` 100/100/100/100 in `jest.config.ts`) → all 63 suites
  pass; the changed production file row reads
  `...ceSummary.tsx | 100 | 100 | 100 | 100` (statements/branches/functions/lines).
No threshold, exclusion, ignore comment or skipped test was added.

New/changed branches and the test that takes each side:

| Branch in `AccessibilityEvidenceSummary.tsx` | Test covering each side |
|---|---|
| `isRTL ? RTL_ROW_STYLE : LTR_ROW_STYLE` | `.tablet.test.tsx` matrix (LTR) and `mirrors direction and inline insets for RTL`; legacy `index.test.tsx` LTR/RTL tests |
| `isRTL ? insets.right : insets.left` (paddingStart) | matrix asserts `paddingStart === insets.left`; RTL test asserts `=== insets.right` |
| `isRTL ? insets.left : insets.right` (paddingEnd) | matrix asserts `paddingEnd === insets.right`; RTL test asserts `=== insets.left` |
| `windowClass === 'large' ? LARGE_CONTAINER_STYLE : {}` | matrix rows `large portrait`/`large landscape` assert `maxWidth`/`alignSelf`; compact/regular rows assert `undefined`; rotation test crosses compact → large |
| `typeof SafeAreaContext.useSafeAreaInsets === 'function' ? real : fallback` | `.tablet.test.tsx` local `jest.mock` supplies the named export (real side); legacy `index.test.tsx` uses the partial global mock (fallback side, also invoking the fallback arrow) |

Font scale needs no production branch: the three scale cases assert on the Text
**props** (not style keys, per the senior-mobile review) that `numberOfLines` and
`maxFontSizeMultiplier` are absent, `adjustsFontSizeToFit` is falsy and
`allowFontScaling` is not `false`, plus the style keys `flexShrink`/`minHeight`/
`minWidth` and the container's `flexWrap: 'wrap'` — so a future clipping
regression fails CI at 200%. The focus/reading-order test asserts the same
ordered `testID` sequence for `isRTL={false}` and `isRTL={true}`, and the rotation
test proves the window class is recomputed from the new width.

## Clinical and architecture review

No signed data, cross-organization sharing, patient association, prescribing or
critical result is involved. A bounded senior-mobile review completed: it was
static only (the reviewer session had no shell/execute tool, so it ran no tests),
and reported three findings. Findings 2 and 3 were accepted and fixed in this
file (font-scale assertions now read Text props, and a focus/reading-order
assertion was added). Finding 1 is pre-existing behavior that this story must
not change (see Findings). This is simulated/synthetic evidence, not clinical or
patient acceptance.

## Findings

1. **Two-pane rule is not applicable here.** Decision 0005 reserves two panes for
   list-plus-detail screens (inbox/thread, appointments/detail, reports/detail).
   This component is a single summary card (title, status, detail), not a list
   plus detail, so no two-pane branch was introduced. Recorded rather than
   invented.
2. **Shared Jest mock is partial (out of scope to fix).** `jest.setup.ts` mocks
   `react-native-safe-area-context` with `jest.requireActual('.../jest/mock')`,
   which exposes only a `default` object; the named `useSafeAreaInsets` is
   `undefined` in tests that do not locally mock it (confirmed with a throwaway
   diagnostic test). The component therefore binds the hook defensively once at
   module load and falls back to zero insets, keeping the legacy test green
   while using real insets in the app. Owner should fix the shared mock export
   so future components can call `useWindowClass()` directly.
3. **`useWindowClass()` depends on a provider supplied indirectly.** It calls
   `useSafeAreaInsets()`, which throws when no `SafeAreaProvider` is mounted.
   The app has no explicit provider in `App.tsx`/`RootNavigator.tsx`, but
   `BottomTabView` wraps its screens in React Navigation's
   `SafeAreaProviderCompat` (`@react-navigation/bottom-tabs` `BottomTabView.js:187`),
   so a provider does exist for screens in the tab tree. This component reuses
   the pure `windowClassFor` classifier and RN's `useWindowDimensions` rather
   than the hook, so it also renders safely outside that tree. No change to the
   hook was in scope.
4. **Pre-existing conflict markers in HEAD (out of scope).** Three committed
   Java files under `apps/api/.../exchange/hl7/` (`Hl7ParseException.java`,
   `Hl7Parser.java`, `Hl7Segment.java`) contain `<<<<<<<`/`>>>>>>>` markers. Not
   touched here. This is why the full `scripts/check.sh` was not run: it invokes
   `apps/api/check.sh` (full Maven verify, forbidden for workers) and
   `db/check.sh` (PostgreSQL integration tests). The module gate and the fast
   policy/frontend scripts above were run instead.
5. **RTL double-mirroring is a pre-existing defect (senior-mobile review, static).**
   `RootNavigator.tsx` applies `I18nManager.forceRTL` via `direction.ts`, so under
   native RTL Yoga already mirrors `flexDirection: 'row'`; the pre-existing
   `isRTL ? 'row-reverse' : 'row'` then reverses it back to LTR visual order, and
   the manual inset swap double-mirrors padding. Fixing this means dropping
   `row-reverse`, but the out-of-scope legacy `index.test.tsx:163` asserts it and
   the story forbids behaviour changes, so it was deliberately not changed here.
   Recorded for an owner-coordinated follow-up (pin `direction: 'ltr'` on this
   container with explicit physical padding, or update the legacy assertion and
   let the single native-direction boundary mirror). The new order test proves
   the rendered focus/reading sequence is unchanged, but cannot detect the
   painted double-mirror; that needs a device RTL pass.
6. **Review-driven test quality fixes applied.** The senior-mobile review found
   the font-scale assertions targeted style keys that can never carry Text props
   (tautologies) and that focus order was unasserted. Both were fixed in
   `AccessibilityEvidenceSummary.tablet.test.tsx` (assert props for clipping
   controls, and compare rendered `testID` order in LTR and RTL); re-ran the full
   gate green with 661 tests.

## Lessons for workers

- Inspect the export shape of the shared `jest.setup.ts` mock before adding
  `useWindowClass()`/`useSafeAreaInsets()` to a patient-mobile component; a
  legacy test without a local mock will fail on the partial default-only mock.
- `render` from `@testing-library/react-native` is async in this repo: always
  `await render(...)`, and type helper parameters as
  `Awaited<ReturnType<typeof render>>`.
- Workers should use `bash apps/patient-mobile/check.sh` plus the fast policy
  scripts; the root `scripts/check.sh` pulls in the >10-minute Maven verify and
  the database integration suite.
- Assert React Native clipping controls (`numberOfLines`, `adjustsFontSizeToFit`,
  `maxFontSizeMultiplier`, `allowFontScaling`) on element **props**, never on
  `props.style`; style-key assertions on those names are tautologies and let a
  200% text regression through.
