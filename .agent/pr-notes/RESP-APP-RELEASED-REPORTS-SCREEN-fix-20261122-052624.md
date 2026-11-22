# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — independent re-verification, 2026-11-22 05:26)

## Summary

Continuation fix pass on branch `swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505`. I
rehydrated from the issue #656 / PR #655 findings and the newest hand-off note
(`RESP-APP-RELEASED-REPORTS-SCREEN-fix-20261122-051606.md`), read the governing
docs and the story task file, merged `origin/main` at the start (`Already up to
date.`), loaded the `smartclinic-delivery`, `smartclinic-senior-mobile` and
`smartclinic-senior-typescript` guidance, and independently re-ran every gate
for the touched area with fresh tool commands.

**The scoped repair is already present and correct at committed ancestor
`5eccf8dc`.** The no-result synthetic fixture in
`ReleasedReportsScreen.tablet.test.tsx` omits the optional `result` key with
rest destructuring (`const { result: _discardedResult, ...withoutResult } =
report;`) instead of assigning `undefined`. Under the root strict typecheck's
`exactOptionalPropertyTypes: true`, writing `result: undefined` raised TS2375;
omitting the key is type-legal and the screen treats an absent result exactly
like an explicitly undefined one (`report.result === undefined ? null : ...`).
I made **no new production, test, type, config, threshold, exclusion, tracker,
workflow or `scripts/` change** — the two scoped source files are already
complete. This notes file is the only new file in this pass.

**Result: every in-scope frontend gate is green with 100% coverage.** The TS2375
defect reported by earlier integrator runs does not reproduce at branch HEAD.
`scripts/check.sh` still exits 1, but only at the pre-existing, out-of-scope
CI-profile artifact recorded under Findings. No scoped code change is needed.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`
and decision `docs/decisions/0005-patient-app-tablets.md`).

Acceptance mapping (entry point → test, all green in this run):

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Reuse the window-based layout rule so the 600/840 dp breakpoints cover reports on rotation/split-screen; no behaviour change. | Matrix test over compact/regular/large × 1.0/1.5/2.0 × portrait/landscape; `windowClassFor` unit tests unchanged. |
| PAT-02 | No persistent clinical cache, no new storage/state, no changed download flow; revision/issuer/effective-date/correction/download labels unchanged. | `reports-screen.test.tsx` integration test still green; the screen imports no storage client. |
| UX-01 | Capped, centred content on large; two panes on large; ≥ 44 pt touch targets; safe-area insets respected; no truncation or capped font scaling at 200%; identical accessibility-label order; RTL mirrors insets and row direction. | `ReleasedReportsScreen.tablet.test.tsx`: 9-case matrix + Arabic RTL case + no-result/no-provider case; `expectReflowAtTwoHundredPercent`. |

This fix pass only removes a type-illegal test assignment; runtime behaviour and
all acceptance scenarios are unaffected.

Entry-point-to-test trace: `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The repaired test
(`keeps a single summary pane when a report has no result detail and tolerates no provider`)
renders the exported screen directly, drives `Dimensions.get` through
`useWindowDimensions`, injects `SafeAreaInsetsContext`, and asserts the rendered
tree and accessibility labels.

## Risk class

Routine (`docs/WORKFLOW.md`). Test-only type repair — no clinical decision,
authorization, patient association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo (`smartclinic-senior-mobile`) and Senior TypeScript
(`smartclinic-senior-typescript`) guidance loaded and applied. Both skills'
definition-of-done was satisfied: `bash apps/patient-mobile/check.sh` green,
typecheck final lines pasted below, every `error TS…` resolved, no `any` or
`@ts-ignore`. No named clinician/patient review was available and none is
claimed. **Evidence source for every check below: tool** — commands I, the
primary agent, executed and observed myself. No reviewer, simulated-user or
human-participant evidence was added in this pass.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix;
no reserved migration version was used. Synthetic fixture only — no patient
data, identifier, credential or real domain name appears in the change or note.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event was touched. The screen
remains presentational: all report data and download authorization still arrive
from the authenticated client.

## Migration and rollout considerations

None. No deployment or rollout change; existing app users keep the same data flow.

## Test evidence

Evidence source: **tool** — I, the primary agent, executed every command below
and observed the results. No simulated user or human participant was involved.

- `git merge --no-edit origin/main` → `Already up to date.` (run at start; no
  conflict markers in the scoped `.tsx` files —
  `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/patient-mobile/src/features/reports/`
  returned none).
- `npm ci --no-audit --no-fund` for root, `apps/patient-mobile` and
  `apps/staff-web` → all exit 0 (`added 164`, `added 1408`, `added 486 packages`).
  Both modules' toolchains are present so the root strict typecheck resolves
  React types.
