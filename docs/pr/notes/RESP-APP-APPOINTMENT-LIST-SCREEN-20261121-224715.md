# RESP-APP-APPOINTMENT-LIST-SCREEN — patient app tablet/landscape/large-text layout

## Summary

`AppointmentListScreen` now derives its presentation from the current window using the shared
width classifier (`windowClassFor` from `src/app/layout/useWindowClass.ts`). A centred,
width-capped content column holds two panes: the appointment list and a detail pane carrying the
secondary actions (device revocation). Large windows arrange the panes side by side (`row`), with a
gutter that grows with text scale and wrapping so content reflows instead of clipping; compact and
regular windows stack them (`column`). Safe-area insets are physical edges, so the inline pair is
mapped once to logical `paddingStart`/`paddingEnd` for the active direction (swapped for Arabic),
keeping the approved-design source guard satisfied. Touch targets use the existing 48 pt token and
no text is shrunk or truncated. No behaviour, copy, exported name, callback, API call or dependency
changed; no storage or clinical cache was added (PAT-02).

## Affected requirements

- **PAT-01** — appointment list presentation; confirmation and detail callbacks unchanged.
- **PAT-02** — no persistent clinical cache introduced; data still arrives via props from the
  authenticated loader.
- **UX-01** — 48 pt touch targets (≥ 44 pt), safe-area insets, 200% text without clipping, RTL
  mirroring, unchanged screen-reader labels and focus order. Acceptance mapping: decision
  `docs/decisions/0005-patient-app-tablets.md` (width classes, two panes, 200% text, native
  screen-reader parity, unchanged boundaries).

## Risk class

Routine — contained, presentation-only patient-app change (docs/WORKFLOW.md). No data, contract,
authorization or migration boundary is crossed.

## Review owner

Senior React Native and Expo (`aurevia-senior-mobile`), plus synthetic UX review against
`docs/design/accessibility-patterns.md` section 9 and `docs/decisions/0005-patient-app-tablets.md`.
Real VoiceOver/TalkBack and Arabic-clinician acceptance remain a G5 gate.

## Data changes

None. No migration, backfill, lock/volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None changed. The screen remains a presentational component receiving server projections and typed
callbacks; no new audit event and no new data path.

## Migration and rollout considerations

None. Presentation only; rotation/resize recomputes the layout class from window width, as the
decision requires.

## Test evidence

New file `src/features/appointments/AppointmentListScreen.tablet.test.tsx` uses synthetic fixtures
(`patient-1`, `apt-1`, `apt-2`, `dev-1`, `org-a<NUL>apt-1`) only. Entry point to test trace:
`AppointmentListScreen` → `useWindowDimensions`/`windowClassFor` → `appointment-content` /
`appointment-list-pane` / `appointment-detail-pane` → assertions on style, focus order, labels,
touch targets, RTL inset mapping and safe-area padding.

Commands actually run (from `apps/patient-mobile` unless noted):

- `npm ci --no-audit --no-fund` — `added 1408 packages in 20s`.
- `npx jest src/features/appointments/AppointmentListScreen.tablet.test.tsx --runInBand --no-coverage`
  — **RED before implementation**: `Test Suites: 1 failed, 1 total; Tests: 21 failed, 21 total`
  (missing `appointment-content`, `appointment-list-pane`, `appointment-detail-pane`).
- `npm run typecheck` (`tsc --noEmit`) — no output, exit 0. Final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- `npm run lint` (`eslint .`) — exit 0; 0 errors, 2 pre-existing warnings in
  `src/features/tracker/presentation.test.ts` (`import/no-duplicates`, lines 1–2), unrelated and
  outside scope. No rule was disabled.
- `bash apps/patient-mobile/check.sh` (from repo root), run after every implementation stage —
  final run: `Test Suites: 63 passed, 63 total; Tests: 671 passed, 671 total; Snapshots: 0 total`,
  typecheck + lint + Jest 100% coverage all green.
