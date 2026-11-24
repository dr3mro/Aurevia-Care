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

`aurevia-senior-mobile` (simulated reviewer, loaded skill and bounded review) and `aurevia-senior-typescript` (skill; strict TypeScript/typecheck gate). No human, patient or clinician review is claimed; real accessibility acceptance remains a G5 gate.

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

Simulated `aurevia-senior-mobile` review is recorded in Findings. `aurevia-senior-typescript` skill applied: `tsc --noEmit` green, no `any`, no `@ts-ignore`, no disabled/skipped test. No clinical-safety review is required: no clinical value, prescribing, patient association or critical result is rendered or changed by this presentation-only diff. Real VoiceOver/TalkBack and Arabic-clinician acceptance remain G5 gates and are not claimed here.

## Findings

- The two-pane scaffold is structural only. `RootNavigator` never rendered a detail screen: `AppointmentListScreen` uses `onOpenDetails={placeholderAction}`, `ReleasedReportsScreen` has no detail route, and the Messages stack does not mount `ConversationThread`. Within the two-file scope, and without changing behaviour, text, API calls, exported names or adding a dependency, the largest compliant change is a presentational list pane plus an empty detail slot on `large` windows. The detail content is owned by existing screen-level stories (for example `RESP-APP-CONVERSATION-THREAD`), not by this shell story. No task request is needed.
- RTL handling uses one mapping point: physical safe-area insets are swapped into logical `paddingStart`/`paddingEnd` from `locale === 'ar'`, and the `direction` style mirrors the row. Physical `left`/`right`, `marginLeft`/`marginRight` and `paddingLeft`/`paddingRight` are not used. `applyDirection` remains the only `I18nManager.forceRTL` caller.
- Touch targets: the tab bar reserves `minHeight: 48` from the shared patient token (≥44 pt); per-tab Pressables remain owned by `@react-navigation/bottom-tabs`, whose default item size already meets the floor. The header reserves a 48-point min height so 200% text cannot clip or overlap it.
- No defect was found in the executed gates. No out-of-scope file was changed; `git status` shows only the two scoped paths (plus this notes file).

### Simulated senior-mobile review (reviewer, not tool/human evidence)

A bounded `aurevia-senior-mobile` review found no blocking issue and confirmed the physical→logical inset swap, the empty detail slot's neutral accessibility impact, the `borderStartWidth` divider behaviour and full branch coverage. It could not run commands in its environment and relied on the tool evidence above. Three of its observations were addressed in this same pass:

1. **Bottom inset applied twice (medium, now fixed)** — the tab bar previously added `paddingBottom: max(insets.bottom, 8)` while the containing region already applied `insets.bottom`, which could double the inset on a notched device. `tabBarStyle` is now only `{ minHeight: TOUCH_TARGET }`; the bottom inset lives once on `responsive-content`. The RTL/LTR inset assertions in the test still prove the region applies it.
2. **Double horizontal mirroring (medium, device check deferred)** — `applyDirection()` and the explicit `direction` style both follow the same active locale, so one logical flip is intended, not two. The redundant `direction: 'ltr'` in `styles.twoPane` (always overridden by the inline locale value) was removed to avoid confusion. A cold-start VoiceOver/TalkBack pass on a real Arabic device remains a G5/launch-gate item and is not claimed as done here.
3. **Test name and edge assertions (low)** — the responsive `it.each` tuple was reordered so the printed name reads “…window as single-pane/two-pane” instead of showing the width. The `Math.max` floor assertion became moot once that call was removed. A painted-height assertion for a 200% title is not observable in the headless React Native test renderer; the structural guarantees (no `numberOfLines`, no `adjustsFontSizeToFit`, `allowFontScaling`, `flexShrink`, no fixed height) are asserted instead, with the real-device text pass deferred to G5.

## Lessons for workers

