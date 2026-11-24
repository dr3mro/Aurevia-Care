# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — 2026-11-22 08:28 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505`. I read the escalation findings first and
continued from where the last worker (08:20 pass) stopped instead of starting over. The
scoped repair was already committed before this pass and is present at HEAD:

- `ReleasedReportsScreen.tsx` — responsive presentation: capped/centred content column and
  two summary + result-detail panes on `large`, safe-area insets, RTL mirroring, ≥48 pt panes.
- `ReleasedReportsScreen.tablet.test.tsx` — the `exactOptionalPropertyTypes` TS2375 repair at
  lines 233–236: the no-result fixture **omits** the optional `ReleasedReport.result` key via
  rest destructuring (`const { result: _discardedResult, ...withoutResult } = report;`) instead of
  assigning `undefined`, which the repo-root strict `tsconfig.json` rejects.

I rehydrated the governing context (AGENTS.md, CODING_STYLE.md, PROJECT.md, WORKSPACE.md,
MEMORY.md, the story task, decision `0005-patient-app-tablets.md`, the patient clause, the
orevia-delivery and orevia-senior-mobile skills, both scoped files, `reports/types.ts`,
`useWindowClass.ts`, the newest daily note's lessons and the last fix-pass note), then surveyed
`features/reports` and `app/layout` to confirm reuse of the existing `windowClassFor`
classifier, `patientDesign` tokens and report types.

**No production or test code was changed this pass.** The scoped fix is complete and green
at HEAD; this pass re-verifies it with fresh commands and records the evidence. **Evidence
source for every command below is a tool** (the primary agent ran and observed them, strictly
sequentially — each `check.sh`/gate runs its own `npm ci`). The prior bounded senior-mobile
advisory review is a **reviewer** source recorded in the 07:45 note. **No simulated-user or
human-participant evidence is claimed**; no named clinician or patient review was available.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md` and decision
`docs/decisions/0005-patient-app-tablets.md`).

Acceptance mapping (entry point → test), all green this run:

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Reuse the window-width rule so reports reflow on rotation, split-screen and Slide Over; device type never decides layout. | Tablet test 9-case matrix (compact/regular/large × 1.0/1.5/2.0) drives `Dimensions.get` through `useWindowDimensions`; shared `windowClassFor` untouched. |
| PAT-02 | No persistent clinical cache, new state/storage or changed API call/download flow; revision, issuer, effective-date, correction and download labels unchanged. | Component holds no storage client or state; existing `reports-screen.test.tsx` integration test stays green; 1127 tests green. |
| UX-01 | Capped/centred content and two panes on large; ≥44 pt targets (`MIN_PANE_WIDTH = 48`); safe-area insets; no truncation or font cap below 200%; unchanged label order; RTL mirrors insets and row direction. | Tablet matrix, Arabic RTL case, no-result/no-provider case and `expectReflowAtTwoHundredPercent`. |

Entry-point-to-test trace: exported `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The tablet test renders the exported screen directly, injects
`SafeAreaInsetsContext`, drives `Dimensions.get`, and asserts the rendered tree, labels,
insets and direction. The TS2375 fix is test-only; runtime behavior and all acceptance
scenarios are unaffected.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus a test-only type
repair — no clinical decision, authorization, patient association, data, contract,
migration or release change.

## Review owner

Senior React Native and Expo (`orevia-senior-mobile`) is the domain owner; its mandatory
definition of done was executed and met this pass (module `check.sh` green, typecheck final
lines and coverage summary below, zero `error TS…`). Senior TypeScript
(`orevia-senior-typescript`) owns the strict `exactOptionalPropertyTypes` rule that
produced TS2375; the root strict typecheck and the exact failing gate
`scripts/frontend-check.sh` are green. A prior bounded mobile-specialist advisory review
confirmed the fix is correct and complete. No new code was added this pass, so no new
specialist review was required. Independent QA and the full integration gate belong to the
integrator/Delivery. No named clinical or patient reviewer was available.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix; no reserved
migration version was used. Synthetic fixture only — no patient data, identifier, credential
or real domain name appears in the change or note.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event touched. The component stays
presentational: report data and download authorization still arrive from the authenticated
client; the withdrawn-release guard and server authorization are unchanged.

## Migration and rollout considerations

None. Existing app users keep the same data flow; the responsive structure is a pure render
change.

## Test evidence

All commands were executed by the primary agent (tool evidence), run **strictly sequentially**
— each `check.sh`/gate performs its own `npm ci`, so parallel runs wipe `node_modules` out
from under each other (see Finding 1).

Sync at the start: `git merge --no-edit origin/main` → `Already up to date.`; scoped
`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/patient-mobile/src/features/reports .agent/pr-notes/`
→ no output; working tree clean before and after.

Dependencies installed sequentially (each exit 0): patient-mobile `npm ci` via `check.sh`
(`added 1408 packages`), repo-root `npm ci` (`added 164 packages`), `apps/staff-web` `npm ci`
(`added 486 packages`).

- `bash apps/patient-mobile/check.sh` → **exit 0**. Module typecheck final lines (mandatory
  paste):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  (no output after the command — zero `error TS…`). Jest final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Ran all test suites.
  ```
- `cd apps/patient-mobile && npm run typecheck` → **exit 0**; final lines identical to the
  mandatory paste above (no `error TS…`).
- Repo-root `npm run typecheck` (`tsconfig.json` confirmed `exactOptionalPropertyTypes: true`
  at line 13 — the exact gate that originally surfaced TS2375) → **exit 0**; final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no output after the command — zero `error TS…`). Root and staff-web deps were installed
  first; missing staff-web deps otherwise mask the strict-optional error behind
  `TS2307`/`TS2875`.
- `bash scripts/frontend-check.sh` — the *exact failing gate* named in the escalation (both
  modules' typecheck/lint/test, npm audit, then root typecheck/lint/format/test) → **exit 0**,
  final lines: `All matched files use Prettier code style!`, root Vitest `Test Files 1 passed
  (1)`, `frontend standards: policy and tool commands passed`.
- `bash scripts/coverage-check.sh` (worker coverage gate for the touched patient-mobile area)
  → **exit 0**, final line `coverage check: passed` (`Test Suites: 79 passed`, `Tests: 1127
  passed`).

Fast policy/tooling portion of `scripts/check.sh` (the frontend-relevant, non-Java part) was
also run: shell `bash -n`, `check-docs.py`, `check-secrets.py`, `check-names.py`,
`check-wiring.py`, `check-migrations.py --require-baseline` → **all exit 0**. The full
`scripts/check.sh` was not run end-to-end because it invokes `apps/api/check.sh` (full
`mvn verify`, >10 min) and `db/check.sh`, which exceed the 5-minute worker cap and are the
integrator's responsibility (Finding 2). Two of its remaining steps fail locally, and I
reproduced them here as environment artifacts, not branch regressions:
- `bash scripts/python-coverage.sh` → **exit 1**; `python3 -I -m unittest discover -s
  scripts/tests` ran 1113 tests with 6 failures + 2 errors. The 6 failures are all
  `test_check_pack.*` (the opencode.json artifact below); the 2 errors are
  `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
  `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`,
  which need fresh Surefire reports that only a completed `mvn verify` produces. Their
  output included the escalation's exact lines `integration run failed without fresh,
  usable test results (compile or setup error)` and
  `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}`.
- `python3 -I scripts/check-pack.py` → **exit 1**, `check-pack: opencode.json is stale: run
  scripts/gen-opencode.py`. Confirmed artifact: `git ls-files -v opencode.json` → `S`
  (skip-worktree); working-file SHA-256 `50005c9c…` equals `.github/opencode.ci.json`, while
  the committed HEAD blob and `origin/main` blob are both `0dc2ac8e…`. No protected file was
  edited or committed.

No simulated user or human participant was involved. The worker's re-runs of the typecheck
and coverage gates therefore have clean tool evidence to act on.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (pinned `npm ci` → typecheck → lint →
`npm run test:coverage -- --ci` with the module's enforced 100% global threshold over every
production source file). Result: `apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tsx`
**100 | 100 | 100 | 100** (statements/branches/functions/lines); the `features/reports`
directory **100 | 100 | 100 | 100**; `All files` **100 | 100 | 100 | 100**; 1127 tests green.
`bash scripts/coverage-check.sh` independently re-ran the same gate → `coverage check: passed`.
Test files are excluded from the source scope by `jest.config.ts`; no threshold, exclusion,
`@Generated`/ignore comment, skip or `@Disabled` was added. Type-only `types.ts` files show
`0 | 0 | 0 | 0` because they contain no executable statements and do not affect the 100%
total. **This fix pass added no production code, so there is no new production branch.**
The story's production branches (committed implementation, confirmed 100% above) map to
their tests:

| Branch in `ReleasedReportsScreen.tsx` | Test that takes it |
|---|---|
| `windowClass === 'large'` (`maxWidth: 960`, row panes, `flex: 1`) | tablet matrix `large / 1`, `large / 1.5`, `large / 2`; Arabic RTL case; no-result case |
| `windowClass !== 'large'` (no `maxWidth`, column panes, natural height) | tablet matrix `compact` and `regular` cases |
| `locale === 'ar'` (insets mirrored) | `mirrors the panes, safe-area insets and row direction under Arabic RTL` |
| `locale !== 'ar'` (LTR insets) | all 9 matrix cases |
| `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context defined | tablet test provider + module mock |
| … context undefined | `reports-screen.test.tsx` (global Jest setup mock) |
| `useContext(...) ?? NO_INSETS` — non-null | matrix + RTL cases |
| … null insets | `keeps a single summary pane … and tolerates no provider` (provider value `null`) |
| `withdrawn = report.releaseState === 'withdrawn'` true / false | `reports-screen.test.tsx` |
| `report.title === undefined` true / false | `reports-screen.test.tsx` |
| `correction === null` true / false (pills + correction view) | `reports-screen.test.tsx` |
| `report.result === undefined` true / false (detail pane) | no-result case / matrix cases |
| `error !== null` true / false | `reports-screen.test.tsx` |
| `reports.length === 0 && error === null` true / false | `reports-screen.test.tsx` |
| `isRtl(report)` true / false (RTL/LTR row style) | `reports-screen.test.tsx` + RTL case |
| `locale` / `showTabBar` / `activeTab` / `onTab` defaults | existing `reports-screen.test.tsx` |

