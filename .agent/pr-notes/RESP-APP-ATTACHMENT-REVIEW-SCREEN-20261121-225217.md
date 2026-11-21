# RESP-APP-ATTACHMENT-REVIEW-SCREEN — tablet, landscape and large-text `AttachmentReviewScreen`

## Summary

Made the patient app's pre-send attachment review screen responsive for compact,
regular and large windows, in portrait and landscape, at font scales 1.0, 1.5
and 2.0 (decision `docs/decisions/0005-patient-app-tablets.md`). Presentation
only: on large windows the content column is capped at 640 dp and centred; the
existing safe-area library's insets become padding on every edge; every control
keeps a 44×44 pt floor; text keeps platform scaling with no `numberOfLines`,
`adjustsFontSizeToFit` or `maxFontSizeMultiplier` cap; Arabic mirrors the writing
direction; roles and labels are stable across all window classes. No API call,
validation rule, callback, exported name, text string, persistent clinical cache
(PAT-02) or dependency was added or changed.

Added `AttachmentReviewScreen.tablet.test.tsx` with an 18-case window class ×
font scale × orientation matrix plus focused assertions for the large-window cap,
safe-area padding, touch targets, unclipped text, stable accessibility labels,
RTL mirroring and unchanged callbacks.

Scope respected: only
`apps/patient-mobile/src/features/attachments/AttachmentReviewScreen.tsx` and
`apps/patient-mobile/src/features/attachments/AttachmentReviewScreen.tablet.test.tsx`
changed. The bulk of the component diff is the indentation required by the new
non-interactive content wrapper and Prettier import layout.

## Affected requirements

- PAT-01, PAT-02, UX-01 (per story); decision `docs/decisions/0005-patient-app-tablets.md`.
- Acceptance mapping:
  1. Compact/regular/large, portrait/landscape, font scale 1/1.5/2 → 18-case
     matrix `'keeps the full structure at $windowClass / $fontScale / landscape=$isLandscape'`
     drives real window widths through `Dimensions.get` and asserts the width
     reaches `windowClassFor`.
  2. Max readable width centred on large → `constrains and centres content on a
     large window` (maxWidth 640, alignSelf center) and two per-class
     `fills the available width without a cap on %s windows` tests.
  3. Touch targets ≥44×44 → `keeps every touch target at least 44 by 44 points`.
  4. Nothing clipped at 200% → `never clips or caps text scaling at 200 percent`.
  5. Safe-area insets → `respects safe-area insets on every edge`.
  6. Focus order and screen-reader labels unchanged → `preserves screen-reader
     labels and focus order in %s at every window class` (control role + label,
     send disabled state, key text labels at every class in en and ar).
  7. RTL mirrors → `mirrors writing direction and text alignment for Arabic`
     plus the pre-existing Arabic direction test in `attachments.test.tsx`.
  8. No persistent clinical cache / no behaviour change → `still fires every
     callback on a large window` and the unmodified `attachments.test.tsx` suite.
  9. Two-pane list+detail applies to list-and-detail screens (inbox/thread,
     appointments/detail, reports/detail); this screen is a single review form,
     not one of those, so it uses the max-width/centring treatment only.

## Risk class

Routine (contained presentational screen change). No API, database, contract,
authorization, migration or release boundary is crossed. The screen holds no
patient data or credentials and adds no clinical decision logic.

## Review owner

Senior React Native and Expo (`smartclinic-senior-mobile`), simulated review
with story ID, paths, acceptance criteria and evidence only. (Finding recorded
under `## Findings` once returned.)

## Data changes

None. No migration, backfill, table, lock or volume impact; no rollback or
forward-fix. No persistent cache was added (PAT-02).

## Authorization boundaries and audit events

None. All business rules (`isBoundToUnit`, `canDownload`, `canSend`,
`validateAttachment`) and their call order are byte-for-byte unchanged, and no
audit event was added.

## Migration and rollout considerations

None. Pure client presentation; no schema or configuration change.

## Test evidence

Commands run with final lines (all from the repository root unless noted):

- `cd apps/patient-mobile && npx jest src/features/attachments/AttachmentReviewScreen.tablet.test.tsx --runInBand`
  → RED before the component change: `Test Suites: 1 failed, 1 total`,
  `Tests: 26 failed, 2 passed, 28 total` (responsive assertions failed; the two
  passes were the pre-existing behaviour checks).
- `cd apps/patient-mobile && npx jest src/features/attachments/AttachmentReviewScreen.tablet.test.tsx src/features/attachments/attachments.test.tsx --runInBand`
  → GREEN: `Test Suites: 2 passed, 2 total`, `Tests: 50 passed, 50 total`.