- In this repo the Jest config enforces 100% global coverage, so a shell-level responsive change must render every window class and both locales in the new test; a single default-size render silently leaves RTL and large-window branches uncovered.
- `useWindowDimensions()` is driven by `Dimensions.get`/`addEventListener`; spying on those (as `useWindowClass.test.ts` does) is enough to drive real hook integration without mocking `useWindowClass` itself.
- React Native style props read from Testing Library are nested arrays (`[rootStyle, [contentStyle, …]]`); use `StyleSheet.flatten` before asserting on them. `allowFontScaling` is a Text prop, not a TextStyle key.
- Safe-area insets are physical: map left/right into `paddingStart`/`paddingEnd` with an explicit RTL swap at the shell boundary, and set `direction` on the same node, or RTL landscape insets land on the wrong edge.
- Apply a safe-area inset in exactly one place in the tree. A bottom inset on a padded shell plus a second `paddingBottom` on a nested tab bar is a double inset on notched devices; reviewers will (correctly) flag it even when unit tests pass.

## Segment 2 — post-merge verification and completion

This segment resumed after the branch merged the latest `origin/main` (top commit `9afeebae`, "Merge remote-tracking branch 'origin/main'"). The scoped work from segment 1 was intact and no source change was needed. Gates re-run on the merged tree:

- `git merge --no-edit origin/main` — "Already up to date." No conflicts in either scoped file.
- `cd apps/patient-mobile && npm run typecheck` — final lines `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit`, exit 0, no `error TS…`.
- `bash apps/patient-mobile/check.sh` — exit 0 (explicit `EXIT=0`); `Test Suites: 63 passed, 63 total`; `Tests: 665 passed, 665 total`; all files 100%.
- `cd apps/patient-mobile && npx jest src/app/RootNavigator.tablet.test.tsx src/app/RootNavigator.test.tsx --coverage --collectCoverageFrom='src/app/RootNavigator.tsx' --runInBand --silent` — `RootNavigator.tsx | 100 | 100 | 100 | 100`; `All files | 100 | 100 | 100 | 100`; 2 suites / 34 tests passed.
- `git status --short` — clean; only the two scoped source paths and this notes file differ from `origin/main` (2 commits ahead).

Evidence source for segment 2: tool-executed commands in this worker only. No simulated user, reviewer or human participant was involved in this segment.

### Story complete

All acceptance criteria for PAT-01, PAT-02 and UX-01 are met within the two-file scope: responsive structure at every window class, orientation and font scale; logical RTL insets and row mirroring; 44 pt touch-target floor; unclipped text at 200%; unchanged screen-reader labels and focus order; and no persistent clinical cache. No behaviour, text, API call, exported name or dependency changed. Coverage is 100% on the changed file and all source files, and the canonical patient-app gate (`bash apps/patient-mobile/check.sh`) is green on the merged tree. **This story is complete; no further work remains in this segment.**

### Finding carried forward (out of scope, not modified)

`git grep -nE '^(<<<<<<<|>>>>>>>) '` reports conflict markers in three committed API files under `apps/api/src/main/java/com/aurevia/exchange/hl7/` (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). `git show HEAD:<path>` confirms they are already committed on `main`; they were not produced by this branch's merge (which was "Already up to date") and are outside this story's scope, so I did not modify them. Flagging for the integrator/Delivery: these markers would break the Java compile gate. No `task-requests` block is filed because this is a pre-existing `main` defect, not a dependency of this story.

## Coverage repair — attempt 1 (2026-11-21, orchestrator repair worker)

### Branch-to-test list for the changed scope (unchanged; all branches covered)

The only production file this story changed is `apps/patient-mobile/src/app/RootNavigator.tsx`. It is at **100% statements / branches / functions / lines**; every branch already has a deterministic test (no new test was needed, and no uncovered line or branch was found in the changed scope). Re-running `cd apps/patient-mobile && npx jest --coverage --runInBand --silent` this attempt: `All files | 100 | 100 | 100 | 100` and `RootNavigator.tsx | 100 | 100 | 100 | 100`, `Test Suites: 63 passed`, `Tests: 665 passed`, exit 0. The gate log confirms the patient-mobile and staff-web sections completed (coverage tables all 100%).