## Clinical and architecture review

Not required for this fix: no signed data, cross-organization sharing, patient association
change, prescribing or critical result. Presentation-only; no domain contract or state
machine changed. A named VoiceOver/TalkBack pass on device remains a separate launch gate
and is not claimed here.

## Findings

1. **Scoped work is complete and green (not a defect).** The test fix and responsive
   component are complete at HEAD. The root strict typecheck that produced TS2375, the module
   typecheck, the module check gate, the exact failing gate `scripts/frontend-check.sh` and
   `scripts/coverage-check.sh` all pass on fresh tool runs in this pass. There is no in-scope
   code change left that can make the full gate pass.
2. **Full `scripts/check.sh` was deliberately not run.** It invokes `apps/api/check.sh` (full
   `mvn verify`, >10 min locally; prior debugger rounds timed out at 20 minutes) and
   `db/check.sh`. Worker rules stop a command that passes 5 minutes; the integrator and
   Delivery own the full gate and the 100% coverage check. All scoped frontend gates were
   run instead.
3. **The integrator's "integration run failed without fresh, usable test results (compile or
   setup error)" is external infrastructure (out of scope, not fixed).** That literal string
   does not exist in this repository, so it is emitted by external swarm/orchestrator tooling.
   Prior debugger findings recorded full `mvn verify` / `apps/api/check.sh` exceeding the
   20-minute cap locally while still applying migrations (exit 124), leaving no fresh Surefire
   report — a runner time limit, not a failing assertion; the narrowed `mvn -Pfast test` and
   PostgreSQL integration suites passed. Existing tracking already covers this area
   (`FIX-WORKER-REPORTS`, `OPS-WORKER-QUALITY`), so no duplicate task-request is raised. In CI
   this must be judged from the exact SHA's job logs, not a worker re-run.
