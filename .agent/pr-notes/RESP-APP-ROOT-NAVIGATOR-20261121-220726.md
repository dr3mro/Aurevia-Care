# RESP-APP-ROOT-NAVIGATOR — responsive `RootNavigator`

## Summary

The patient root shell now derives its layout from the existing `useWindowClass()`
hook instead of ignoring window size. On a `large` window the content column is
capped at a readable maximum and centred, and each tab screen is mounted in a
two-pane scaffold (list pane + presentational detail slot). On `compact` and
`regular` windows the same screen fills a single pane. Physical safe-area insets
are mapped once, at the shell boundary, into logical `paddingStart`/`paddingEnd`
so the inline pair swaps under right-to-left; the two-pane row mirrors with the
active locale. The tab bar reserves a 48-point target floor and the title has no
fixed height, no line cap and font scaling enabled, so text can reach 200% without
clipping. No behaviour, text, API call, exported name or dependency changed, and
no clinical cache or storage was added.

Files changed (only the declared scope):
- `apps/patient-mobile/src/app/RootNavigator.tsx`
- `apps/patient-mobile/src/app/RootNavigator.tablet.test.tsx` (new)

## Affected requirements

- PAT-01 — appointment list remains the same data-backed tab; layout is presentation only.
- PAT-02 — no persistent clinical cache added; the detail pane is an empty presentational slot with no data, storage or fetch.
- UX-01 — 200% text scaling (no cap/truncation), 44 pt target floor, logical RTL properties, unchanged screen-reader labels and focus order, both locales.

Supporting evidence: `docs/decisions/0005-patient-app-tablets.md`, `docs/design/accessibility-patterns.md` §9, `docs/design/rtl-and-localization.md` §1.1.

## Risk class

Routine — contained patient-app presentation change. Cross-boundary only in the sense that it consumes the shared responsive hook and token values; no clinical decision, authorization, API or persistence boundary is touched. UX-01 accessibility behaviour is covered by simulated review, not by human or clinical acceptance.

## Review owner

`smartclinic-senior-mobile` (simulated reviewer, loaded skill and bounded review) and `smartclinic-senior-typescript` (skill; strict TypeScript/typecheck gate). No human, patient or clinician review is claimed; real accessibility acceptance remains a G5 gate.

## Data changes

None. No migration, backfill, table, lock, volume or rollback impact.

## Authorization boundaries and audit events

Unchanged. No tenant, role or patient-link scope is read or written by this change. No audit event added or changed. The shell still passes the same synthetic organization/patient identifiers and the same injected transport to the existing pages.

## Migration and rollout considerations

None. Presentation-only client change; no server or database rollout step.

## Test evidence

Tool evidence (executed in this worker; no human or clinical participant):

1. `cd apps/patient-mobile && npx jest src/app/RootNavigator.tablet.test.tsx --runInBand --silent` — before implementation: **Test Suites: 1 failed, 1 total; Tests: 16 failed, 16 total** (red: `responsive-content`, `single-pane`/`two-pane` did not exist). This confirms the new tests fail for missing responsive behaviour, not for fixture errors.
2. `cd apps/patient-mobile && npm run typecheck` — final lines:
   ```
   > patient-mobile@0.1.0 typecheck
   > tsc --noEmit
   ```
   exit 0, no `error TS…`.
3. `cd apps/patient-mobile && npx jest src/app/RootNavigator.tablet.test.tsx src/app/RootNavigator.test.tsx --runInBand --silent` —
   `Test Suites: 2 passed, 2 total; Tests: 34 passed, 34 total`.
4. `bash apps/patient-mobile/check.sh` (canonical gate: pinned `npm ci`, typecheck, lint, Jest with enforced coverage) — exit 0;
   `Test Suites: 63 passed, 63 total; Tests: 665 passed, 665 total; All files 100%`.
5. `cd apps/patient-mobile && npx jest --coverage --runInBand --silent` (focused coverage read) —
   `RootNavigator.tsx | 100 | 100 | 100 | 100`, `All files | 100 | 100 | 100 | 100`, `Test Suites: 63 passed`, `Tests: 665 passed`.