| Branch in `RootNavigator.tsx` | Test that takes it |
|---|---|
| `layout.windowClass === 'large' ? styles.contentLarge : null` (true / false) | `RootNavigator.tablet.test.tsx` “centres content … on a large window” / “uses the full width on a compact window” + structural `it.each` compact/regular rows |
| `isRTL ? 'rtl' : 'ltr'` (content `direction`) | RTL test / LTR inset-mapping test |
| `isRTL ? layout.insets.right : layout.insets.left` (`paddingStart`) | RTL test (`30`) / LTR test (`10`) |
| `isRTL ? layout.insets.left : layout.insets.right` (`paddingEnd`) | RTL test (`10`) / LTR test (`30`) |
| `TabViewport`: `if (layout.windowClass === 'large')` (true / false) | structural `it.each` large rows, RTL test, live-`Dimensions`-change test (after) / compact & regular rows, live-change test (before) |
| `isRTL ? 'rtl' : 'ltr'` (`two-pane` style) | RTL test / large `it.each` rows |
| `signedOut` early return (pre-existing) | existing session-expiry tests / all other tests |
| `TabContent` switch (pre-existing, 4 cases) | existing “renders each tab screen when that tab is pressed” |

No threshold, exclusion, skip, disable or `pragma` was added or changed.

### Why the coverage gate actually failed (NOT a coverage gap)

`scripts/coverage-check.sh` uses `base-sha = 36cf64ce…` (local `main` captured at worker start). Because this branch merged `origin/main`, `git diff 36cf64ce..HEAD` contains **64 `apps/api/`+`db/` files** from other workers' merged stories, so the gate ran `mvn -f apps/api/pom.xml -Pno-security verify`. Maven failed in **surefire, before JaCoCo ran**: 4 *contract-policy* test failures (`Tests run: 4095, Failures: 4`). All four are pre-existing defects already present on `origin/main`, byte-for-byte:

- `OpenApiRouteParityTest.everyControllerRouteIsDocumentedOrBaselined` — NEW undocumented controller routes (`/api/v1/pharmacy/substitutions/*`, `/api/v1/staff/mail/*`, `/health/metrics`; plus routes not in `contracts/openapi/missing-routes.txt`).
- `OpenApiPaginationRulesTest.committedContractPassesEveryPaginationRule` and `…theOnlyExemptionIsTheBareArrayCatalogFamilyList` — merged `listInstantMessages` / `listInstantUnreadCounts` / `listInstantConversations` operations lack `cursor`/`limit`/`nextCursor`.
- `OpenApiContractRulesTest.committedStaffApiContractSatisfiesEveryRule` — merged `/api/v1/staff/instant/*` operations lack `tags` and the `Idempotency-Key` header.

Tool evidence (blob IDs, HEAD == origin/main for every failing artifact): `OpenApiRouteParityTest.java 984ea88b`, `OpenApiPaginationRulesTest.java d9d028a6`, `OpenApiContractRulesTest.java f86e3404`, `contracts/openapi.yaml 188fe4c7`, `contracts/openapi/missing-routes.txt 058b181d`. Controllers `InstantMessagingController.java 3f98f721` and `MetricsController.java 3926eb42` also match origin/main. The full log is `.agent/coverage.log`; patient-mobile (line 11099) and staff-web (line 11737) sections both completed with 100% tables.

These are cross-boundary Java/OpenAPI-contract integration defects owned by the substitution, mail, metrics and instant-messaging stories and the integrator — not by this patient-mobile presentation story. Fixing them means editing the shared `contracts/openapi.yaml`, its baseline and Java contract tests across several other workers' domains, which would break swarm scope, risk parallel-PR conflicts and is outside a 2-file patient-mobile change. I did not make those edits.

