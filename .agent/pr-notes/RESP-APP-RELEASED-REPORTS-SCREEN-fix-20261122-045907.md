# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — independent re-verification, 2026-11-22 04:59)

## Summary

Continuation fix pass on branch `swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505`.
I rehydrated from the issue #656 / PR #655 findings and the newest hand-off note
(`RESP-APP-RELEASED-REPORTS-SCREEN-fix-20261122-045110.md`), merged `origin/main`
at the start (`Already up to date.`), installed all three locked dependency sets,
loaded the `orevia-delivery`, `orevia-senior-mobile` and
`orevia-senior-typescript` guidance, routed a bounded advisory review to the
senior-mobile specialist, and independently re-ran every gate for the touched area.

**The scoped repair is already present and correct at commit `5eccf8dc`:** the
no-result synthetic fixture in `ReleasedReportsScreen.tablet.test.tsx` omits the
optional `result` key with rest destructuring
(`const { result: _discardedResult, ...withoutResult } = report;`, line 236)
instead of assigning `undefined`, so the root strict typecheck
(`exactOptionalPropertyTypes: true`) no longer raises TS2375. I made **no new
production, test, type, config, threshold, exclusion, tracker, workflow or
`scripts/` change** in this pass — the two scoped source files are already
complete. This notes file is the only new file in this pass. The earlier
debugger note that "the fix was never applied" was stale relative to current
branch history.

**Result: every in-scope frontend gate is green with 100% coverage.** The TS2375
defect reported by earlier integrator runs does not reproduce at branch HEAD.
`scripts/check.sh` still exits 1, but only at the pre-existing, out-of-scope
CI-profile artifact recorded below.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`
and decision `docs/decisions/0005-patient-app-tablets.md`).

Acceptance mapping (entry point → test, all green in this run):

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Reuse `windowClassFor` from `src/app/layout/useWindowClass.ts` so the 600/840 dp rule covers reports on rotation/split-screen; no behaviour change. | Matrix test over compact/regular/large × 1.0/1.5/2.0 × portrait/landscape; `windowClassFor` unit tests unchanged. |
| PAT-02 | No persistent clinical cache, no new storage/state, no changed download flow; revision/issuer/effective-date/correction/download labels unchanged. | `reports-screen.test.tsx` integration test (authorises the exact tapped revision) still passes; the screen imports no storage client. |
| UX-01 | Capped, centred content on large; two panes on large; ≥ 44 pt touch targets; safe-area insets respected; no truncation or capped font scaling at 200%; identical accessibility-label order; RTL mirrors insets and row direction. | `ReleasedReportsScreen.tablet.test.tsx`: 9-case matrix + Arabic RTL case + no-result/no-provider case; `expectReflowAtTwoHundredPercent`. |

This fix pass only removes a type-illegal test assignment; runtime behaviour and
all acceptance scenarios are unaffected.

Entry-point-to-test trace: `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The repaired test
(`keeps a single summary pane when a report has no result detail and tolerates no provider`)
renders the exported screen directly, drives `Dimensions.get` through
`useWindowDimensions`, injects `SafeAreaInsetsContext`, and asserts the rendered
tree and accessibility labels. The 21-test targeted trace (this file plus
`reports-screen.test.tsx`) was green in earlier runs; the full 79-suite module
gate is green in this run.

## Risk class

Routine (`docs/WORKFLOW.md`). Test-only type repair — no clinical decision,
authorisation, patient association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo specialist (`orevia-senior-mobile`, advisory
review completed this pass) and Senior TypeScript (`orevia-senior-typescript`
guidance loaded). The bounded advisory review found **no blocking finding**: the
rest-omission is the smallest correct fix under `exactOptionalPropertyTypes`,
`Omit<ReleasedReport, 'result'>` is assignable to `readonly ReleasedReport[]`,
`_discardedResult` is exempt from both `noUnusedLocals` (rest-sibling rule) and
`@typescript-eslint/no-unused-vars` (`varsIgnorePattern: '^_'`), and runtime
behaviour is identical. Evidence sources: **tool** (commands I executed and
observed myself) and **reviewer** (the bounded advisory finding). No
simulated-user or human-participant evidence was involved or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix;
no reserved migration version was used. Synthetic fixture only — no patient
data, identifier, credential or real domain name appears in the change or note.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event was touched. The screen
remains presentational: all report data and download authorisation still arrive
from the authenticated client.

