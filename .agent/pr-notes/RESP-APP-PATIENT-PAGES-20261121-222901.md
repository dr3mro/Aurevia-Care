## Summary

Adds a presentation-only responsive frame around the three data-backed patient
tabs (`AppointmentListPage`, `ReleasedReportsPage`, `ConversationInboxPage`).
The frame reads the current window through React Native's `useWindowDimensions`
and the shared `windowClassFor` classifier, applies physical safe-area insets as
logical padding (swapped under RTL), caps and centres content on `large`
windows, and renders a list/detail two-pane row on `large` windows. The pane
row uses an explicit `direction` (`ltr`/`rtl`) with `flexDirection: 'row'`, so
the platform's global native RTL does not double-flip it. No loader, command,
screen text, request, exported name or dependency changed; no clinical data is
stored between renders (PAT-02).

Files changed (only these two, both in task scope):
- `apps/patient-mobile/src/app/patientPages.tsx` (modified)
- `apps/patient-mobile/src/app/patientPages.tablet.test.tsx` (new)

## Affected requirements

PAT-01, PAT-02, UX-01. Requirement IDs recorded on task
`00308000-RESP-APP-PATIENT-PAGES.md`; decision record
`docs/decisions/0005-patient-app-tablets.md`.

## Risk class

Routine (contained presentation change in one module) with simulated
senior-mobile and accessibility review. No authorization, data or workflow
boundary was changed. Real patient and clinical acceptance remains a G5 gate.

## Review owner

Senior React Native and Expo specialist (`orevia-senior-mobile`). The
integrator and Delivery own final acceptance.

## Data changes

None. No migration, backfill, lock/volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

Unchanged. Tenant, role and patient-link scope stay in the existing transport
and server. No audit event added or changed. No patient data or credential in
code, notes or logs; all payloads in the new test are synthetic
(`synthetic-patient`, `synthetic-facility`, `synthetic-lab`, `report-1`).

## Migration and rollout considerations

None. Presentation-only; no reserved migration version used.

## Test evidence

Test first: the new acceptance file was written before the production change
and failed with `Unable to find an element with testID: appointments-frame`.

Commands actually run (tool evidence, from the repository root unless noted):

- `cd apps/patient-mobile && npx jest src/app/patientPages.test.tsx --runInBand`
  (baseline) — `Test Suites: 1 passed, 1 total`, `Tests: 21 passed, 21 total`.
- `cd apps/patient-mobile && npx jest src/app/patientPages.tablet.test.tsx src/app/patientPages.test.tsx --runInBand` —
  `Test Suites: 2 passed, 2 total`, `Tests: 81 passed, 81 total`.
- `cd apps/patient-mobile && npm run typecheck` — final lines:
  `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit`, exit 0, no `error TS…`.
- `cd apps/patient-mobile && npm run lint` — 0 errors; 2 pre-existing warnings
  in `src/features/tracker/presentation.test.ts` (import/no-duplicates),
  unrelated to this change.
- `bash apps/patient-mobile/check.sh` — green. Final lines:
  `Test Suites: 63 passed, 63 total` /
  `Tests: 709 passed, 709 total` /
  `Snapshots: 0 total` /
  `Time: 16.311 s` /
  `Ran all test suites.` (includes pinned `npm ci`, typecheck and lint). After
  the review fix below it was re-run: `Test Suites: 63 passed, 63 total` /
  `Tests: 709 passed, 709 total` / `Time: 6.685 s`.
  This is the mandatory frontend definition of done; Jest alone is not treated
  as done.
