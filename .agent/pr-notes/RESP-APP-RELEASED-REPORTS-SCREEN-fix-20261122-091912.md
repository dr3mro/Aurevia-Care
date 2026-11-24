# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — 2026-11-22 09:19 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505`. I read the escalation findings first
and continued from the 09:14 pass (notes `...fix-20261122-091003.md`) rather than
starting over; I did not redo work those findings record as tried.

I rehydrated the governing context first: `.agent/context-files.txt`,
`AGENTS.md`, `CODING_STYLE.md`, `README-SETUP.md`, `docs/WORKFLOW.md`, `PROJECT.md`,
`WORKSPACE.md`, `MEMORY.md`, `TODO.md` (Instructions for every agent + this story's
entry), `docs/decisions/0005-patient-app-tablets.md`, the patient self-access product
clause, `.github/pull_request_template.md`, and the `orevia-delivery`,
`orevia-senior-mobile` skills. I surveyed `src/features/reports` and confirmed
reuse of the existing `ReleasedReport` type, `windowClassFor` classifier and
`patientDesign` tokens.

The scoped repair is already committed at HEAD (introduced in `5eccf8dc`, with
verification/doc corrections in `70d4be33` and `c59551bc` and later notes-only
commits; HEAD is `942105e2`):

- `ReleasedReportsScreen.tsx` — capped/centred content column plus two
  summary+result panes on `large`, safe-area insets, RTL mirroring, ≥48 pt panes,
  no new state/cache, unchanged labels/API/exported names.
- `ReleasedReportsScreen.tablet.test.tsx` — the TS2375 repair: the no-result fixture
  **omits** the optional `result` key via rest destructuring
  (`const { result: _discardedResult, ...withoutResult } = report;`) instead of
  assigning `result: undefined`, which the repo-root `tsconfig.json`
  (`exactOptionalPropertyTypes: true`) rejects.

**No production or test code was changed this pass.** I re-verified the committed
fix with fresh commands (below). Every command in this note was executed by the
primary agent — **evidence source: tool**. The bounded senior-mobile advisory
review from an earlier pass is **reviewer** evidence recorded in that pass's note;
no new review was required because no code changed. **No simulated-user or
human-participant evidence is claimed**; no named clinician or patient review was
available.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`
and decision `0005-patient-app-tablets.md`). Acceptance mapping (entry point → test):

| ID | Acceptance | Evidence (this run) |
|---|---|---|
| PAT-01 | Window-width rule drives reflow on rotation/split-screen; device type never decides layout. | Tablet test 9-case matrix (compact/regular/large × 1.0/1.5/2.0) drives `Dimensions.get` through `useWindowDimensions`; shared `windowClassFor` untouched. |
| PAT-02 | No persistent clinical cache/state/storage or changed API/download flow; revision, issuer, date, correction and download labels unchanged. | Component holds no storage client/state; `reports-screen.test.tsx` integration test stays green; 1127 tests green. |
| UX-01 | Capped/centred content + two panes on large; ≥44 pt targets (`MIN_PANE_WIDTH = 48`); safe-area insets; no truncation/font cap below 200%; unchanged label order; RTL mirrors insets and row direction. | Tablet matrix, Arabic RTL case, no-result/no-provider case, `expectReflowAtTwoHundredPercent`. |

Entry-point-to-test trace: exported `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The tablet test renders the exported screen directly, injects
`SafeAreaInsetsContext`, drives `Dimensions.get`, and asserts tree, labels, insets and
direction. The TS2375 fix is test-only; runtime behavior is unaffected.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus a test-only
type repair — no clinical decision, authorization, patient association, data,
contract, migration or release change.

## Review owner

