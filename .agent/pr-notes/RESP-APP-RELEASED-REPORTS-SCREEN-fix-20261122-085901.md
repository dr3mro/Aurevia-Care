# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — 2026-11-22 09:05 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505`. I read the escalation findings first
and continued from the 08:53 pass instead of starting over. I did **not** repeat work
the findings record as tried.

I rehydrated the governing context as instructed: `.agent/context-files.txt`,
`AGENTS.md`, `CODING_STYLE.md`, `PROJECT.md`, `WORKSPACE.md`, `MEMORY.md`, `TODO.md`
("Instructions for every agent" + this story's entry), `docs/WORKFLOW.md`, BUGS-relevant
history, the `0005-patient-app-tablets.md` decision, the product clause for
verified-adult self-access, and the `orevia-delivery`, `orevia-senior-mobile`
and `orevia-senior-typescript` skills. I surveyed `features/reports` and
`app/layout` and confirmed reuse of the existing `windowClassFor` classifier,
`patientDesign` tokens and `ReleasedReport` type.

At HEAD the scoped repair is already committed (`5eccf8dc`, `70d4be33`, `c59551bc`):

- `ReleasedReportsScreen.tsx` — centred/capped content column and two summary +
  result-detail panes on `large`, safe-area insets, RTL mirroring, ≥48 pt panes, no
  new state or cache, unchanged labels/API.
- `ReleasedReportsScreen.tablet.test.tsx` — the `exactOptionalPropertyTypes` TS2375
  repair: the no-result fixture **omits** the optional `result` key via rest
  destructuring (`const { result: _discardedResult, ...withoutResult } = report;`)
  instead of assigning `undefined`, which the repo-root strict `tsconfig.json`
  rejects.

**No production or test code was changed this pass.** I re-verified the committed
fix with fresh commands (below). Every command in this note was executed by the
primary agent — **evidence source: tool**. The bounded senior-mobile advisory
review from the 07:45 pass is **reviewer** evidence recorded in that pass's note.
**No simulated-user or human-participant evidence is claimed**; no named clinician
or patient review was available.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`
and decision `0005-patient-app-tablets.md`).

Acceptance mapping (entry point → test), all green this run:

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Window-width rule drives reflow on rotation/split-screen; device type never decides layout. | Tablet test 9-case matrix (compact/regular/large × 1.0/1.5/2.0) drives `Dimensions.get` through `useWindowDimensions`; shared `windowClassFor` untouched. |
| PAT-02 | No persistent clinical cache, new state/storage or changed API/download flow; revision, issuer, effective-date, correction and download labels unchanged. | Component holds no storage client or state; `reports-screen.test.tsx` integration test stays green; 1127 tests green. |
| UX-01 | Capped/centred content and two panes on large; ≥44 pt targets (`MIN_PANE_WIDTH = 48`); safe-area insets; no truncation or font cap below 200%; unchanged label order; RTL mirrors insets and row direction. | Tablet matrix, Arabic RTL case, no-result/no-provider case and `expectReflowAtTwoHundredPercent`. |

Entry-point-to-test trace: exported `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The tablet test renders the exported screen directly, injects
`SafeAreaInsetsContext`, drives `Dimensions.get`, and asserts tree, labels, insets
and direction. The TS2375 fix is test-only; runtime behavior is unaffected.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus a test-only
type repair — no clinical decision, authorization, patient association, data,
contract, migration or release change.

## Review owner

Senior React Native and Expo (`orevia-senior-mobile`) is the domain owner; its
mandatory definition of done was executed and met this pass (module `check.sh` green,
typecheck final lines and coverage summary below, zero `error TS…`). Senior
TypeScript (`orevia-senior-typescript`) owns the strict
`exactOptionalPropertyTypes` rule that produced TS2375; the root strict typecheck,
the exact failing gate `scripts/frontend-check.sh` and `scripts/frontend-typecheck-check.sh`
are green. A prior bounded mobile-specialist advisory review confirmed the fix is
correct and complete. No new code was added this pass, so no new specialist review
was required. Independent QA and the full integration gate belong to the
integrator/Delivery. No named clinical or patient reviewer was available.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix;
no reserved migration version was used. Synthetic fixture only — no patient data,
identifier, credential or real domain name appears in the change or note.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event touched. The component stays
presentational: report data and download authorization still arrive from the
authenticated client; the withdrawn-release guard and server authorization are
unchanged.

## Migration and rollout considerations

None. Existing app users keep the same data flow; the responsive structure is a
pure render change.

## Test evidence

All commands executed by the primary agent (tool evidence), run **strictly
sequentially** (each `check.sh` performs its own `npm ci`; parallel runs corrupt
`node_modules`).

Sync at the start: `git merge --no-edit origin/main` → `Already up to date.`
Working tree clean before and after; no scoped file has a conflict marker. The
pre-existing `<<<<<<<`/`>>>>>>>` markers reported repo-wide by `git grep` are inside
committed Javadoc comments in
`apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
on `origin/main` itself — my branch has zero diff there, they compile inertly, and
they are out of this task's scope (documented in the 08:57 debugger findings).

- `bash apps/patient-mobile/check.sh` → **exit 0**. Mandatory typecheck final lines:
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
- `cd apps/patient-mobile && npm run typecheck` → **exit 0**; final lines identical
  to the mandatory paste above (no `error TS…`).
- `bash scripts/frontend-check.sh` — the *exact failing gate* named in the
  escalation (both modules' typecheck/lint/test, npm audit, then repo-root `npm ci`,
  `tsc --noEmit -p tsconfig.json` with `exactOptionalPropertyTypes: true`, lint,
  format:check and root Vitest) → **exit 0**, final lines:
  ```
  All matched files use Prettier code style!
  Test Files  1 passed (1)
  Tests       1 passed (1)
  frontend standards: policy and tool commands passed
  ```
  The root strict typecheck that originally surfaced TS2375 produced no output and
  exited 0.
- `bash scripts/frontend-typecheck-check.sh` (the worker gate re-run by the delivery
  loop) → **exit 0**, final line:
  `frontend typecheck: passed (or no frontend files changed)`.
- `bash scripts/coverage-check.sh` (worker coverage gate for the touched
  patient-mobile area) → **exit 0**, final line `coverage check: passed`
  (`Test Suites: 79 passed`, `Tests: 1127 passed`).

Full `scripts/check.sh` / `scripts/worker-check.sh` were deliberately **not** run
end-to-end: they invoke `apps/api/check.sh` (full `mvn verify`, which prior debugger
rounds timed out at 20 minutes while applying migrations) and the PostgreSQL
integration suites, which exceed the 5-minute worker cap and are the integrator's
responsibility. All scoped frontend gates were run instead. No simulated user or
human participant was involved.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (pinned `npm ci` → typecheck → lint →
`npm run test:coverage -- --ci` with the module's enforced 100% global threshold over
every production source file). Result:
`apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tsx`
**100 | 100 | 100 | 100** (statements/branches/functions/lines); the `features/reports`
directory **100 | 100 | 100 | 100**; `All files` **100 | 100 | 100 | 100**; 1127 tests
green. `bash scripts/coverage-check.sh` independently re-ran the same gate →
`coverage check: passed`. Test files are excluded from the source scope by
`jest.config.ts`; no threshold, exclusion, `@Generated`/ignore comment, skip or
`@Disabled` was added. Type-only `types.ts` files show `0 | 0 | 0 | 0` because they
contain no executable statements and do not affect the 100% total. **This fix pass
added no production code, so there is no new production branch.** The story's
production branches (committed implementation, confirmed 100% above) map to their
tests:

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
| `withdrawn = report.releaseState === 'withdrawn'` true / false | `reports-screen.test.tsx` |
| `report.title === undefined` true / false | `reports-screen.test.tsx` |
| `correction === null` true / false (pills + correction view) | `reports-screen.test.tsx` |
| `report.result === undefined` true / false (detail pane) | no-result case / matrix cases |
| `error !== null` true / false | `reports-screen.test.tsx` |
| `reports.length === 0 && error === null` true / false | `reports-screen.test.tsx` |
| `isRtl(report)` true / false (RTL/LTR row style) | `reports-screen.test.tsx` + RTL case |
| `locale` / `showTabBar` / `activeTab` / `onTab` defaults | existing `reports-screen.test.tsx` |

## Clinical and architecture review

Not required for this fix: no signed data, cross-organization sharing, patient
association change, prescribing or critical result. Presentation-only; no domain
contract or state machine changed. A named VoiceOver/TalkBack pass on device remains
a separate launch gate and is not claimed here.

## Findings

1. **Scoped work is complete and green (not a defect).** The test fix and responsive
   component are complete at HEAD. The root strict typecheck that produced TS2375,
   the module typecheck, the module check gate, the exact failing gate
   `scripts/frontend-check.sh`, `scripts/frontend-typecheck-check.sh` and
   `scripts/coverage-check.sh` all pass on fresh tool runs this pass. There is no
   in-scope code change left that can make the full gate pass.
2. **No source change since 07:52.** `git log` shows the last source commit is
   `c59551bc`; every later commit is notes-only or a merge with `origin/main`.
   Worker 10's 08:57 "fix" added no source change — this pass did not redo it.
3. **Full `scripts/check.sh` / `scripts/worker-check.sh` were deliberately not run.**
   They invoke `apps/api/check.sh` (full `mvn verify`) and the PostgreSQL integration
   suites, which exceed the 5-minute worker cap; the integrator and Delivery own the
   full gate and the 100% coverage check.
4. **The integrator's "integration run failed without fresh, usable test results
   (compile or setup error)" is external infrastructure (out of scope, not fixed).**
   That literal string does not exist in this repository; prior debugger findings
   recorded full `mvn verify` / `apps/api/check.sh` exceeding the 20-minute cap
   while still applying migrations (exit 124), leaving no fresh Surefire report — a
   runner time limit, not a failing assertion. In CI it must be judged from the
   exact SHA's job logs, not a worker re-run. Existing tracking already covers this
   (`FIX-WORKER-REPORTS`, `OPS-WORKER-QUALITY`); no duplicate task-request is raised.
5. **`check-pack.py` "opencode.json is stale" is a CI-profile artifact (out of
   scope, not fixed).** The worktree `opencode.json` is the CI profile copied under
   `skip-worktree`; HEAD's committed blob equals `origin/main`'s. `opencode.json` and
   `.opencode/` are protected files a worker must not edit (MEMORY OPS-CW-003-01).
6. **Intermittent `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` lines
   are a shared-tracker condition (out of scope).** TODO.md is owned by the
   orchestrator/integrator; this branch's working tree is clean and its scoped diff
   contains no tracker file.
7. **PR file count is an integrator policy matter (out of scope).** The branch
   accumulates one notes file per prior fix pass; per MEMORY.md a PR over 15 files
   is treated as `needs-human`. Deleting earlier workers' committed notes is
   destructive and outside this task's scope; an owner/integrator decision is
   required, not another worker code pass. The exact next step for the
   integrator/owner is to judge the red CI job's logs for the exact SHA and resolve
   the tooling/tracker/PR-size condition in a separate change.

## Why this is not finished

The scoped story work is finished and green; the task remains unmerged because the
integrator's full gate repeatedly fails for out-of-scope reasons, not for the
patient-mobile diff. Concretely:

- The failing condition in the escalation is the integrator's full gate
  (`scripts/integrator-gate.sh` → `scripts/check.sh` → `apps/api/check.sh` full
  `mvn verify`), which locally exceeds the 20-minute cap while still applying
  migrations (exit 124) and writes no fresh Surefire report — producing "integration
  run failed without fresh, usable test results (compile or setup error)". That
  string is external to this repo (Finding 4).
- The intermittent tracker-violation line comes from the integrator's own checkout
  having TODO.md dirty at validation time; this branch's working tree is clean and
  has no tracker file in scope.
- All scoped gates pass with exact commands and final lines above, including the
  exact failing gate `scripts/frontend-check.sh` and the worker typecheck/coverage
  gates. There is no in-scope code change left that can make the full gate pass.
- The accumulated notes-file count may trigger the `needs-human` PR-size policy
  (Finding 7), which only an owner/integrator can resolve.

Most likely cause: a runner time limit / external orchestrator artifact in the
integrator, not a code defect. What was tried: fresh sequential installs and all
scoped frontend gates, all green. Exact next step: an owner inspects the red CI
job's logs for the exact SHA, fixes the Java/external-gate tooling, dirty-tracker
condition or PR-size policy in a separate change, then re-runs the integrator gate;
no further worker pass on this story is productive.

## Lessons for workers

- Never run two frontend gates in parallel: `apps/patient-mobile/check.sh` and
  `scripts/coverage-check.sh` each run their own `npm ci` under `apps/patient-mobile`
  and wipe `node_modules` out from under a concurrent typecheck/lint.
- Install module dependencies in all frontend modules and the root before running
  the root `npm run typecheck`; the root `tsconfig.json` is the only one with
  `exactOptionalPropertyTypes: true` and missing deps mask the real error.
- `ReleasedReport.result` is optional but not `undefined`-writable: omit the key
  (rest destructuring) in fixtures instead of assigning `result: undefined`.
- Re-run the exact failing gate named in the escalation (`bash scripts/frontend-check.sh`),
  not just the module check — a module-local green can hide a root strict-typecheck red.
- In cloud workers, treat a `check-pack.py` "opencode.json is stale" result as a
  CI-profile artifact until the committed blob is proven different from `origin/main`;
  never "fix" it by editing the protected file.
- Before adding another notes-only fix pass, check whether the escalation's error
  string exists in the repository; if it is external tooling, repeated scoped
  passes cannot fix it and only churn the branch. Establish that all scoped gates
  are green, then stop and request an owner decision.