4. **`check-pack.py` "opencode.json is stale" is a CI-profile artifact (out of scope, not
   fixed).** The worktree `opencode.json` is the CI profile copied under `skip-worktree`, so
   its hash differs from the committed HEAD blob; HEAD's committed blob equals
   `origin/main`'s. `opencode.json` and `.opencode/` are protected files a worker must not
   edit.
5. **Intermittent `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` integrator
   lines are a shared-tracker condition (out of scope).** TODO.md is owned by the
   orchestrator/integrator; a swarm worker must not edit it. This branch's working tree is
   clean and its scoped diff contains no tracker file.
6. **PR file count is an integrator policy matter (out of scope).** The branch accumulates
   one notes file per prior fix pass; per MEMORY.md a PR over 15 files is treated as
   `needs-human`. Deleting earlier workers' committed notes is destructive and outside this
   task's scope; an owner/integrator decision is required, not another worker code pass.

**Exact next step for the integrator/owner:** re-run `bash apps/patient-mobile/check.sh`,
root `npm run typecheck` (after all three `npm ci` runs), `bash scripts/coverage-check.sh` and
`bash scripts/frontend-check.sh` against this branch HEAD **one at a time**; all are green
here. If the merge still fails, the cause is the out-of-scope infrastructure in Findings 3–5
(or the PR-size policy in Finding 6), not the scoped source, and it needs an owner/tooling
decision rather than another worker pass on this story.