## Migration and rollout considerations

None. No deployment or rollout change; existing app users keep the same data
flow (pure render change).

## Test evidence

Evidence source: **tool** — I, the primary agent, executed every command below
and observed the results. No simulated user or human participant was involved.

- `git merge --no-edit origin/main` → `Already up to date.` (run at start; no
  conflict markers in the scoped `.ts`/`.tsx` files).
- Root `npm ci --no-audit --no-fund` → `added 164 packages in 2s` (exit 0).
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → exit 0 (run inside
  `bash apps/patient-mobile/check.sh`).
- `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages in 5s`
  (exit 0), so the strict root typecheck resolves React types.
- `bash apps/patient-mobile/check.sh` → **EXIT 0**. Mandatory frontend
  definition-of-done check (pinned `npm ci` → typecheck → lint → Jest with
  enforced 100% coverage). Final lines:
  ```
  All files          |     100 |      100 |     100 |     100 |
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        14.001 s
  Ran all test suites.
  ```
- **Mandatory patient-mobile typecheck final lines**
  (from `cd apps/patient-mobile && npm run typecheck`, EXIT 0, zero `error TS…`):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- `cd apps/patient-mobile && npm run lint` → `✖ 4 problems (0 errors, 4 warnings)`.
  All 4 warnings are pre-existing `import/no-duplicates` /
  `@typescript-eslint/array-type` in out-of-scope
  `enrollment/screens.tablet.test.tsx` and `tracker/presentation.test.ts`; 0 in
  the two scoped files.
- Root `npm run typecheck` (strict `exactOptionalPropertyTypes`) → **EXIT 0**.
  This is the command that originally produced TS2375. Final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  `error TS` count: 0 — the defect does not reproduce at branch HEAD.
- `bash scripts/frontend-typecheck-check.sh` → **EXIT 0**. Final line:
  `frontend typecheck: passed (or no frontend files changed)` (4 pre-existing
  warnings, 0 errors, none in scoped files). The delivery worker re-runs this as
  a hard stop.
- `bash scripts/frontend-check.sh` → **EXIT 0**. Final line:
  `frontend standards: policy and tool commands passed` (root Vitest tooling
  test 1/1 green).
- `bash scripts/check.sh` → **EXIT 1** at the first gate:
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (known
  skip-worktree CI-profile artifact, see Findings). It stops before any scoped
  frontend command.
- Not run locally: `bash apps/api/check.sh` / full `mvn verify` and `db/check.sh`
  (standing no-full-`mvn-verify` worker rule; frontend-only scope). No Java file
  or migration was changed.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (`npm ci` → `npm run typecheck` →
`npm run lint` → `npm run test:coverage -- --ci` with the module's enforced 100%
global threshold).

Result: `All files          |     100 |      100 |     100 |     100 |`;
`features/reports` directory and `ReleasedReportsScreen.tsx` (shown as
`...rtsScreen.tsx` in the truncated report) both at **100/100/100/100**;
`Test Suites: 79 passed, 79 total`; `Tests: 1127 passed, 1127 total`. No
threshold, exclusion, `@Generated`/ignore comment or skip was added or removed.
(Type-only `types.ts` files show 0/0 statements and do not lower the enforced
global threshold.)

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
| `useContext(...) ?? NO_INSETS` — null insets | `keeps a single summary pane …` (provider value `null`) |
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
   `bash apps/patient-mobile/check.sh` (100% coverage, 1127 tests) are all green
   in my run. The TS2375 failure reported by earlier integrator runs does not
   reproduce at branch HEAD.
2. **CI-profile artifact blocks `scripts/check.sh` (out of scope, not fixed).** In
   this worker environment `opencode.json` carries the skip-worktree flag
   (verified `git ls-files -v opencode.json` → `S`): the worktree file is the CI
   profile (sha256 `50005c9c…`, byte-identical to `.github/opencode.ci.json`),
   while `git show HEAD:opencode.json` (sha256 `0dc2ac8e…`) and `origin/main` are
   byte-identical. `agent-run.sh`'s `activate_ci_config` causes the swap, so
   `check-pack.py` judges the CI profile, not the committed local profile — the
   exact false positive documented in the delivery skill and tracked as
   OPS-CW-003-01 (closed) in MEMORY.md. I did not run `gen-opencode.py` or edit
   `opencode.json`, `.opencode/` or any tracker to force a green local gate.