Senior React Native and Expo (`orevia-senior-mobile`) owns the mobile definition
of done, executed and met this pass (`apps/patient-mobile/check.sh` green, typecheck
final lines and coverage below, zero `error TS…`). Senior TypeScript
(`orevia-senior-typescript`) owns the strict `exactOptionalPropertyTypes` rule
that produced TS2375; the root strict typecheck, the exact failing gate
`scripts/frontend-check.sh` and `scripts/frontend-typecheck-check.sh` are green.
Independent QA and the full integration gate belong to the integrator/Delivery.
No named clinical or patient reviewer was available and none is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix;
no reserved migration version was used. Synthetic fixture only — no patient data,
identifier, credential or real domain name appears in the change or note.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event touched. The component remains
presentational: report data and download authorization still arrive from the
authenticated client; the withdrawn-release guard and server authorization are unchanged.

## Migration and rollout considerations

None. Existing app users keep the same data flow; the responsive structure is a pure
render change.

## Test evidence

All commands executed by the primary agent (tool evidence), run **strictly
sequentially** where a `check.sh` performs its own `npm ci`.

Sync at the start: `git merge --no-edit origin/main` → `Already up to date.`
Working tree clean. `git grep -nE '^(<<<<<<<|>>>>>>>) '` found conflict markers in only
3 pre-existing `apps/api/.../exchange/hl7/*.java` files that also exist on
`origin/main` (flagged separately by the debugger, inert here, out of scope);
none in `apps/patient-mobile/src/features/reports/`.

- `bash apps/patient-mobile/check.sh` → **exit 0**. Mandatory typecheck final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  (no output after the command — zero `error TS…`). Lint final line:
  `✖ 4 problems (0 errors, 4 warnings)`; the 4 warnings are pre-existing
  `array-type`/`import/no-duplicates` warnings in `enrollment/screens.tablet.test.tsx`
  and `tracker/presentation.test.ts` (other workers' files, not in this scope; lint
  exits 0). Jest final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Ran all test suites.
  ```
- Root strict typecheck (the exact place TS2375 surfaced):
  `npm ci --no-audit --no-fund` at repo root and in `apps/staff-web` (module deps are
  required because the root `tsconfig.json` includes staff-web sources), then
  `npm run typecheck` → **exit 0**, final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  No output — zero `error TS…`, including no TS2375 in
  `ReleasedReportsScreen.tablet.test.tsx`. (A first attempt before installing
  staff-web deps produced only missing-module errors for `react` and
  `@testing-library/react`, an environment artifact of the partial run, not a
  code failure; all module deps were then installed and the gate passed.)
- `FRONTEND_AUDIT=skip bash scripts/frontend-check.sh` — the *exact failing gate*
  named in the escalation (both modules' typecheck/lint/test, then repo-root
  `npm ci`, `tsc --noEmit -p tsconfig.json` with `exactOptionalPropertyTypes: true`,
  lint, format:check and root Vitest). `FRONTEND_AUDIT=skip` only disables the
  network-dependent npm audit for this local run; the script supports it and the
  prior pass recorded that audit green. → **exit 0**, final lines:
  ```
  All matched files use Prettier code style!
  Test Files  1 passed (1)
  Tests       1 passed (1)
  frontend standards: policy and tool commands passed
  ```
- `bash scripts/frontend-typecheck-check.sh` (worker gate re-run by the delivery
  loop) → **exit 0**, final line:
  `frontend typecheck: passed (or no frontend files changed)`.
- `bash scripts/coverage-check.sh` (worker coverage gate) → **exit 0**, final lines:
  `Test Suites: 79 passed`, `Tests: 1127 passed`, `coverage check: passed`.

Full `scripts/check.sh` / `scripts/worker-check.sh` were deliberately **not** run:
they invoke `apps/api/check.sh` (full `mvn verify`) and the PostgreSQL integration
suites, which prior debugger rounds timed out (>20 min) and which the task instruction
forbids this worker from running; the integrator and Delivery own that gate. All
scoped frontend gates were run instead. No simulated user or human participant was
involved.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (pinned `npm ci` → typecheck → lint →
`npm run test:coverage -- --ci` with the module's enforced 100% global threshold).
Result: `All files` **100 | 100 | 100 | 100**, `features/reports` directory
**100 | 100 | 100 | 100**, 1127 tests green. `bash scripts/coverage-check.sh`
independently re-ran the same gate → `coverage check: passed`. `lcov.info` for the
touched file confirms the exact figures:
`SF:src/features/reports/ReleasedReportsScreen.tsx` — `FNF:3 FNH:3`, `LF:20 LH:20`,
`BRF:37 BRH:37` (100% functions/lines/branches). Test files are excluded from the
source scope by `jest.config.ts`; no threshold, exclusion, `@Generated`/ignore
comment, skip or `@Disabled` was added. Type-only `types.ts` files show `0 | 0 | 0 | 0`
because they contain no executable statements. **This fix pass added no production
code, so there is no new production branch.** The story's production branches
(committed implementation, confirmed 100% above) map to their tests:

| Branch in `ReleasedReportsScreen.tsx` | Test that takes it |
|---|---|
| `windowClass === 'large'` (`maxWidth: 960`, row panes, `flex: 1`) | tablet matrix `large / 1`, `large / 1.5`, `large / 2`; Arabic RTL case; no-result case |
| `windowClass !== 'large'` (no `maxWidth`, column panes, natural height) | tablet matrix `compact` and `regular` cases |
| `locale === 'ar'` (insets mirrored) | `mirrors the panes, safe-area insets and row direction under Arabic RTL` |
| `locale !== 'ar'` (LTR insets) | all 9 matrix cases |
| `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context defined | tablet test provider + module mock |
| … context undefined | `reports-screen.test.tsx` (global Jest setup mock) |
| `useContext(...) ?? NO_INSETS` — non-null | matrix + RTL cases |
| … null insets | `keeps a single summary pane … and tolerates no provider` |
| `withdrawn = releaseState === 'withdrawn'` true / false | `reports-screen.test.tsx` |
| `report.title === undefined` true / false | `reports-screen.test.tsx` |
| `correction === null` true / false (pills + correction view) | `reports-screen.test.tsx` |
| `report.result === undefined` true / false (detail pane) | no-result case / matrix cases |
| `error !== null`, `reports.length === 0 && error === null` true / false | `reports-screen.test.tsx` |
| `isRtl(report)` true / false (row style) | `reports-screen.test.tsx` + RTL case |
| `locale` / `showTabBar` / `activeTab` / `onTab` defaults | existing `reports-screen.test.tsx` |

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient-association change,
prescribing or critical result. Presentation-only; no domain contract or state machine
changed. A named VoiceOver/TalkBack pass on device remains a separate launch gate and
is not claimed here.