6. `python3 -I scripts/check-docs.py` — exit 0.
7. `bash scripts/check.sh` — stopped at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented cloud-worker CI-profile artifact, not a change defect: `git ls-files -v opencode.json` reports `S` (skip-worktree), the worktree file is byte-identical to `.github/opencode.ci.json`, and with the committed HEAD bytes temporarily restored `python3 -I scripts/gen-opencode.py --check` exits 0. Nothing was regenerated or committed for that file; the CI profile was restored afterwards. A second full `scripts/check.sh` run exceeded the 5-minute worker command budget and was stopped; the canonical module gate (`bash apps/patient-mobile/check.sh`) already passed in step 4.

Entry-point-to-test trace:
`index.ts` / `App.tsx` → `RootNavigator` (`app-title`, `tab-button-*`) → `useWindowClass()` (`useWindowDimensions` + `useSafeAreaInsets`) → `Tab.Navigator`/`Tab.Screen` → `TabViewport` (`responsive-content`, `single-pane`/`two-pane`, `pane-list`, `pane-detail`) → `TabContent` → `AppointmentListPage` / `ReleasedReportsPage` / `MessagesTab` / `AccountTab`. The existing `RootNavigator.test.tsx` exercises the entry point and all four tabs; `RootNavigator.tablet.test.tsx` exercises the same component at every window class, orientation, font scale and locale, including a live `Dimensions` change event.

## Coverage

Command: `cd apps/patient-mobile && npx jest --coverage --runInBand --silent` (and the enforced run in `bash apps/patient-mobile/check.sh`).
Result: `RootNavigator.tsx` 100% statements / branches / functions / lines; all source files 100%; no threshold, exclusion or skip changed.

Every new or changed branch in `RootNavigator.tsx` and the test that takes it:

| Branch | True side | False side |
|---|---|---|
| `layout.windowClass === 'large' ? styles.contentLarge : null` | “centres content to a maximum readable width only on a large window”; “mirrors direction … on a large RTL window” | “uses the full width on a compact window”; structural `it.each` rows for compact/regular |
| `isRTL ? 'rtl' : 'ltr'` (content `direction`) | “mirrors direction and insets … RTL” | “maps physical insets to logical padding on a left-to-right window” |
| `isRTL ? layout.insets.right : layout.insets.left` (`paddingStart`) | RTL test (`30`) | LTR test (`10`) |
| `isRTL ? layout.insets.left : layout.insets.right` (`paddingEnd`) | RTL test (`10`) | LTR test (`30`) |
| `TabViewport`: `if (layout.windowClass === 'large')` | structural `it.each` large portrait/large landscape; RTL test; “recomputes the layout when the window dimensions change” (after event) | structural `it.each` compact/regular rows; “recomputes…” (before event) |
| `isRTL ? 'rtl' : 'ltr'` (`two-pane` style) | RTL test | large rows of the structural `it.each` |
| `signedOut` early return (pre-existing) | existing `session expiry` tests | all other tests |
| `TabContent` switch (pre-existing, 4 cases) | existing “renders each tab screen when that tab is pressed” | same |

`allowFontScaling` is a prop, not a branch. No production line or branch was left uncovered, and no unreachable guard was added. A simulated reviewer's early observation about a `Math.max` floor is no longer applicable: the tab bar now reserves only the 48-point target floor, and the bottom safe-area inset is applied once on the containing region.

## Clinical and architecture review

Simulated `smartclinic-senior-mobile` review is recorded in Findings. `smartclinic-senior-typescript` skill applied: `tsc --noEmit` green, no `any`, no `@ts-ignore`, no disabled/skipped test. No clinical-safety review is required: no clinical value, prescribing, patient association or critical result is rendered or changed by this presentation-only diff. Real VoiceOver/TalkBack and Arabic-clinician acceptance remain G5 gates and are not claimed here.

## Findings