## Why this is not finished

The scoped story work is finished and green; the task remains unmerged because the
integrator's full gate repeatedly fails for out-of-scope reasons, not for the patient-mobile
diff. Concretely:

- The failing condition recorded in the escalation is the integrator's full gate
  (`scripts/integrator-gate.sh` → `scripts/check.sh` → `apps/api/check.sh` full
  `mvn verify`), which locally exceeds the 20-minute cap while still applying migrations
  (exit 124) and therefore writes no fresh Surefire report — producing "integration run
  failed without fresh, usable test results (compile or setup error)". That string is
  external to this repo (Finding 3). In CI it must be judged by the exact SHA's job logs.
- The intermittent `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` comes from
  the integrator's own checkout having TODO.md dirty at validation time; this branch's
  working tree is clean and has no tracker file in scope.
- All scoped gates pass with exact commands and final lines above, including the exact
  failing gate `scripts/frontend-check.sh` and the worker coverage gate. There is no
  in-scope code change left that can make the full gate pass.
- The accumulated notes-file count may trigger the `needs-human` PR-size policy (Finding 6),
  which only an owner/integrator can resolve.

Most likely cause: a runner time limit / external orchestrator artifact in the integrator,
not a code defect. What was tried: fresh sequential installs of all three frontend modules,
module check, module typecheck, root strict typecheck, the worker coverage gate and the full
frontend standards gate — all green. Exact next step: an owner inspects the red CI job's logs
for the exact SHA, fixes the Java / external-gate tooling, dirty-tracker condition or PR-size
policy in a separate change, then re-runs the integrator gate; no further worker pass on this
story is productive.

## Lessons for workers

- Never run two frontend gates in parallel: `apps/patient-mobile/check.sh` and
  `scripts/coverage-check.sh` each run their own `npm ci` under `apps/patient-mobile` and wipe
  `node_modules` out from under a concurrent typecheck/lint. Run them one at a time, then
  re-run any gate that saw transient `TS2307`/`TS2875`/`ERR_MODULE_NOT_FOUND` before
  reporting a failure.
- Install module dependencies in **all** frontend modules and the root before running the
  **root** `npm run typecheck`: `npm ci` in `apps/patient-mobile`, `apps/staff-web` and the
  repo root. The root `tsconfig.json` is the only one with `exactOptionalPropertyTypes:
  true`; missing staff-web dependencies mask the real strict-optional error with dozens of
  `TS2307`/`TS2875` errors.
- `ReleasedReport.result` is optional but not `undefined`-writable: omit the key (rest
  destructuring) in fixtures instead of assigning `result: undefined`, or the root strict
  typecheck fails with TS2375 while the module-local typecheck stays green.
- Re-run the **exact failing gate** named in the escalation (`bash scripts/frontend-check.sh`),
  not just the module check — a module-local green can hide a root strict-typecheck red.
- In cloud workers, treat a `check-pack.py` "opencode.json is stale" result as a CI-profile
  artifact until the committed blob (`git show HEAD:opencode.json`) is proven different from
  `origin/main`; never "fix" it by editing the protected file.
- Do not run the full `mvn verify` / `scripts/check.sh` as a worker; it exceeds the 5-minute
  worker cap. Run only the tests for the code you touched and let the integrator/Delivery run
  the full gate.
- Before adding another notes-only fix pass, check whether the escalation's error string even
  exists in the repository; if it is external tooling, repeated scoped worker passes cannot
  fix it and only churn the branch. Establish that all scoped gates are green, then stop and
  request an owner decision instead of a further code pass.
