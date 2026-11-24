# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — continuation, 2026-11-22 04:27)

## Summary

Continuation fix pass for the responsive `ReleasedReportsScreen` work on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505`. I rehydrated from the findings in
issue #656 / PR #655 and the newest hand-off note
(`.agent/pr-notes/RESP-APP-RELEASED-REPORTS-SCREEN-fix-20261122-041651.md`),
merged `origin/main`, installed the three locked dependency sets, and
independently re-ran every gate for the touched area.

The branch already carries the correct one-line repair (`5eccf8dc`): the
no-result synthetic fixture in `ReleasedReportsScreen.tablet.test.tsx` omits the
optional `result` key with rest destructuring
(`const { result: _discardedResult, ...withoutResult } = report;`, line 236)
instead of assigning `undefined`, so the ROOT strict typecheck
(`exactOptionalPropertyTypes: true`) no longer raises TS2375. I did **not**
change production code, types, config, a threshold, an exclusion, a tracker, a
workflow or `scripts/` in this pass: the scoped implementation is already
complete and the fix is correct. This notes file is the only new file.

**Result: the two scoped files are complete and every applicable frontend gate
is green at 100% coverage.** Two repository-wide commands still fail for
pre-existing, out-of-scope reasons (recorded under Findings and "Why this is not
finished"). No further scoped code change is needed or safe to make.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`
and decision `docs/decisions/0005-patient-app-tablets.md`).

Acceptance mapping (entry point-to-test evidence, all still green after the repair):

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Reuse `windowClassFor` from `src/app/layout/useWindowClass.ts` so the 600/840 dp rule covers reports on rotation/split-screen; no behavior change. | Matrix test over compact/regular/large x 1.0/1.5/2.0 x portrait/landscape; `windowClassFor` unit tests unchanged. |
| PAT-02 | No persistent clinical cache, no new storage/state, no changed download flow; revision/issuer/effective-date/correction/download labels unchanged. | `reports-screen.test.tsx` integration test (authorises the exact tapped revision) still passes; the screen imports no storage client. |
| UX-01 | Capped, centred content on large; two panes on large; 48 pt panes and >= 44 pt touch targets; safe-area insets respected; no truncation or capped font scaling at 200%; identical accessibility-label order; RTL mirrors insets and row direction. | `ReleasedReportsScreen.tablet.test.tsx`: 9-case matrix + Arabic RTL case + no-result/no-provider case; `expectReflowAtTwoHundredPercent`. |

This fix pass only removes a type-illegal test assignment; runtime behaviour and
all acceptance scenarios are unaffected.

Entry-point-to-test trace: `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The repaired test (`keeps a single summary pane when a
report has no result detail and tolerates no provider`) renders the exported
screen directly, drives `Dimensions.get` through `useWindowDimensions`, injects
`SafeAreaInsetsContext`, and asserts the rendered tree and accessibility labels.

## Risk class

Routine (`docs/WORKFLOW.md`). Test-only type repair — no clinical decision,
authorization, patient association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo specialist (`orevia-senior-mobile`, loaded for
this pass) and Debugger (`orevia-debugger`, loaded for the failing-gate
triage). An earlier bounded, read-only advisory review found no blocking issue:
rest-omission is the smallest correct fix, the rest object is assignable to the
`reports` prop, `_discardedResult` is exempt from `noUnusedLocals` (rest-sibling
rule) and `@typescript-eslint/no-unused-vars` (`varsIgnorePattern: '^_'`), and
runtime behaviour is identical. No new code was added in this pass, so no new
specialist review was required. Evidence sources: **tool** (commands I executed
myself) and **reviewer** (the earlier advisory finding). No simulated-user or
human-participant evidence was involved or claimed.

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
  conflict markers in scoped files).
- `npm ci --no-audit --no-fund` (root) → `added 164 packages` (exit 0).
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → `added 1408 packages` (exit 0).
- `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages` (exit 0),
  so the strict root typecheck resolves React types.
- `bash apps/patient-mobile/check.sh` → EXIT 0. Mandatory frontend
  definition-of-done check (npm ci → typecheck → lint → Jest with enforced 100%
  coverage). Final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        12.342 s
  Ran all test suites.
  ```
- **Mandatory patient-mobile typecheck final lines** (`cd apps/patient-mobile && npm run typecheck`, EXIT 0, zero `error TS…`):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- `npm run typecheck` (root, strict `exactOptionalPropertyTypes`) → EXIT 0. This
  is the command that originally produced TS2375. Final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  No `error TS…` output — the defect does not reproduce at branch HEAD.
- `bash scripts/frontend-typecheck-check.sh` → EXIT 0. Final line:
  `frontend typecheck: passed (or no frontend files changed)` (4 pre-existing
  lint warnings in out-of-scope `enrollment/screens.tablet.test.tsx` and
  `tracker/presentation.test.ts`; 0 errors, none in scoped files). The delivery
  worker re-runs this as a hard stop.
- `FRONTEND_AUDIT=skip bash scripts/frontend-check.sh` → EXIT 0. Final lines:
  `All matched files use Prettier code style!` and
  `frontend standards: policy and tool commands passed`. The audit was skipped
  locally to avoid a registry call; it is unrelated to this two-file fix.
- `python3 -I -m unittest discover -s scripts/tests -q` → EXIT 1, 8 pre-existing
  out-of-scope failures (2 `FileNotFoundError: apps/api/target/surefire-reports`,
  6 `opencode.json is stale`). See Findings.