## Findings

1. **Scoped work is complete and green (not a defect).** The TS2375 test fix and
   responsive component are present at HEAD. The root strict typecheck that produced
   TS2375, the module typecheck, the module check gate, the exact failing gate
   `scripts/frontend-check.sh`, `scripts/frontend-typecheck-check.sh` and
   `scripts/coverage-check.sh` all pass on fresh tool runs this pass. There is no
   in-scope code change left that can make the full gate pass.
2. **No source change since `c59551bc`.** `git log` shows every later commit is
   notes-only or a merge with `origin/main`. This pass added no source change.
3. **Full `scripts/check.sh` / `scripts/worker-check.sh` were deliberately not run.**
   They invoke the full `mvn verify` and PostgreSQL integration suites, which exceed
   the worker cap and are the integrator's responsibility; the task instruction also
   forbids running them here.
4. **"integration run failed without fresh, usable test results (compile or setup
   error)" is external infrastructure (out of scope, not fixed).** That literal
   string does not exist in this repository; earlier debugger findings recorded full
   `mvn verify` exceeding the runner cap while applying migrations (exit 124), leaving
   no fresh Surefire report — a time limit, not a failing assertion. Judge it from the
   exact SHA's CI job logs, not a worker re-run.
5. **`check-pack.py` "opencode.json is stale" is a CI-profile artifact (out of scope).**
   The worktree `opencode.json` is the CI profile copied under `skip-worktree`; HEAD's
   committed blob equals `origin/main`'s (MEMORY OPS-CW-003-01). `opencode.json` and
   `.opencode/` are protected files a worker must not edit.