- **Mandatory patient-mobile gate** `bash apps/patient-mobile/check.sh` →
  **EXIT 0**. Final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        14.071 s
  Ran all test suites.
  ```
  (lint: `4 problems (0 errors, 4 warnings)`, all pre-existing in
  `enrollment/screens.tablet.test.tsx` and `tracker/presentation.test.ts`, none
  in a scoped file.)
- **Mandatory patient-mobile typecheck final lines**
  (`npm run typecheck` inside the module, exit 0, zero `error TS…`):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- Root `npm run typecheck` (strict `exactOptionalPropertyTypes: true`, the
  command that originally produced TS2375) → **EXIT 0**, `error TS` count 0.
  Final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  The defect does not reproduce at branch HEAD.
- `bash scripts/frontend-typecheck-check.sh` → **EXIT 0**, final line
  `frontend typecheck: passed (or no frontend files changed)`.
- `bash scripts/frontend-check.sh` → **EXIT 0**, final line
  `frontend standards: policy and tool commands passed`.
- Targeted scoped test:
  `cd apps/patient-mobile && ./node_modules/.bin/jest --ci --runInBand src/features/reports/ReleasedReportsScreen.tablet.test.tsx src/features/reports/reports-screen.test.tsx`
  → **EXIT 0**, `Test Suites: 2 passed`, `Tests: 21 passed`.
- `bash scripts/check.sh` → **EXIT 1** at the first gate only:
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (known
  skip-worktree CI-profile artifact, see Findings). It stops before any scoped
  frontend command.
- Not run locally: `bash apps/api/check.sh` / full `mvn verify` and `db/check.sh`
  (standing no-full-`mvn-verify` worker rule; frontend-only scope). No Java file
  or migration was changed.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (`npm ci` → `npm run typecheck` →
`npm run lint` → `npm run test:coverage -- --ci` with the module's enforced 100%
global threshold). Global summary: `All files` **100/100/100/100**.

Per-file coverage read directly from `apps/patient-mobile/coverage/lcov.info`
for the one changed production file:

```
SF:src/features/reports/ReleasedReportsScreen.tsx
FNF:3  FNH:3   (functions 100%)
LF:20  LH:20   (lines 100%)
BRF:37 BRH:37  (branches 100%)
```

Result: `ReleasedReportsScreen.tsx` at **100% lines, 100% branches, 100%
functions**; `All files` 100/100/100/100; `1127` tests green. No threshold,
exclusion, `@Generated`/ignore comment or skip was added or removed. Test files
are outside the source scope; type-only `types.ts` files report 0/0 statements
and do not lower the enforced global threshold.

This fix pass adds no production line or branch. Every new/changed branch and
the test that takes it (from the original implementation, all still green):

| New/changed branch | Test |
|---|---|
| `report.result === undefined` true → no result-detail pane (now type-legal via key omission); `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context undefined | `keeps a single summary pane … and tolerates no provider` (`renderScreen(..., false)` with `result` omitted) |
| `windowClass === 'large'` true → `maxWidth: 960`, row panes, `flex: 1` panes | Matrix cases `large / 1`, `large / 1.5`, `large / 2`; Arabic RTL case; no-result case |
| `windowClass === 'large'` false → column panes, natural-height panes | Matrix cases for `compact` and `regular` |
| `locale === 'ar'` true → `paddingStart` from `insets.right`, `paddingEnd` from `insets.left` | `mirrors the panes, safe-area insets and row direction under Arabic RTL` |
| `locale === 'ar'` false → LTR inset mapping | All 9 matrix cases |
| `useContext(...) ?? NO_INSETS` — non-null insets | Matrix + RTL cases (provider value supplied) |
| `useContext(...) ?? NO_INSETS` — null insets | `keeps a single summary pane …` (no provider / null) |
| `report.result === undefined` false → result-detail pane present | Matrix cases (result Card present) |

Other production branches (`error`, empty state, `withdrawn`, optional `title`,
`correction`, `isRtl`, `locale` default, `showTabBar`) remain covered by
`ReleasedReportsScreen.tablet.test.tsx` and `reports-screen.test.tsx`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical result was changed. Presentation-only test repair; no
domain contract, state machine, API or data boundary changed. A named
VoiceOver/TalkBack pass on device remains a separate launch gate and is not
claimed here.

## Findings

1. **Fix present, correct and independently verified — no further scoped code change needed.**
   The committed repair at `5eccf8dc` is correct. Root `npm run typecheck`,
   `scripts/frontend-typecheck-check.sh`, `scripts/frontend-check.sh`,
   `bash apps/patient-mobile/check.sh` (100% coverage, 1127 tests) and the
   targeted 21-test scoped run are all green in my run. The TS2375 failure
   reported by earlier integrator runs does not reproduce at branch HEAD.
   Earlier notes that "the fix was never applied" are stale relative to current
   branch history.