### Why this is not finished

- **Failing command:** `bash scripts/coverage-check.sh 36cf64ce…` → `mvn … -Pno-security verify` exit 1 with 4 OpenAPI contract-policy test failures in surefire (no JaCoCo coverage violation; patient-mobile/staff-web both 100%).
- **Most likely cause:** a pre-existing `origin/main` defect — merged routes from other workers are missing from the shared OpenAPI contract/baseline and violate contract rules. Every worker that merges current `main` hits the same gate.
- **What I tried:** reproduced the gate, read `.agent/coverage.log`, proved the changed-file set is the main merge (64 api/db files, not my 2 patient-mobile files), and proved by blob identity that the failing tests, contract and controllers are identical on HEAD and `origin/main`. Re-ran patient-mobile Jest coverage: 100% on my scope. Considered patching the contract, but declined as out-of-scope cross-worker work.
- **Exact next step (for integrator/Delivery, not this worker):** on `origin/main`, reconcile `contracts/openapi.yaml` + `contracts/openapi/missing-routes.txt` with the merged substitution/mail/metrics/instant controllers (add missing route docs or baselines, `tags`, `Idempotency-Key` headers and pagination params), or fix the responsible workers' stories; then re-run `bash scripts/coverage-check.sh`. Once main is green, this story's branch needs no source change — its own scope is already at 100%.
- **Evidence source:** tool-executed commands in this worker only (git, grep, `npx jest --coverage`, reading `.agent/coverage.log`). No reviewer, simulated user or human participant was involved in this repair segment.

## Coverage repair — attempt 2 (final, 2 of 2; 2026-11-21)

### Answer to the repair request: there is no uncovered line or branch in this story's scope

The only production file this story changed is `apps/patient-mobile/src/app/RootNavigator.tsx`. It remains at **100% statements / branches / functions / lines**, re-proven fresh this attempt with a focused command:

- `cd apps/patient-mobile && npx jest src/app/RootNavigator.tablet.test.tsx src/app/RootNavigator.test.tsx --coverage --collectCoverageFrom='src/app/RootNavigator.tsx' --runInBand --silent` →
  ```
  All files          |     100 |      100 |     100 |     100 |
   RootNavigator.tsx |     100 |      100 |     100 |     100 |
  Test Suites: 2 passed, 2 total
  Tests:       34 passed, 34 total
  ```
- The full patient-mobile gate is also green: `Test Suites: 63 passed`, `Tests: 665 passed`, `All files | 100 | 100 | 100 | 100` (see `.agent/coverage.log`, "coverage check: patient-mobile" section).

No line or branch in the changed scope is uncovered, so there is no deterministic test to add and no unreachable code to delete. Fabricating a test that only touches a line would violate the coverage rules ("A test that only exists to touch a line" is not allowed). No threshold, exclusion, skip, disable, `@Generated`, ignore comment or `pragma` was added or changed.

### Branch-to-test list (re-affirmed; unchanged from attempt 1)

| Branch in `RootNavigator.tsx` | True side | False side / test |
|---|---|---|
| `layout.windowClass === 'large' ? styles.contentLarge : null` | `RootNavigator.tablet.test.tsx` "centres content … on a large window", "mirrors direction … on a large RTL window" | "uses the full width on a compact window" + structural `it.each` compact/regular rows |
| `isRTL ? 'rtl' : 'ltr'` (content `direction`) | RTL test | "maps physical insets to logical padding on a left-to-right window" |
| `isRTL ? layout.insets.right : layout.insets.left` (`paddingStart`) | RTL test (`30`) | LTR test (`10`) |
| `isRTL ? layout.insets.left : layout.insets.right` (`paddingEnd`) | RTL test (`10`) | LTR test (`30`) |
| `TabViewport`: `if (layout.windowClass === 'large')` | structural `it.each` large rows, RTL test, live-`Dimensions`-change test (after event) | compact/regular rows, live-change test (before event) |
| `isRTL ? 'rtl' : 'ltr'` (`two-pane` style) | RTL test | large `it.each` rows |
| `signedOut` early return (pre-existing) | existing session-expiry tests | all other tests |
| `TabContent` switch (pre-existing, 4 cases) | existing "renders each tab screen when that tab is pressed" | same |

