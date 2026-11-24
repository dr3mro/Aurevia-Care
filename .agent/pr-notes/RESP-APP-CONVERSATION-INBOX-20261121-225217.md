## Summary

Patient conversation inbox now has a tablet/landscape/large-text presentation inside the mandated two-file scope: a centred, width-capped content column and a capped list pane on `large` windows (840 dp and above), 48 dp minimum touch targets in both axes, no fixed height/truncation so text reflows at 200%, and unchanged labels, focus order, data access and `onOpen` behaviour. The shared pure classifier `windowClassFor` from `src/app/layout/useWindowClass.ts` is reused; no new dependency, exported name, text, API call or persistent cache (PAT-02). A new tablet test file renders every window class, both orientations and font scales 1.0/1.5/2.0, plus the Arabic RTL case and a live window resize.

## Affected requirements

- PAT-01, PAT-02, UX-01 (story RESP-APP-CONVERSATION-INBOX, TODO.md:3914).
- MSG-02 (inbox unread labels are unchanged), decision `docs/decisions/0005-patient-app-tablets.md` (window-width classes, 200% text, two-pane large layout, RTL parity), `docs/design/accessibility-patterns.md` §9 and `docs/design/rtl-and-localization.md` §1.1.

## Risk class

Routine. Presentation-only styles and one new container per pane; no clinical logic, persistence, authorization or contract change (docs/WORKFLOW.md).

## Review owner

Senior React Native and Expo (`aurevia-senior-mobile`) — bounded, read-only simulated subagent review of the two in-scope files; UX reviewer skill (`aurevia-ux-reviewer`) and Test Engineer skill (`aurevia-test-engineer`) loaded. Three low-severity findings were applied (minWidth, honest 200% evidence wording, removal of a brittle listener assertion). No human, clinician or patient-participant evidence is claimed.

## Data changes

None. No migration, backfill, lock/volume impact or rollback. No persistent clinical cache is introduced (PAT-02): the component still only renders the `conversations` prop and derives unread state through the existing `unreadCount` fallback.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit event is touched. All data remains caller-supplied after the host's authenticated load.

## Migration and rollout considerations

None. Presentation-only. `useWindowDimensions` re-renders the pane on rotation, split-screen and Slide Over, so layout follows the window rather than the device (decision 0005). Safe-area insets remain owned by the app shell; this component deliberately adds no second padding layer.

## Test evidence

Evidence source: executed tool commands (below); the specialist review was a simulated subagent that made no edits and ran no commands.