- `cd apps/patient-mobile && npm run typecheck` → exit 0; final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  No `error TS…`. Two initial errors (`TS6133` unused import) were fixed.
- `cd apps/patient-mobile && npm run lint` → exit 0; only two pre-existing
  `import/no-duplicates` warnings in `src/features/tracker/presentation.test.ts`
  (outside scope, unchanged); 0 errors.
- `cd apps/patient-mobile && npx prettier --check ...` → initially flagged the
  component; `npx prettier --write` applied; re-check clean.
- `cd apps/patient-mobile && bash check.sh` → exit 0; `Test Suites: 63 passed,
  63 total`, `Tests: 677 passed, 677 total`, `All files | 100 | 100 | 100 | 100`.
- `cd apps/patient-mobile && npx jest --coverage --ci --runInBand` → exit 0;
  `All files | 100 | 100 | 100 | 100`, `AttachmentReviewScreen.tsx | 100 | 100 |
  100 | 100`, `Test Suites: 63 passed`, `Tests: 677 passed`.
- `bash scripts/check.sh` → exit 1 at the first policy gate:
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Confirmed
  environment artifact, not a change defect: `git ls-files -v opencode.json` →
  `S` (skip-worktree), and the worktree file differs from `git show
  HEAD:opencode.json` because `agent-run.sh` copied the CI profile into the
  worktree. Per AGENTS.md and the daily notes this is the documented OPS-CW-003
  false positive; the file was not regenerated or committed. Later gates
  (including the full Java/database suite) are owned by the integrator.

Entry-point-to-test trace: the screen is rendered by the patient app's
attachment flow and reads `useWindowDimensions()` +
`windowClassFor(width)` + `useSafeInsets()` → the new test renders
`<AttachmentReviewScreen … />` through `@testing-library/react-native`,
drives `Dimensions.get` with each class/scale/orientation, mocks
`windowClassFor` to record the observed width and mocks `useSafeAreaInsets`
with synthetic insets, then asserts `attachment-review`,
`attachment-review-content` elements, control roles
and labels, style floors, direction and the press callbacks. The unmodified
`attachments.test.tsx` continues to exercise the real business rules.

Evidence source: executed local commands (tool) plus one simulated senior-mobile
review. No human, clinical or patient-participant evidence; no patient data.

## Coverage

Command: `cd apps/patient-mobile && npx jest --coverage --ci --runInBand`
(also run inside `bash check.sh`). Result: global 100% statements/branches/
functions/lines; `AttachmentReviewScreen.tsx` 100/100/100/100; 63 suites /
677 tests. No threshold, exclusion, `@Generated`, ignore comment or skipped
test was added or changed.

New/changed branches and the test that takes each side:

1. `isLarge = windowClassFor(width) === 'large'` used in
   `isLarge ? styles.largeContent : null`.
   - true: `constrains and centres content on a large window` asserts maxWidth
     640 and alignSelf 'center'.
   - false: `fills the available width without a cap on compact windows` and
     `… on regular windows` assert both properties undefined; the 18-case
     matrix also renders all three classes.
2. `SafeArea.useSafeAreaInsets ?? (() => ZERO_INSETS)` (module-load hook
   resolution).
   - defined: the new tablet test mocks the named `useSafeAreaInsets`;
     `respects safe-area insets on every edge` asserts insets + 16 on all four
     paddings.
   - undefined: the unmodified `attachments.test.tsx` loads the component under
     the repository's shared headless mock (default export only) and takes the
     zero-inset fallback; all its tests pass, proving headless rendering works.
3. `direction = isRTL ? 'rtl' : 'ltr'` (reused by the container and content
   styles).
   - `'ar'`: `mirrors writing direction and text alignment for Arabic` and the
     `'ar'` case of `preserves screen-reader labels …` assert direction 'rtl'.
   - `'en'`: the same tests assert 'ltr'; the pre-existing
     `mirrors layout and text direction in Arabic` test still asserts the
     `attachment-review` style array contains `{ direction: 'rtl' }`.
4. `!sendAllowed ? styles.disabled : null` and all pre-existing conditional
   renders (`hiddenCount > 0`, `visible.length === 0`,
   `downloadAllowed.get(item.id) === true`, `item.scanState === 'failed'`) are
   unchanged and remain covered by the unmodified `attachments.test.tsx`.

The added `accessibilityRole` / `accessibilityLabel` props, the new wrapper
`View`, the local `Insets` interface, the constants and the style objects add no
branches.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical result is involved. Accessibility presentation is
reviewed by the simulated senior-mobile reviewer; a real VoiceOver/TalkBack
pass remains a separate G5 gate.