Not run locally: `bash apps/api/check.sh` / full `mvn verify` and `db/check.sh`
(standing no-full-`mvn-verify` worker rule; frontend-only scope). No Java file
or migration was changed.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (`npm ci` → `npm run typecheck` →
`npm run lint` → `npm run test:coverage -- --ci` with the module's enforced 100%
global threshold).

Result: `All files          |     100 |      100 |     100 |      100 |`;
`features/reports` directory and `ReleasedReportsScreen.tsx` both at
**100/100/100/100**; `Test Suites: 79 passed, 79 total`;
`Tests: 1127 passed, 1127 total`. No threshold, exclusion,
`@Generated`/ignore comment or skip was added or removed. (Type-only `types.ts`
files show 0/0 statements and do not lower the enforced global threshold.)

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

1. **Fix present, correct and verified — no further scoped code change needed.**
   The committed repair at `5eccf8dc` is correct. Root `npm run typecheck`,
   `scripts/frontend-check.sh`, `scripts/frontend-typecheck-check.sh` and
   `bash apps/patient-mobile/check.sh` (100% coverage, 1127 tests) are all green
   in my run. The TS2375 failure reported by earlier integrator runs does not
   reproduce at branch HEAD. A debugger note that "the fix was never applied"
   was stale relative to the current branch history, which includes `5eccf8dc`.
2. **Out-of-scope tooling defect blocks the local Python gate (not fixed).**
   `python3 -I -m unittest discover -s scripts/tests -q` raises two
   `FileNotFoundError: 'apps/api/target/surefire-reports'` errors in
   `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate`
   and `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`.
   I confirmed `apps/api/target/surefire-reports` does not exist in this clean
   checkout; `usable_reports()` (from `8f363e02`, `FIX-WORKER-REPORTS`) scans
   that directory and the two tests mock `Path.is_dir` but not
   `usable_reports`. This is **pre-existing on `origin/main`**, not caused by
   this branch. `scripts/` is protected and outside the two-file scope, so I did
   not change it. A worker `task-requests` block cannot target `scripts/`
   (`scope_ok` rejects those paths), so no valid task request is filed; an owner
   must fix `scripts/tests/test_worker_gate.py` and
   `scripts/tests/test_worker_gate_coverage.py`.
3. **Pre-existing environment artifact (not fixed).** The other 6 unittest
   failures and `check-pack: opencode.json is stale` are a skip-worktree CI
   profile artifact. I confirmed first-hand: `git ls-files -v opencode.json` =
   `S`; worktree sha256 prefix `50005c9c` equals `.github/opencode.ci.json`,
   while `git show HEAD:opencode.json` and `origin/main:opencode.json` are
   byte-identical (sha prefix `0dc2ac8e`). `agent-run.sh`'s `activate_ci_config`
   causes it. Not a branch regression and not fixable from task scope; tracked
   as OPS-CW-003-01 (closed false positive) in MEMORY.md.
4. **Out-of-scope React `act()` warning (not fixed).** An earlier full-suite run
   showed an `act()` warning from `src/app/PatientResource.tsx:54`; tests still
   pass. Unrelated to the scoped file.
5. **Conflict markers in HL7 Java files are pre-existing and inert.** I found
   `<<<<<<<`/`>>>>>>>` markers in
   `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`;
   an earlier debugger note records these on `origin/main` too and Java compile
   / `mvn -Pfast test` still pass. Out of scope; flagged, not fixed.
6. **No new defect was introduced by this fix.**

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
environment, `check-pack.py`'s "opencode.json is stale" and the two
`worker_gate` `FileNotFoundError` errors are known pre-existing artifacts —
verify them, record them, and do not edit `opencode.json`, `scripts/` or any
tracker to force a green local gate.

## Why this is not finished

**The scoped implementation IS finished** — every applicable frontend gate is
green with 100% coverage. This section exists only to record, per the hand-off
contract, the residual repository-wide command failures so the next worker or
person does not rediscover them. No scoped code change is needed.

- Command/error: `python3 -I -m unittest discover -s scripts/tests -q` → EXIT 1,
  `FAILED (failures=6, errors=2)`. Two errors are
  `FileNotFoundError: 'apps/api/target/surefire-reports'` in the `worker_gate`
  tests; six failures are `opencode.json is stale` in `test_check_pack.py`.
  `bash scripts/check.sh` stops at the same `check-pack` line.
- Most likely cause: both are pre-existing on `origin/main` and environment/
  tooling related — the `worker_gate` tests mock `Path.is_dir` but not the new
  `usable_reports()` helper and the suite runs before Maven creates `target/`;
  `opencode.json` is a skip-worktree CI profile (`S`, sha `50005c9c`) rather than
  the committed blob (`0dc2ac8e`). Neither is caused by this branch's two-file
  diff.
- What I tried: confirmed the exact failures; confirmed the skip-worktree state
  and sha256 comparison; confirmed `surefire-reports` is absent; installed all
  three locked dependency sets; merged `origin/main`; and ran every applicable
  frontend gate directly — all green, including strict root typecheck, both
  frontend check scripts, prettier and 100% Jest coverage. I did not edit any
  out-of-scope file to force a green local gate.
- Exact next step: an owner/integrator fixes the two `worker_gate` tests to mock
  `usable_reports()` and runs the trusted integrator/CI in an environment with
  the committed `opencode.json` profile and freshly generated Surefire reports.
  On that verdict this branch is ready to merge; no further code change is
  required from this story's scope. Per the findings contract, this task has
  already outlived the debugger/fix round limit, so owner escalation is the
  correct next action rather than another scoped code edit.