2. **CI-profile artifact blocks `scripts/check.sh` (out of scope, not fixed).**
   In this worker environment `opencode.json` carries the skip-worktree flag
   (`git ls-files -v opencode.json` → `S`). Worktree sha `50005c9c` equals
   `.github/opencode.ci.json`; `git show HEAD:opencode.json` (sha `0dc2ac8e`) is
   byte-identical to `git show origin/main:opencode.json` (`0dc2ac8e`). The
   committed local profile is therefore in sync; `check-pack.py` judges the CI
   copy that `agent-run.sh`'s `activate_ci_config` swaps in, not HEAD — the exact
   false positive documented in the delivery skill and tracked as OPS-CW-003-01
   (closed) in MEMORY.md. I did not edit `opencode.json`, `.opencode/`,
   `scripts/` or any tracker to force a green local gate.
3. **Pre-existing 4 lint warnings (not in scoped files).** 2 in
   `enrollment/screens.tablet.test.tsx` (`@typescript-eslint/array-type`) and 2
   in `tracker/presentation.test.ts` (`import/no-duplicates`), 0 errors, none in
   the two scoped files.
4. **Out-of-scope integrator/worker-gate failures (not fixed).** Earlier notes
   record an integrator `integration run failed without fresh, usable test
   results (compile or setup error)` and `FileNotFoundError:
   apps/api/target/surefire-reports` in the `worker_gate` unittest suite, plus
   `{"violations":[{"file":"TODO.md","rule":"tracker"}]}`. All are pre-existing
   on `origin/main`; `scripts/`, trackers and Java are outside the two-file
   scope. `check.sh` stopped at check-pack in this run, so I did not re-observe
   them here.
5. **Out-of-scope React `act()` warning (not fixed).** An earlier full-suite run
   showed an `act()` warning from `src/app/PatientResource.tsx:54`; tests pass.
   Unrelated to the scoped file.
6. **Cosmetic, pre-existing (not fixed).** Two consecutive doc comments sit above
   the `RenderedView` type alias in `ReleasedReportsScreen.tablet.test.tsx`; the
   first actually describes `renderScreen`. It has no gate, correctness or a11y
   impact, is not part of this fix's scope, and is left untouched rather than
   forcing another full-gate rerun for a cosmetic edit.
7. **No new defect was introduced by this fix.** No conflict markers were
   introduced by the `origin/main` merge.

## Lessons for workers

A patient-mobile module-local `tsc --noEmit` does **not** enable
`exactOptionalPropertyTypes`; the root `npm run typecheck` (run by
`scripts/frontend-check.sh`) does. Run `bash apps/patient-mobile/check.sh`,
`bash scripts/frontend-typecheck-check.sh` **and** `bash scripts/frontend-check.sh`
before calling a frontend fix green. Under `exactOptionalPropertyTypes`, omit an
optional key with rest destructuring (`const { key: _unused, ...rest } = value;`)
rather than writing `key: undefined`; `delete` is also invalid on a `readonly`
member. The root strict typecheck needs both modules' `node_modules` present
(React types resolve from `apps/staff-web/node_modules`), so install all three
locked dependency sets before judging root `error TS…` output. In this worker
environment `check-pack.py`'s "opencode.json is stale" is a known skip-worktree
CI-profile artifact: verify it with `git ls-files -v opencode.json` and by
comparing worktree / CI-copy / HEAD SHAs, record it, and do not edit
`opencode.json`, `scripts/` or any tracker to force a green local gate. Before
re-recording yet another evidence note on a resumed fix, check the branch history
first — repeated continuation passes add no value once the scoped fix is already
committed and green. This task has outlived the debugger/fix round limit;
further scoped edits or evidence-only commits do not unblock it.

## Why this is not finished

**The scoped implementation IS finished** — every in-scope frontend gate is
green with 100% coverage. This section exists only to record, per the hand-off
contract, the residual repository-wide command failure so the next worker or
person does not rediscover it. No scoped code change is needed.

- Command/error: `bash scripts/check.sh` → EXIT 1, first actionable line
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (same result
  from `python3 -I scripts/gen-opencode.py --check` against the worktree CI
  profile).
- Most likely cause: `opencode.json` is a skip-worktree CI profile
  (`git ls-files -v` = `S`; worktree sha prefix `50005c9c` equals
  `.github/opencode.ci.json`), while the committed blob (and `origin/main`) is
  byte-identical at `0dc2ac8e`. The gate is judging the CI profile, not HEAD — a
  known false positive, not caused by this branch's two-file diff.
- What I tried: confirmed merge state (`Already up to date.`), the skip-worktree
  flag and all three SHA values; installed all three locked dependency sets; and
  ran every applicable frontend gate directly — all green, including strict root
  typecheck, both frontend check scripts, the 21-test targeted scoped run and the
  100%-coverage module gate (1127 tests). I did not edit any out-of-scope file to
  force a green local gate.
- Exact next step: the trusted integrator/CI (which checks out the committed
  local profile, not the CI copy) runs the authoritative `scripts/check.sh` and
  the database/full integration tests. On that verdict this branch is ready to
  merge; no further code change is required from this story's scope. Because
  this task has already exceeded the debugger/fix round limit and the residual
  failure is out of scope, owner escalation — not another scoped code edit or
  another evidence-only commit — is the correct next action.