- Targeted suites after the last change:
  `npx jest src/features/appointments/AppointmentListScreen.tablet.test.tsx src/features/appointments/AppointmentListScreen.test.tsx --runInBand --no-coverage`
  — `Test Suites: 2 passed, 2 total; Tests: 31 passed, 31 total`.
- `git merge --no-edit origin/main` — `Already up to date.` at start, after implementation and
  before finish; no conflict markers under `apps/patient-mobile`.

Evidence source: executed tool commands (install, targeted Jest, typecheck, lint, module gate,
git) plus a bounded static review by the senior-mobile specialist (subagent, no shell). Assertions
are simulated and structural — no human or clinical participant. Real device text-scaling and
screen-reader passes remain a G5 gate.

`scripts/check.sh` was **not** run: it executes `apps/api/check.sh` → `mvn -q -B verify` (full API
and integration suite), which the task prompt explicitly forbids for a worker. The scoped module
gate (`apps/patient-mobile/check.sh`) and its 100% coverage threshold are the applicable gate and
passed. Pre-existing committed conflict markers in unrelated
`apps/api/src/main/java/com/aurevia/exchange/hl7/*` were observed with `git grep` and
left untouched (outside scope).

## Coverage

`bash apps/patient-mobile/check.sh` enforces 100% lines/branches/functions/statements globally via
`apps/patient-mobile/jest.config.ts` (threshold unchanged). From `coverage/lcov.info` for the
touched production file after the final run:

```
SF:src/features/appointments/AppointmentListScreen.tsx
LF:33 LH:33        (lines 100%)
BRF:43 BRH:43      (branches 100%)
FNF:11 FNH:11      (functions 100%)
```

Changed/new production branches and the test that takes each side:

| Branch | True side | False side |
|---|---|---|
| `useSafeAreaInsets ?? fallback` (module scope) | tablet test supplies a working mock (insets 20/24/32/16) | existing `AppointmentListScreen.test.tsx` uses the repo mock where the named export is undefined → zero-inset fallback |
| `windowClass === 'large'` (`isLarge`) | matrix rows `large portrait`, `large landscape`, plus landscape test | matrix rows compact/regular, portrait and landscape |
| `isLarge ? 1 : undefined` (`paneFlex`) | `flex: 1` on both panes in large rows | `flex: undefined` in compact/regular rows |
| `isLarge ? 'row' : 'column'` | `flexDirection: 'row'` in large rows | `'column'` in compact/regular rows |
| `isLarge ? 'wrap' : 'nowrap'` | `flexWrap: 'wrap'` in large rows | `'nowrap'` in compact/regular rows |
| `isLarge ? paneGutter(fontScale) : 0` | assert `marginStart` 16/20/24 by font scale in large rows | assert `marginStart: 0` in compact/regular rows |
| `paneGutter`: `fontScale >= 2` | matrix `2` rows → 24 | matrix `1`/`1.5` rows continue |
| `paneGutter`: `fontScale >= 1.5` | matrix `1.5` rows → 20 | matrix `1` rows → 16 |
| `rtl ? insets.right : insets.left` (start) | RTL test → `paddingStart: 24` | matrix (en) → `paddingStart: 16` |
| `rtl ? insets.left : insets.right` (end) | RTL test → `paddingEnd: 16` | matrix (en) → `paddingEnd: 24` |
| `Math.max(inset, 16)` (four insets) | tablet matrix asserts 20/24/32/16 from the mock | existing tests use the zero fallback → base spacing 16 |

Non-branch changes: `minWidth: 48` on the details/revoke `Pressable` style is asserted in the
matrix; the empty-state row, the dedicated confirmed-value test and the `if (text === null) continue`
test guard are all exercised. No threshold, exclusion, skip or ignore was added; no existing test
was weakened or deleted. The `??` fallback branch is only fully covered when the tablet test and
the existing phone test run in the same Jest run (the module gate does).