`allowFontScaling` is a prop, not a branch. Every new or changed branch has a deterministic test.

### Root cause of the gate failure (external to this story, proven with fresh commands)

1. `git diff --name-only origin/main..HEAD` → exactly **3 files**: this notes file, `RootNavigator.tsx`, `RootNavigator.tablet.test.tsx`. The story's real diff is 2 source files.
2. `git diff --name-only 36cf64ce..HEAD` → **64 `apps/api`/`db` files** plus many others — all inherited from this branch's legitimate `origin/main` merge (`9afeebae`), not authored by this story.
3. The orchestrator's gate used the stale worker-start `base-sha` (`36cf64ce`), so the Java section ran (`coverage check: java (JaCoCo 100%)`) and died in **surefire, before JaCoCo**: the same 4 pre-existing `origin/main` OpenAPI contract-policy failures documented in attempt 1 (`OpenApiRouteParityTest`, `OpenApiPaginationRulesTest` ×2, `OpenApiContractRulesTest`; `Tests run: 4095, Failures: 4`). The patient-mobile and staff-web sections both completed with 100% tables.
4. **Proof the scope itself passes the gate:** `bash scripts/coverage-check.sh origin/main` → `coverage check: passed`, `GATE_EXIT=0` (patient-mobile 100%; Java/staff-web correctly skipped because only the 2 mobile files + notes differ from `origin/main`).

The failure is therefore a stale-base / pre-existing-main defect, not a coverage gap. The daily-note lesson (2026-11-20, line 99) — "After merging `origin/main`, re-run `git diff origin/main...HEAD`" — applies directly. Fixing the shared OpenAPI contract/baseline and Java contract tests is cross-boundary work owned by the substitution/mail/metrics/instant-messaging stories and the integrator, and is outside this 2-file patient-mobile scope; editing it here would break swarm scope and risk parallel-PR conflicts. I did not modify it.

### Why this is not finished

- **Failing command:** the orchestrator ran `bash scripts/coverage-check.sh` with stale `base-sha=36cf64ce`; `mvn -f apps/api/pom.xml -Pno-security verify` exited 1 in surefire on 4 pre-existing `origin/main` OpenAPI contract-policy tests. No JaCoCo coverage violation; patient-mobile and staff-web were 100%.
- **Most likely cause:** the branch merged `origin/main`, so the stale worker-start base pulls 64 api/db files into the diff and needlessly triggers the Java gate on a definition of "changed" that includes other workers' merged stories.
- **What I tried:** re-ran focused Jest coverage (`RootNavigator.tsx` 100%, 34/34); re-ran the gate against the correct base (`bash scripts/coverage-check.sh origin/main` → `coverage check: passed`, exit 0); proved by `git diff` that the real scope is only the 2 mobile files and that the failing Java artifacts are inherited from `origin/main`. No in-scope test can fix an out-of-scope Java contract failure, and no uncovered line exists to test.
- **Exact next step (for the integrator/Delivery, not this worker):** either (a) run the coverage gate with `origin/main` as the base for this merged branch (this story then passes as proven above), or (b) on `origin/main`, reconcile `contracts/openapi.yaml` + `contracts/openapi/missing-routes.txt` with the merged substitution/mail/metrics/instant controllers and fix the 4 contract-policy tests, then re-run the gate. This story needs no source change — its scope is already 100%.
- **Evidence source:** tool-executed commands in this worker only (git diff, `npx jest --coverage`, `bash scripts/coverage-check.sh origin/main`, reading `.agent/coverage.log`). No reviewer, simulated user or human participant was involved in this repair segment.