6. **Intermittent `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` is a shared
   tracker condition (out of scope).** TODO.md is owned by the orchestrator/integrator;
   this branch's scoped diff contains no tracker file.
7. **PR file count is an integrator policy matter (out of scope).** The branch has
   accumulated one notes file per prior fix pass (38 before this one); per MEMORY.md a
   PR over 15 files is treated as `needs-human`. Deleting earlier workers' committed
   notes is destructive and explicitly outside a worker's scope ("notes go only to the
   file your prompt names"). This needs an owner/integrator decision, not another
   code pass.
8. **No valid `task-requests` block is added.** Every remaining blocker is in
   protected `scripts/`, `.github/workflows/` or tracker paths, and the task-request
   rule forbids those as scope paths; a request for them would be rejected. The
   blockers need an owner/integrator action, not a new scoped story.
9. **Pre-existing conflict markers (out of scope, not fixed).** Three
   `apps/api/.../exchange/hl7/*.java` files carry conflict markers on `origin/main`
   itself (noted by the debugger as inert because Java compile/unit tests pass).
   They are outside this story's scope and were left untouched.

## Lessons for workers

- Never run two frontend gates in parallel: `apps/patient-mobile/check.sh` and
  `scripts/coverage-check.sh` each run their own `npm ci` and wipe `node_modules` out
  from under a concurrent typecheck/lint.
- Install module and root dependencies before the root `npm run typecheck`; the root
  `tsconfig.json` includes staff-web sources and is the only one with
  `exactOptionalPropertyTypes: true`. Missing staff-web deps produce a wall of
  misleading `Cannot find module 'react'` errors, not a TS2375 regression.
- `ReleasedReport.result` is optional but not `undefined`-writable: omit the key (rest
  destructuring) in fixtures instead of assigning `result: undefined`.
- Re-run the exact failing gate named in the escalation (`bash scripts/frontend-check.sh`),
  not just the module check — a module-local green can hide a root strict-typecheck red.
- Before adding another notes-only pass, check whether the escalation's error string
  exists in the repository; if it is external tooling, repeated scoped passes cannot
  fix it and only add notes files to the PR. Establish all scoped gates green, then
  stop and request an owner decision.

## Why this is not finished

The scoped story work is finished and green; the task remains unmerged because the
integrator's full gate repeatedly fails for out-of-scope reasons, not for the
patient-mobile diff.

- The failing condition in the escalation is the integrator's full gate, which locally
  exceeds the runner time limit while applying migrations (exit 124) and writes no
  fresh Surefire report — producing "integration run failed without fresh, usable
  test results (compile or setup error)" (Finding 4).
- The intermittent tracker-violation line comes from a dirty TODO.md in the
  integrator's checkout; this branch's scoped diff has no tracker file (Finding 6).
- All scoped gates pass with exact commands and final lines above, including the exact
  failing gate `scripts/frontend-check.sh` and the worker typecheck/coverage gates.
  There is no in-scope code change left that can make the full gate pass.
- The accumulated notes-file count may trigger the `needs-human` PR-size policy
  (Finding 7), which only an owner/integrator can resolve.

Most likely cause: a runner time limit / external orchestrator artifact plus a
PR-size/tracker condition in the integrator, not a code defect. What was tried: fresh
sequential installs and every scoped frontend gate, all green (tool evidence). Exact
next step for the owner/integrator: inspect the red CI job's logs for the exact SHA,
then resolve the Java/external-gate timeout, the dirty-tracker condition and the
notes-file-count `needs-human` policy in a separate change, and re-run the integrator
gate. No further worker code pass on this story is productive; each one only adds a
notes file.