- `bash scripts/check.sh` (once at the end) — stopped at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`, exit 1.
  This is the known cloud-worker measurement artifact, not a regression:
  `git ls-files -v opencode.json` reports flag `S` (skip-worktree), the worktree
  file is the CI profile copied by `agent-run.sh`, and MEMORY.md records the
  dispatch-3 finding about `gen-opencode.py --check` judging that profile. The
  worktree has no change to `opencode.json` or any protected file; the committed
  local profile was not inspected or modified. All other repository checks were
  left to the integrator.

Entry point to test trace: exported `AppointmentListPage` /
`ReleasedReportsPage` / `ConversationInboxPage` → `PatientPageFrame` →
`PatientResource` → existing `AppointmentListScreen` / `ReleasedReportsScreen` /
`ConversationInbox`. The new tablet test renders each exported page with a
synthetic transport and asserts frame, column, pane, safe-area, label and RTL
structure; `patientPages.test.tsx` still proves the transport, state and
endpoint behaviour through the same entry points.

The 60 new acceptance cases cover: three pages × six window/orientation cases
(compact, regular, large × portrait, landscape) × font scales 1.0, 1.5 and 2.0,
plus a right-to-left large case per page and three screen-reader-label cases.

## Coverage

Production coverage command (scoped to the only changed production file):

`cd apps/patient-mobile && npx jest src/app/patientPages.test.tsx src/app/patientPages.tablet.test.tsx --coverage --collectCoverageFrom='src/app/patientPages.tsx' --coverageThreshold='{}' --runInBand`

Result: `patientPages.tsx | 100 | 100 | 100 | 100` for statements, branches,
functions and lines; `All files 100/100/100/100`. Full-module coverage from
`bash apps/patient-mobile/check.sh` also passed with the enforced global
100% thresholds.

New or changed branches and the test that takes each side:

| Branch | True side | False side |
|---|---|---|
| `SafeArea.useSafeAreaInsets ?? ((): Insets => NO_INSETS)` | new tablet test mocks the named hook (`mockInsets`) for every tablet case | existing `patientPages.test.tsx` runs against the repo's default-only safe-area mock and takes the zero-inset fallback |
| `large ? styles.columnLarge : styles.column` | `large portrait`/`large landscape` cases at all three font scales | `compact`/`regular` cases at all three font scales |
| `large ? (two-pane row) : (single list pane)` | `large` cases assert `*-panes` and `*-detail-pane` exist | `compact`/`regular` cases assert both are absent |
| `rtl ? styles.panesRtl : styles.panes` | "keeps the two panes in reading order on a right-to-left large window" (each page) asserts `direction: 'rtl'` | all English large cases in the layout matrix assert `direction: 'ltr'` |
| `rtl ? insets.right : insets.left` and `rtl ? insets.left : insets.right` | RTL test asserts swapped `paddingStart`/`paddingEnd` | English matrix cases assert `paddingStart === left`, `paddingEnd === right` |
| `locale === 'ar'` | all Arabic-rendered cases | all English-rendered cases |

No threshold was lowered, no exclusion or ignore comment added and no test
skipped or disabled.

## Clinical and architecture review

Not required for signed data, cross-organization sharing, patient association,
prescribing or critical results; this is presentation only. Simulated UX and
senior-mobile review applies. Real VoiceOver/TalkBack and Arabic-clinician
acceptance stays a separate G5 launch gate and is not claimed here.

## Findings

1. The repository's `jest.setup.ts` maps `react-native-safe-area-context` to a
   default-only mock with no named `useSafeAreaInsets`. Calling the shared
   `useWindowClass()` from a component under a test file without its own
   safe-area mock throws `useSafeAreaInsets is not a function`; that is what
   broke the untouched `patientPages.test.tsx` when the frame was added. Because
   the task scope excludes the hook, the setup and the existing test, the frame
   reuses the pure `windowClassFor` classifier with `useWindowDimensions` and a
   one-time guarded read of the platform hook. Sibling responsive stories
   (`RESP-APP-APPOINTMENT-LIST-SCREEN`, `RESP-APP-RELEASED-REPORTS-SCREEN`,
   `RESP-APP-CONVERSATION-INBOX`, `RESP-APP-CONVERSATION-THREAD`) will meet the
   same issue; an owner-owned fix to `jest.setup.ts` is the durable remedy.
2. The three pages do not pass `locale` to the presentational screens; those
   screens resolve their display locale from native `I18nManager.isRTL`. That
   pre-existing behaviour was deliberately left unchanged ("do not change
   behaviour, text, API calls or exported names"). The frame mirrors from the
   page's `locale` prop; in headless tests the native flag is false, so inner
   screen labels stay English even when the page locale is `ar`. The tests
   assert labels are unchanged rather than claiming an Arabic label they cannot
   legitimately observe.
3. The two-pane row provides an empty detail slot (`*-detail-pane`) with no
   children and no accessibility props. No detail screen is in this story's
   scope and no command result is routed to the pages, so adding one would
   change behaviour. The slot is presentation scaffolding only; sibling screen
   stories own two-pane detail content. Touch-target sizing (48 pt
   `SCREEN_TOKENS.size.target`) and inner-screen text scaling likewise remain in
   the presentational screens and their sibling stories; the frame adds no
   fixed height, line clamp or clipping that could defeat 200% text.
4. `git grep -nE '^(<<<<<<<|>>>>>>>) '` after the `origin/main` merge reports
   conflict markers in committed Java files under
   `apps/api/src/main/java/com/aurevia/exchange/hl7/`. The merge
   itself was "Already up to date"; `git status` shows only the two scoped files
   changed. These markers are pre-existing on the base branch, outside this
   scope and untouched. They need an owner-owned defect task.
5. `bash scripts/check.sh` cannot complete in this cloud worker because of the
   skip-worktree `opencode.json` CI-profile artifact described above. No
   protected file was edited to work around it.
6. A simulated senior-mobile review (read-only; the reviewer had no shell and
   did not re-run checks) found one medium risk: `flexDirection: 'row-reverse'`
   would double-flip on a real Arabic device because `applyDirection` forces
   native RTL globally. Fixed by making `direction` authoritative on the pane
   row (`panes: 'ltr'` with `flexDirection: 'row'`, `panesRtl: 'rtl'` with
   `flexDirection: 'row'`), matching `ScreenFrame`'s established pattern; the
   RTL and LTR cases now assert `direction`. Re-ran targeted tests (81 pass),
   scoped coverage (100%), typecheck (clean), lint (0 errors) and the full
   `check.sh` (63/709 green). The reviewer's other notes were low, non-blocking:
   fontScale cases are structurally rather than behaviourally exercised; the
   `??` fallback branch is covered by the existing suite in the full run; and
   the empty detail pane is a deliberate scaffold.

## Lessons for workers

- Before calling a hook that reads safe-area insets from a patient-mobile
  component, check whether the test file mocks
  `react-native-safe-area-context` with a named `useSafeAreaInsets`. The global
  `jest.setup.ts` mock is default-only and throws on a named hook call.
- In a cloud worker, a `scripts/check.sh` / `gen-opencode.py --check` "opencode.json
  is stale" failure is usually the skip-worktree CI profile, not a real stale
  file. Confirm with `git ls-files -v opencode.json` before changing anything.
- Conflict markers found by `git grep` may already be committed on the base
  branch; check `git status` and the merge result before treating them as your
  own conflict or resolving them.
- When a presentational wrapper is added around existing screens, assert that
  screen-reader labels and focusable elements are unchanged instead of
  introducing new labelled elements just to prove RTL mirroring.