## Clinical and architecture review

PAT-02: no `AsyncStorage`, `SecureStore`, cache or persistence was added; the component is still a
pure function of props and window values. Server authorization and release checks are untouched.
The two-pane treatment is presentation only; opening the full appointment detail remains the
unchanged `onOpenDetails` callback owned by the navigator.

## Findings

- **Senior-mobile review found an RTL safe-area defect in the first draft**: logical
  `paddingStart/End` was fed physical `insets.left/right`, applying the wrong edge under Arabic
  landscape with a notch. Fixed in scope by mapping the inset pair once for RTL
  (`rtl ? right : left` for start, `rtl ? left : right` for end) and keeping logical padding; the RTL
  test now asserts `paddingStart: 24, paddingEnd: 16` with asymmetric insets.
- `patientDesign.test.tsx` "approved-design source guard" fails any physical
  `paddingLeft|paddingRight|marginLeft|marginRight` in the approved screens, so the fix had to use
  the direction-aware logical mapping from `docs/design/responsive-layout.md` section 7, not
  physical padding. The full gate caught this; targeted Jest alone did not.
- The repo-wide `jest.setup.ts` mock returns the library's CommonJS safe-area mock under `default`,
  so a named `useSafeAreaInsets` import is `undefined` in the existing screen test. The component
  resolves the hook once at module scope with a zero-inset fallback; the new tablet test supplies
  its own working mock. In the mounted app React Navigation's `SafeAreaProviderCompat` supplies the
  provider, so real insets are used. Every existing test kept passing without editing the
  out-of-scope setup file.
- Font scale is now a real layout input (`paneGutter`) and is asserted at 1.0/1.5/2.0. The
  "no clipping" assertions are static proxies (no `numberOfLines`, `adjustsFontSizeToFit`,
  `maxFontSizeMultiplier` or disabled scaling); actual painted clipping still needs the G5 device
  pass, which is not claimed here.
- Focus order is asserted as DOM order (`confirm-apt-1`, `details-apt-1`, `revoke-dev-1`) and is
  identical to the phone presentation; the panes carry no accessibility label.
- The "detail" pane for this screen hosts the secondary actions (active-device revocation) beside
  the appointment list. The full appointment detail screen is a separate pushed screen reached by
  the unchanged `onOpenDetails` prop; embedding it here would change behaviour and require files
  outside scope. This is recorded as a deliberate in-scope interpretation of the decision's
  "list plus detail" rule.
- `npm run lint` reports two pre-existing `import/no-duplicates` warnings in
  `src/features/tracker/presentation.test.ts`; they are unrelated and outside this scope.
- Committed merge-conflict markers in three unrelated `exchange/hl7` Java files were observed on
  `origin/main` and left alone (out of scope). Integrator/owner should track separately.

## Lessons for workers

- Shared native-module mocks under `default` leave named imports undefined in existing tests; check
  `jest.setup.ts` before adding such a hook to a component, and resolve it once with a safe fallback
  if existing tests must stay green.
- `patientDesign.test.tsx` enforces logical properties in approved patient screens. Safe-area
  insets must be mapped once for direction (swap the inline pair in RTL), never written as
  `paddingLeft`/`paddingRight`.
- Run the module gate, not just the targeted Jest file: a source-guard assertion in another test
  file failed after a change that targeted Jest could not see.
- `render()` from `@testing-library/react-native` 14 is awaitable; type helpers with
  `Awaited<ReturnType<typeof render>>`, not `ReturnType<typeof render>`.
- `toHaveTextContent` is an exact match in this jest-native version; assert `props.children` with
  `toContain` when a string is composed from several values.
- Reuse the repo's width classifier (`windowClassFor`) instead of duplicating breakpoint numbers;
  only presentation styles belong in the screen.

## Why this is not finished

Not applicable — the scoped gate passed with 100% coverage on every touched file.