3. **Pre-existing 4 lint warnings (not in scoped files).** `npm run lint` reports
   `4 problems (0 errors, 4 warnings)` in `enrollment/screens.tablet.test.tsx`
   and `tracker/presentation.test.ts`; none in the two scoped files.
4. **Out-of-scope worker_gate test errors (not fixed).** Earlier notes record two
   `FileNotFoundError: apps/api/target/surefire-reports` errors in the
   `worker_gate` unittest suite (missing `target/` before Maven creates it);
   pre-existing on `origin/main`, and `scripts/` is protected and outside the
   two-file scope. `check.sh` stops at check-pack in this run, so I did not
   re-observe them here.
5. **Out-of-scope React `act()` warning (not fixed).** An earlier full-suite run
   showed an `act()` warning from `src/app/PatientResource.tsx:54`; tests pass.
   Unrelated to the scoped file.
6. **Conflict markers in HL7 Java files are pre-existing and inert.** `<<<<<<<` /
   `>>>>>>>` markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/*.java`
   exist on `origin/main` too; `git merge --no-edit origin/main` reported
   `Already up to date.` and introduced none. Out of scope; flagged, not fixed.
7. **Cosmetic, pre-existing (not fixed).** The advisory review noted an orphaned
   doc comment in `ReleasedReportsScreen.tablet.test.tsx:47` describing
   `renderScreen`; it has no gate, correctness or a11y impact and is not part of
   this fix's scope.
8. **No new defect was introduced by this fix.**

## Lessons for workers

A patient-mobile module-local `tsc --noEmit` does **not** enable
`exactOptionalPropertyTypes`; the root `npm run typecheck` (inside
`scripts/frontend-check.sh`) does. Run `bash apps/patient-mobile/check.sh`,
`bash scripts/frontend-typecheck-check.sh` **and** `bash scripts/frontend-check.sh`
before calling a frontend fix green. Under `exactOptionalPropertyTypes`, omit an
optional key with rest destructuring (`const { key: _unused, ...rest } = value;`)
rather than writing `key: undefined`; `delete` is also invalid on a `readonly`
member. The root strict typecheck needs both modules' `node_modules` present
(React types resolve from `apps/staff-web/node_modules`), so run the module
`npm ci` steps before judging root `error TS…` output. In this worker
environment `check-pack.py`'s "opencode.json is stale" is a known skip-worktree
CI-profile artifact: verify it with `git ls-files -v opencode.json` and sha256
comparison, record it, and do not edit `opencode.json`, `scripts/` or any
tracker to force a green local gate. Before re-recording yet another evidence
note on a resumed fix, check the branch history first — repeated continuation
passes add no value once the scoped fix is already committed and green.

## Why this is not finished

**The scoped implementation IS finished** — every in-scope frontend gate is
green with 100% coverage. This section exists only to record, per the hand-off
contract, the residual repository-wide command failure so the next worker or
person does not rediscover it. No scoped code change is needed.

- Command/error: `bash scripts/check.sh` → EXIT 1, first actionable line
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (same result
  from `python3 -I scripts/check-pack.py`).
- Most likely cause: `opencode.json` is a skip-worktree CI profile
  (`git ls-files -v` = `S`; worktree sha prefix `50005c9c` equals
  `.github/opencode.ci.json`), while the committed blob (and `origin/main`) is
  byte-identical at `0dc2ac8e`. The gate is judging the CI profile, not HEAD —
  a known false positive, not caused by this branch's two-file diff.
- What I tried: confirmed merge state (`Already up to date.`), skip-worktree flag
  and all sha256 values; installed all three locked dependency sets; and ran
  every applicable frontend gate directly — all green, including strict root
  typecheck, both frontend check scripts, the 100%-coverage module gate (1127
  tests) and the lint check. I did not edit any out-of-scope file to force a
  green local gate.
- Exact next step: the trusted integrator/CI (which checks out the committed
  local profile, not the CI copy) runs the authoritative `scripts/check.sh`.
  On that verdict this branch is ready to merge; no further code change is
  required from this story's scope. This task has already outlived the
  debugger/fix round limit, so owner escalation — not another scoped code edit
  or another evidence-only commit — is the correct next action.