- Red first (before the production change): `cd apps/patient-mobile && npx jest src/features/conversations/ConversationInbox.tablet.test.tsx --runInBand --coverage=false` → 13 failed / 13 total; new `conversation-inbox-content` and `conversation-inbox-list-pane` nodes did not exist.
- Green targeted suites (after integration fix): `cd apps/patient-mobile && npx jest src/features/conversations/ConversationInbox.tablet.test.tsx src/features/conversations/ConversationInbox.test.tsx src/app/patientPages.test.tsx --runInBand --coverage=false` → `Test Suites: 3 passed, 3 total`; `Tests: 39 passed, 39 total`.
- Typecheck: `cd apps/patient-mobile && npm run typecheck` → exit 0. Final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  No `error TS…`.
- Lint: `cd apps/patient-mobile && npm run lint` → exit 0, 0 errors; two pre-existing `import/no-duplicates` warnings in `src/features/tracker/presentation.test.ts` (out of scope).
- Module gate: `bash apps/patient-mobile/check.sh` from repo root → exit 0; `Test Suites: 63 passed, 63 total`; `Tests: 662 passed, 662 total`; `...features/conversations  | 100 | 100 | 100 | 100` and `...ationInbox.tsx | 100 | 100 | 100 | 100`. Typecheck and lint inside the gate passed.
- Repository check: `bash scripts/check.sh` → exit 1 at its first step with `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented cloud-worker artifact, not this change: `git ls-files -v opencode.json` = `S` (skip-worktree); worktree hash `50005c9c…` equals `.github/opencode.ci.json`, while `git show HEAD:opencode.json` hashes to `0dc2ac8e…`. No protected generated file was edited.
- Entry point to test trace: `ConversationInbox.tsx:ConversationInbox` → `useWindowDimensions` + shared `windowClassFor` → `conversation-inbox-content` / `conversation-inbox-list-pane` styles → `ConversationInbox.tablet.test.tsx` cases `keeps the labelled, unclipped list at …` and `caps and centres the content only on large windows (…)`; the pre-existing `ConversationInbox.test.tsx` and `patientPages.test.tsx` still drive the same component through the inbox page and assert unchanged labels/actions.

## Coverage

- Command: `cd apps/patient-mobile && npx jest src/features/conversations/ConversationInbox.test.tsx src/features/conversations/ConversationInbox.tablet.test.tsx src/app/patientPages.test.tsx --runInBand --coverage --collectCoverageFrom='<rootDir>/src/features/conversations/ConversationInbox.tsx'`
- Result: `All files | 100 | 100 | 100 | 100`; `ConversationInbox.tsx | 100 | 100 | 100 | 100` (statements, branches, functions, lines). No threshold, exclusion or skip was changed.
- Branch-to-test list for `ConversationInbox.tsx`:
  - `isLarge ? styles.contentLarge : null` true → `caps and centres the content only on large windows (900 x 1200)` and `(1024 x 768)` (also the 200% landscape row); false → the `390 x 844` and `700 x 1000` cases.
  - `isLarge ? styles.listPaneLarge : null` true → the two large-window cases and `recomputes the pane layout when the window is resized from compact to large`; false → the compact/regular cases.
  - Pre-existing branches untouched and still covered by `ConversationInbox.test.tsx`: `unreadByConversation?.[id] ?? unreadCount(conversation)` both sides (`prefers the authoritative server unread count…`, `falls back to the local count…`) and `hasUnread ? … : null` both sides (`renders per-unit unread badges…`, `renders an empty labelled inbox`).
  - No `switch`, `catch`, `&&`, `||` or other conditional was added. `windowClassFor`'s own branches remain covered by `src/app/layout/useWindowClass.test.ts`; this file reuses that pure helper without duplicating it.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient linkage change, prescribing or critical-result handling. Simulated senior-mobile review only; real VoiceOver/TalkBack passes at 200% text remain a G5 gate.

## Findings

- **Two-pane composition boundary.** The inbox renders a capped list pane (560 dp) on `large` windows, leaving the remaining width free for the thread pane. Composing the two panes side by side lives in the host screen (`apps/patient-mobile/src/app/patientPages.tsx`), which is the separately dispatched `RESP-APP-PATIENT-PAGES` task; it cannot be done from this component's scope without changing exported props or behaviour. No task request is needed because that task already exists.
- **Shared headless safe-area mock is not a named-export mock.** `jest.setup.ts` mocks `react-native-safe-area-context` with `jest.requireActual('.../jest/mock')`, whose CommonJS module exposes the hooks only on `default`. Rendering a component that calls `useWindowClass()` from the pre-existing `ConversationInbox.test.tsx` / `patientPages.test.tsx` therefore threw `useSafeAreaInsets is not a function` (7 tests). A local `jest.mock` in the new test file cannot fix other suites. This component now calls RN's `useWindowDimensions()` and reuses the pure `windowClassFor` instead; safe-area insets stay with the app shell, which also avoids double padding.
- **200% evidence limit (reviewer finding, accepted).** `fontScale` is driven through the mocked `Dimensions.get('window')`, but no style is derived from it: the assertion is that the text style tree has no fixed height, line cap, fit-to-width or reduced font-scale multiplier. Painted clipping at 200% is a G5 device pass, not a claim of this headless test.
- **`prettier --check` is not a pinned module gate here.** `apps/patient-mobile/package.json` has no prettier dependency or config; `npx prettier` fetched 3.10.1 and flagged the surrounding hand-formatted sources too. Formatting follows the neighbouring files; the canonical gate is `bash apps/patient-mobile/check.sh` (green).
- `bash scripts/check.sh` stops on the pre-existing skip-worktree `opencode.json` CI-profile artifact described above; HEAD truth was used for policy evidence and no generated file was changed.
- Out-of-scope defect observed during the final `git merge --no-edit origin/main` (already up to date, no conflict stopped the merge): `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds committed conflict markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`. `git show HEAD:<path>` confirms the markers are already committed at `HEAD`, and `git diff -- apps/api/.../hl7/` is empty. Not touched here (outside this story's scope); owner: the integrator or the BUILD-37 worker.

## Lessons for workers

- Before adding `useWindowClass()` to an existing patient screen, run its pre-existing tests: under the shared Jest setup the safe-area hook is only on the module's default export. Reuse `windowClassFor` with `useWindowDimensions()` in leaf components that are rendered by older tests; a local `jest.mock` in a new test file does not repair other suites.
- `render`/`renderHook` in `@testing-library/react-native` 14 are async — `await render(...)`; `StyleSheet.flatten(undefined)` returns `undefined`, so use `?? {}` when asserting styles on nodes with no `style` prop.
- `within(unreadRow).getByText(...)` scopes repeated domain words; a bare `getByText('awaiting_staff')` fails when two synthetic conversations share a state, and `getAllByRole` on the whole view also includes tab-bar buttons in the RTL case.
- Drive `useWindowDimensions` deterministically by spying on `Dimensions.get`/`Dimensions.addEventListener`, but assert the observable style change after firing the listener rather than the listener's mere existence.
- Keep the safe-area padding at one boundary (the app shell); a leaf pane that adds its own insets on top of `ScreenFrame` padding double-pads and introduces a direction branch that must be covered twice.