- The two-pane scaffold is structural only. `RootNavigator` never rendered a detail screen: `AppointmentListScreen` uses `onOpenDetails={placeholderAction}`, `ReleasedReportsScreen` has no detail route, and the Messages stack does not mount `ConversationThread`. Within the two-file scope, and without changing behaviour, text, API calls, exported names or adding a dependency, the largest compliant change is a presentational list pane plus an empty detail slot on `large` windows. The detail content is owned by existing screen-level stories (for example `RESP-APP-CONVERSATION-THREAD`), not by this shell story. No task request is needed.
- RTL handling uses one mapping point: physical safe-area insets are swapped into logical `paddingStart`/`paddingEnd` from `locale === 'ar'`, and the `direction` style mirrors the row. Physical `left`/`right`, `marginLeft`/`marginRight` and `paddingLeft`/`paddingRight` are not used. `applyDirection` remains the only `I18nManager.forceRTL` caller.
- Touch targets: the tab bar reserves `minHeight: 48` from the shared patient token (≥44 pt); per-tab Pressables remain owned by `@react-navigation/bottom-tabs`, whose default item size already meets the floor. The header reserves a 48-point min height so 200% text cannot clip or overlap it.
- No defect was found in the executed gates. No out-of-scope file was changed; `git status` shows only the two scoped paths (plus this notes file).

### Simulated senior-mobile review (reviewer, not tool/human evidence)

A bounded `smartclinic-senior-mobile` review found no blocking issue and confirmed the physical→logical inset swap, the empty detail slot's neutral accessibility impact, the `borderStartWidth` divider behaviour and full branch coverage. It could not run commands in its environment and relied on the tool evidence above. Three of its observations were addressed in this same pass:

1. **Bottom inset applied twice (medium, now fixed)** — the tab bar previously added `paddingBottom: max(insets.bottom, 8)` while the containing region already applied `insets.bottom`, which could double the inset on a notched device. `tabBarStyle` is now only `{ minHeight: TOUCH_TARGET }`; the bottom inset lives once on `responsive-content`. The RTL/LTR inset assertions in the test still prove the region applies it.
2. **Double horizontal mirroring (medium, device check deferred)** — `applyDirection()` and the explicit `direction` style both follow the same active locale, so one logical flip is intended, not two. The redundant `direction: 'ltr'` in `styles.twoPane` (always overridden by the inline locale value) was removed to avoid confusion. A cold-start VoiceOver/TalkBack pass on a real Arabic device remains a G5/launch-gate item and is not claimed as done here.
3. **Test name and edge assertions (low)** — the responsive `it.each` tuple was reordered so the printed name reads “…window as single-pane/two-pane” instead of showing the width. The `Math.max` floor assertion became moot once that call was removed. A painted-height assertion for a 200% title is not observable in the headless React Native test renderer; the structural guarantees (no `numberOfLines`, no `adjustsFontSizeToFit`, `allowFontScaling`, `flexShrink`, no fixed height) are asserted instead, with the real-device text pass deferred to G5.

## Lessons for workers

- In this repo the Jest config enforces 100% global coverage, so a shell-level responsive change must render every window class and both locales in the new test; a single default-size render silently leaves RTL and large-window branches uncovered.
- `useWindowDimensions()` is driven by `Dimensions.get`/`addEventListener`; spying on those (as `useWindowClass.test.ts` does) is enough to drive real hook integration without mocking `useWindowClass` itself.
- React Native style props read from Testing Library are nested arrays (`[rootStyle, [contentStyle, …]]`); use `StyleSheet.flatten` before asserting on them. `allowFontScaling` is a Text prop, not a TextStyle key.
- Safe-area insets are physical: map left/right into `paddingStart`/`paddingEnd` with an explicit RTL swap at the shell boundary, and set `direction` on the same node, or RTL landscape insets land on the wrong edge.
- Apply a safe-area inset in exactly one place in the tree. A bottom inset on a padded shell plus a second `paddingBottom` on a nested tab bar is a double inset on notched devices; reviewers will (correctly) flag it even when unit tests pass.
