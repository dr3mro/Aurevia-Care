## Summary

Made the patient app's MSG-06 `ServiceProfileNotice` responsive for tablets, landscape and large text (PAT-01, PAT-02, UX-01) without changing any data, text, API call or exported name and without adding a dependency. Small and regular windows use one reflowing column; large windows at up to 150% text use a centred overview/guidance two-pane layout; a large window at 200% text reflows to one column so nothing is clipped. Touch target, safe-area padding, RTL mirroring, focus order and accessibility labels were added/preserved. A new synthetic test matrix renders the real component at every window class, both orientations, font scales 1.0/1.5/2.0 and Arabic RTL. Presentation-only: no persistent clinical cache (PAT-02) and no new API call.

Files changed (only the two paths in the task's `scope`):
- `apps/patient-mobile/src/features/service-profiles/ServiceProfileNotice.tsx`
- `apps/patient-mobile/src/features/service-profiles/ServiceProfileNotice.tablet.test.tsx` (new)

## Affected requirements

| ID | Acceptance in this story | Where it is met |
|---|---|---|
| PAT-01 | Friendly presentation of authenticated patient information on phones/tablets, in both orientations; details still come from props (no device-side recomputation). | `windowClassFor(width)` from `src/app/layout/useWindowClass.ts` drives the layout; portrait and landscape cases in the new `.tablet.test.tsx`. |
| PAT-02 | No persistent clinical cache, no new API call, no new data. | Only window metrics, safe-area context and the existing props are read; no storage, effect, fetch or dependency added. |
| UX-01 | 200% text without clipping; 44x44 touch target; unchanged roles/labels/focus order; English and Arabic RTL mirroring; safe-area insets. | `twoPane` disabled at fontScale 2; `minHeight`/`minWidth` 44; logical `paddingStart`/`paddingEnd`; `row-reverse` for RTL; asserted by `assertContentAndLabels` and the RTL cases. |

Governing evidence: `docs/decisions/0005-patient-app-tablets.md` (600/840 dp classes, layout from window not device, 200% text, two panes on large, screen-reader parity, PAT-02 boundary); `docs/design/accessibility-patterns.md` §9; `docs/design/rtl-and-localization.md` §1.1 and §2; `PROJECT_V1_SPEC.md` lines 85–86, 90 and 110.

## Risk class

Routine. Contained, presentation-only change to one React Native component plus a new test file. No clinical data, authorization, persistence, tenant scope, API contract or deployment change.

## Review owner

Senior React Native and Expo (`aurevia-senior-mobile`), with `aurevia-senior-typescript` and `aurevia-ux-reviewer` contracts loaded. The specialist was a bounded simulated subagent review (findings reported below); no human, clinician, simulated patient or real-user evidence is claimed.

## Data changes

None. No migration, backfill, lock or volume impact, rollback or forward-fix. No dependency, configuration or generated-file change.

## Authorization boundaries and audit events

None. The component still renders only the effective profile and server-computed availability passed in as props. No tenant, role, patient-link or credential value is read, and no audit event is added or changed. The acknowledgment callback is still invoked only by an explicit press.

## Migration and rollout considerations

None; presentation-only. The component re-renders on a React Native window-dimension change, so rotation and iPad split-screen/Slide Over recompute the layout class from the new width, as required by decision 0005. The safe-area context is read directly with a zero fallback so the notice still renders where no provider is mounted; the real app's provider supplies the live insets.

## Test evidence

Evidence source: executed tool commands (Jest, tsc, ESLint, module gate, repository check) plus a bounded simulated senior-mobile review. No patient data, credentials or raw logs are recorded.

- Red first: `cd apps/patient-mobile && npx jest src/features/service-profiles/ServiceProfileNotice.tablet.test.tsx --runInBand --coverage=false` → `Test Suites: 1 failed, 1 total`; `Tests: 15 failed, 2 passed, 17 total` (component had no responsive structure). A first red attempt also exposed a test-only mistake (`element.fireEvent` does not exist); it was fixed to `fireEvent.press(...)` before implementation.
- Green: same command after the implementation → all cases pass. Combined with the untouched existing test: `npx jest src/features/service-profiles --runInBand --coverage=false` → `Test Suites: 2 passed, 2 total`; `Tests: 30 passed, 30 total`.
- Typecheck: `cd apps/patient-mobile && npm run typecheck` → exit 0; final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  No `error TS…` was observed. The same typecheck ran inside the module gate.
- Lint: `cd apps/patient-mobile && npm run lint` → 0 errors; 2 pre-existing `import/no-duplicates` warnings in `src/features/tracker/presentation.test.ts` (out of scope, unchanged by this story).
- Module gate: `bash apps/patient-mobile/check.sh` → `EXIT=0`; final lines:
  ```
  All files          |     100 |      100 |     100 |     100 |
  Test Suites: 63 passed, 63 total
  Tests:       667 passed, 667 total
  Snapshots:   0 total
  ```
- Repository check: `bash scripts/check.sh` → `EXIT=1`, first line `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Verified as the documented cloud-worker artifact, not a change from this story: `git ls-files -v opencode.json` → `S` (skip-worktree), `cmp` shows the worktree file is byte-identical to `.github/opencode.ci.json` and differs from `git show HEAD:opencode.json`. The protected generated file was not edited.
- Entry-point-to-test trace: `ServiceProfileNotice.tsx` (exported component) → `useWindowDimensions()` (spied `Dimensions.get` in the new test) and `windowClassFor(width)` (`src/app/layout/useWindowClass.ts`) → `SafeAreaInsetsContext` (local real context provider in the test) → tests `uses a centred two-pane layout for …`, `uses a single reflowing column for …`, `reflows a large window to one column at 200% text …`, `mirrors the two panes … Arabic RTL`, `respects the safe-area insets in an LTR layout`, `falls back to zero safe-area insets when no provider is mounted`, and the role/label assertions in `assertContentAndLabels`. The acknowledgement entry point `onAcknowledge` is exercised by `keeps the acknowledgement action working in the mirrored two-pane layout`.

## Coverage

- Targeted command: `cd apps/patient-mobile && npx jest --runInBand --coverage --collectCoverageFrom='<rootDir>/src/features/service-profiles/ServiceProfileNotice.tsx'`
- Result: `All files | 100 | 100 | 100 | 100`; `ServiceProfileNotice.tsx | 100 | 100 | 100 | 100`; `Test Suites: 63 passed, 63 total`; `Tests: 667 passed, 667 total`. No missed line or branch in the changed production file.
- Full gate command/result: `bash apps/patient-mobile/check.sh` → `EXIT=0`, `All files | 100 | 100 | 100 | 100` (thresholds unchanged; no exclusion, skip, `@Generated`, istanbul/c8 ignore or lowered threshold was added). Type-only `types.ts` files show `0|0|0|0` as in the existing baseline; the build-failing global 100% threshold passes.

Branches added in `ServiceProfileNotice.tsx` and the test that takes each side:

| Branch | True / left side | False / right side |
|---|---|---|
| `SafeAreaInsetsContext ?? FALLBACK_INSETS_CONTEXT` | Every case in `ServiceProfileNotice.tablet.test.tsx` (local mock exports a real context). | Untouched `index.test.tsx` renders under the global `jest.setup.ts` mock, which exposes exports only under `.default` — fallback context path. |
| `useContext(INSETS_CONTEXT) ?? NO_INSETS` | Provider-wrapped `renderNotice()` cases (`paddingTop`/`paddingStart` = 12/24). | `falls back to zero safe-area insets when no provider is mounted` (all paddings = 0). |
| `isWide && fontScale < 2` (`twoPane`) | Large + 1.0/1.5: `uses a centred two-pane layout for large portrait/landscape at 100%/150% text`. | Large + 2.0: `reflows a large window to one column at 200% text …` (right side false); compact/regular: `uses a single reflowing column for …` (left side false, short-circuit). |
| root `flexDirection: isRTL ? 'row-reverse' : 'row'` | `mirrors the two panes … Arabic RTL`, `mirrors a compact column … Arabic RTL`, acknowledgement-in-RTL case. | All LTR cases; asserted `row` in the two-pane and single-column matrices. |
| `justifyContent: isWide ? 'center' : 'flex-start'` | Large matrix (`center`). | Compact/regular matrix (`flex-start`). |
| `alignSelf: isWide ? 'center' : undefined` | Large matrix (`center`). | Compact/regular matrix (`undefined`). |
| `maxWidth: isWide ? MAX_CONTENT_WIDTH : undefined` | Large matrix and the 200%-large case (`960`). | Compact/regular matrix (`undefined`). |
| `paddingStart: isRTL ? insets.right : insets.left` | RTL cases (`48`, asserted). | LTR safe-area case (`24`, asserted). |
| `paddingEnd: isRTL ? insets.left : insets.right` | RTL cases (`24`, asserted). | LTR safe-area case (`48`, asserted). |
| `notice-panes flexDirection: isRTL ? 'row-reverse' : 'row'` | RTL two-pane case (`row-reverse`). | LTR two-pane matrix (`row`). |
| `twoPane ? panes : stack` | Large at 1.0/1.5 (`notice-panes` present, `notice-stack` absent). | Compact/regular and large at 2.0 (`notice-stack` present, `notice-panes` absent). |
| `if (urgent !== undefined)` | All default-profile cases (`urgent-instructions` present). | `omits the urgent block when no urgent guidance is approved`. |

Retained pre-existing branches were re-asserted in the new file so the change does not rely on another suite: `channel.key === 'phone' || channel.key === 'link' ? 'link' : 'text'` (phone/link → `link`, email → `text` in `assertContentAndLabels`), `!availability.open && availability.nextHandlingAt !== undefined` (closed with an instant → `assertContentAndLabels`; open and closed-without-instant → `omits the after-hours banner …`), and `window.wraps ? ' (+1)' : ''` (the FRIDAY/SUNDAY service-hours text assertion).

## Clinical and architecture review

Not required by docs/WORKFLOW.md: presentation-only, no signed data, cross-organization sharing, patient linkage, prescribing, release or critical-result handling. Simulated senior-mobile review only; no clinical sign-off or real patient/unit acceptance is claimed (those remain G5 gates).

## Findings

- Out of scope, pre-existing: `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports conflict markers in three committed HL7 Java files (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). `git show HEAD:<file>` confirms they are already committed at HEAD and predate this story. Not touched — the scope is patient-mobile only. The integrator/owner should resolve them.
- Out of scope, pre-existing: two `import/no-duplicates` lint warnings in `src/features/tracker/presentation.test.ts` (0 errors).
- Tooling gap: the global `jest.setup.ts` mock of `react-native-safe-area-context` returns `jest.requireActual('react-native-safe-area-context/jest/mock')`, whose CommonJS export places `useSafeAreaInsets` under `.default`. Any component calling that hook directly in a suite without its own local mock therefore fails. This story keeps the notice working by reading `SafeAreaInsetsContext` with a zero fallback and asserting live insets through a provider in its own test. A separate tooling story should correct the global mock so other components can use `useSafeAreaInsets` safely; no file outside this story's scope was changed.
- `bash scripts/check.sh` fails on the documented `opencode.json` skip-worktree/CI-profile artifact (verified with `git ls-files -v`, `cmp` and `git show HEAD:opencode.json`), not on this change.
- Bounded simulated senior-mobile review (subagent, static reads only, no edits and no re-run of the gates) confirmed the safe-area context/fallback approach is sound: `SafeAreaInsetsContext` is a real library export in production under `NavigationContainer` → `SafeAreaProviderCompat` → `SafeAreaProvider`, and the zero fallback is what keeps the untouched `index.test.tsx` passing. It raised one in-scope doc fix (applied: the comment now states the real `fontScale < 2` rule rather than "up to 150%") and one optional hygiene suggestion (stable keys on the statically built `guidance` list) that was not taken because lint is clean, the list order is deterministic per render and adding keys would be unrelated churn.
- Latent RTL risk, out of scope (reported by the reviewer, not observable in jsdom): the app enables native RTL with `I18nManager.forceRTL(true)` for Arabic (`src/app/direction.ts`), and `docs/design/accessibility-patterns.md` §9 says `flexDirection: 'row'` already mirrors under native RTL. Manual `row-reverse` therefore double-reverses on a real Arabic-launch device, so the overview/guidance panes can appear left-to-right while focus order stays overview→guidance. Fixing it is a behavior change, forbidden by this story's "do not change behaviour" rule, so it is recorded for a follow-up below; the on-device VoiceOver/TalkBack RTL pass remains a G5 gate. The two panes are otherwise unchanged from the original forward/mirror ordering.

## Lessons for workers

- In this module the global Jest safe-area mock exposes library exports only under `.default`; a component that calls `useSafeAreaInsets` will break any existing suite that renders it without a local mock. Read `SafeAreaInsetsContext` (or mock it locally in a new test) and always keep a zero-inset fallback so untouched tests keep passing.
- To drive `useWindowDimensions` deterministically under jest-expo, spy on `Dimensions.get` (as in `src/app/layout/useWindowClass.test.ts`); do not spread `jest.requireActual('react-native')` in a `jest.mock` factory — it evaluates lazy native getters and throws a TurboModule invariant.
- `@testing-library/react-native` 14 `render` is async — `await` it. Use the imported `fireEvent.press(node)`; React Test Instance objects have no `.fireEvent` method.
- ESLint in this module flags elements in array literals with `react/jsx-key` even for a static presentation list; give each element a stable key (the `testID` is a good choice).
- Check pre-existing gate failures against `git show HEAD:<file>` before treating them as your defect; keep such findings in Notes and never edit files outside `scope`.
- `flexDirection: 'row'` already mirrors under native RTL when `I18nManager.forceRTL` is set; adding `row-reverse` on top reverses twice. A jsdom style assertion cannot detect this — it needs an on-device RTL pass.

## task-requests

```json
[
  {
    "title": "Patient app: use native RTL direction for ServiceProfileNotice two panes",
    "details": "Follow-up to RESP-APP-SERVICE-PROFILE-NOTICE. The app enables native RTL with I18nManager.forceRTL(true) for Arabic (src/app/direction.ts) and docs/design/accessibility-patterns.md section 9 states that flexDirection 'row' already mirrors. The notice's two panes and root currently also use flexDirection 'row-reverse' when isRTL, so they reverse twice on a real device: panes render left-to-right while focus order stays overview then guidance (UX-01, 2.4.3). Change the mirrored layouts to rely on native direction, keep logical paddingStart/paddingEnd and all accessibility labels, add an on-device or directory-aware RTL assertion where feasible, and keep safe-area insets. This is a presentation behavior change and was deliberately excluded from the no-behavior-change story.",
    "scope": ["apps/patient-mobile/src/features/service-profiles/ServiceProfileNotice.tsx", "apps/patient-mobile/src/features/service-profiles/ServiceProfileNotice.tablet.test.tsx"],
    "depends": ["RESP-APP-SERVICE-PROFILE-NOTICE"]
  }
]
```
