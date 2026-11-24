# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — 2026-11-22 07:56 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505` (HEAD `c59551bc` at start). I read the
earlier findings first (through the 07:45 note at commit `c59551bc`) and continued
from them rather than starting over.

The scoped repair was already committed before this pass: `7a9e8352`
(responsive implementation), `5eccf8dc` (the `exactOptionalPropertyTypes` fix —
the tablet test omits the optional `ReleasedReport.result` key by rest
destructuring instead of assigning `undefined`, which the repo-root strict
`tsconfig.json` rejects as TS2375), and `70d4be33` / `c59551bc` (adjacent doc
comment corrections). The TS2375 error quoted in the escalation was the
*pre-fix* state; the current test at `ReleasedReportsScreen.tablet.test.tsx:236`
already uses the corrected form. No earlier fix needed to be undone.

I rehydrated the governing context (AGENTS.md, CODING_STYLE.md, README-SETUP.md,
docs/WORKFLOW.md, WORKSPACE.md, MEMORY.md, PROJECT.md, the story task file, the
aurevia-delivery and aurevia-senior-mobile skills, decision
`0005-patient-app-tablets.md`, both scoped files and `reports/types.ts`) and then
re-ran every scoped gate myself with fresh tool commands. All pass. This pass
changes **only this notes file** — no production, test, config, threshold,
exclusion, tracker, workflow, `scripts/` or `.opencode/` file was edited by me.
Evidence source: **tool** evidence (commands the primary agent ran and observed).
The prior senior-mobile advisory review is recorded in the 07:45 note; no new
code was added this pass, so no new specialist review was required. No simulated
user or human-participant evidence is claimed, and no named clinician or patient
review was available.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`
and decision `docs/decisions/0005-patient-app-tablets.md`).

Acceptance mapping (entry point → test), all green in this run:

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Reuse the window-width rule so reports reflow on rotation, split-screen and Slide Over; device type never decides layout. | `ReleasedReportsScreen.tablet.test.tsx` 9-case matrix over compact/regular/large × 1.0/1.5/2.0 × portrait/landscape, driving `Dimensions.get` through `useWindowDimensions`; shared `windowClassFor` unchanged. |
| PAT-02 | No persistent clinical cache, no new state/storage, no changed API call or download flow; revision, issuer, effective-date, correction and download labels unchanged. | Component reads no storage client; existing `reports-screen.test.tsx` integration test still green; 1127 tests green. |
| UX-01 | Capped/centred content on large; two panes (summary + result detail) on large; ≥44 pt pane/touch targets; safe-area insets; no truncation or font cap below 200%; unchanged accessibility-label order; RTL mirrors insets and row direction. | Tablet matrix, Arabic RTL case, no-result/no-provider case, and `expectReflowAtTwoHundredPercent`. |

Entry-point-to-test trace: exported `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The new test renders the exported screen directly, injects
`SafeAreaInsetsContext`, drives `Dimensions.get`, and asserts tree, labels,
insets and direction. The fix itself is test-only: runtime behavior and all
acceptance scenarios are unaffected.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus a
test-only type repair — no clinical decision, authorization, patient
association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo (`aurevia-senior-mobile`): its mandatory
definition of done was executed and met this pass (module `check.sh` green,
typecheck final lines and coverage summary recorded below, zero `error TS…`).
A prior bounded advisory review by that specialist confirmed the fix is correct
and complete with no actionable correctness, coverage, acceptance or
quality-bar finding. No new code was added this pass to re-review. Independent
QA and the full integration gate belong to the integrator/Delivery. No named
clinical or patient reviewer was available.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix;
no reserved migration version was used. Synthetic fixture only — no patient
data, identifier, credential or real domain name appears in the change or note.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event touched. The component
stays presentational: report data and download authorization still arrive from
the authenticated client; the withdrawn-release guard and server authorization
are unchanged.

## Migration and rollout considerations

None. Existing app users keep the same data flow; the responsive structure is a
pure render change.

## Test evidence

All commands executed by the primary agent (tool evidence) from the repo root
unless noted. Gates were run strictly sequentially after all installs: each
`check.sh`/`coverage-check.sh` runs its own `npm ci`, so running two in parallel
races on `apps/patient-mobile/node_modules` (see Findings 1).

- `git merge --no-edit origin/main` (start) → `Already up to date.`; HEAD
  `c59551bc`, merge-base `a8f0cdc` = `origin/main`. Working tree clean
  (`git status --short` → empty). The only `git grep -nE '^(<<<<<<<|>>>>>>>) '`
  hits are pre-existing, out-of-scope HL7 Java files
  (`apps/api/.../exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`,
  also on `origin/main`); no new conflict from the merge.
- Three dependency installs (fresh checkout): repo-root `npm ci` → `added 164
  packages`; `apps/patient-mobile` `npm ci` → `added 1408 packages`;
  `apps/staff-web` `npm ci` → `added 486 packages` (all exit 0).
- Root strict `npm run typecheck` — the `tsconfig.json` with
  `exactOptionalPropertyTypes: true` that originally surfaced TS2375 →
  **exit 0**, final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no output after the command — zero `error TS…`).
- `bash apps/patient-mobile/check.sh` → **exit 0**. Module typecheck final
  lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  Jest final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Ran all test suites.
  ```
- `./node_modules/.bin/eslint` on the two scope files alone (from
  `apps/patient-mobile`) → **exit 0**, zero warnings and zero errors.
- `bash scripts/coverage-check.sh` → **exit 0**, final line
  `coverage check: passed` (79 suites / 1127 tests green).
- `bash scripts/frontend-typecheck-check.sh` → **exit 0**, final line
  `frontend typecheck: passed (or no frontend files changed)`. Its lint pass
  emits 4 pre-existing warnings, all in out-of-scope files
  (`features/enrollment/screens.tablet.test.tsx`,
  `features/tracker/presentation.test.ts`); 0 errors.

No simulated user or human participant was involved. The worker re-runs of
typecheck and coverage gates therefore have clean tool evidence to act on.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (pinned `npm ci` → typecheck → lint →
`npm run test:coverage -- --ci` with the module's enforced 100% global threshold
over every production source file), corroborated by
`bash scripts/coverage-check.sh` (exit 0, `coverage check: passed`).

Result: `apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tsx`
**100 | 100 | 100 | 100** (statements/branches/functions/lines); the
`features/reports` directory **100 | 100 | 100 | 100**; `All files`
**100 | 100 | 100 | 100**; 1127 tests green. Test files are excluded from the
source scope by `jest.config.ts`; no threshold, exclusion,
`@Generated`/ignore comment or skip was added. This fix pass added no production
code, so no new production branch exists. The story's production branches
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
| … null insets | `keeps a single summary pane … and tolerates no provider` (provider value `null`) |
| `report.result === undefined` true / false | no-result case / matrix cases |
| `error !== null`, empty list, `withdrawn`, optional `title`, `correction === null`, `isRtl(report)` | `reports-screen.test.tsx` and the matrix |

## Clinical and architecture review

Not required for this fix: no signed data, cross-organization sharing, patient
association change, prescribing or critical result. Presentation-only; no domain
contract or state machine changed. A named VoiceOver/TalkBack pass on device
remains a separate launch gate and is not claimed here.

## Findings

1. **Parallel gates race on `npm ci` (environment, not a code defect).** I first
   ran the root typecheck in parallel with `apps/patient-mobile/check.sh` and
   `coverage-check.sh` with `frontend-typecheck-check.sh`. Each of those scripts
   runs its own `npm ci` under `apps/patient-mobile`, which wipes
   `node_modules` mid-run; the peer process then saw transient
   `TS2307`/`TS2875`/`TS7006` and `ERR_MODULE_NOT_FOUND eslint-config-expo`
   errors. Re-running each gate **sequentially** with no peer `npm ci` produced
   clean green for all four. This is the most likely cause of a worker reporting
   "local checks failed" on an already-correct branch.
2. **Scoped work is complete and green (not a defect).** The one-line test fix
   at `5eccf8dc` and the responsive component are complete. The root strict
   typecheck that produced TS2375, the module typecheck, the module check gate,
   the coverage gate and the frontend typecheck gate all pass on fresh tool runs
   in this pass. There is no in-scope code change left that can make the full
   gate pass.
3. **`check-pack.py` "opencode.json is stale" is a CI-profile artifact (out of
   scope, not fixed).** The worktree `opencode.json` is the CI profile copied
   under `skip-worktree`, so its hash differs from the committed HEAD blob;
   HEAD's committed blob equals `origin/main`'s. `opencode.json` and
   `.opencode/` are protected files a worker must not edit.
4. **"integration run failed without fresh, usable test results (compile or
   setup error)" is an integrator/tooling condition (out of scope, not fixed).**
   It is emitted by `scripts/worker_gate.py` when Maven exits nonzero and holds
   no parseable Surefire report. Prior debugger findings recorded full
   `mvn verify`/`apps/api/check.sh` exceeding the 20-minute cap locally while
   still applying migrations (exit 124) — a runner time limit, not a failing
   assertion; the narrowed `mvn -Pfast test` and PostgreSQL integration suites
   passed in those rounds.
5. **Intermittent `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}`
   integrator lines are a shared-tracker condition (out of scope).** TODO.md is
   owned by the orchestrator/integrator; a swarm worker must not edit it. My
   branch working tree is clean and its committed diff contains no tracker file.
6. **Pre-existing conflict markers, out of scope (flagged, not fixed).**
   `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds markers in three HL7 Java files
   (inside Javadoc comments, so compile/tests are unaffected). They exist on
   `origin/main` too and are unrelated to the patient-mobile reports screen.
7. **Full `scripts/check.sh` was deliberately not run.** It invokes
   `apps/api/check.sh` (full `mvn verify`, >10 min locally; prior rounds timed
   out at 20 minutes) and `db/check.sh`. Worker rules stop a command that passes
   5 minutes; the integrator and Delivery own the full gate and the 100% coverage
   check. All scoped frontend gates were run.

**Exact next step for the integrator/owner:** re-run
`bash apps/patient-mobile/check.sh`, root `npm run typecheck` (after all three
`npm ci` runs), `bash scripts/coverage-check.sh` and
`bash scripts/frontend-typecheck-check.sh` against this branch HEAD **one at a
time**; all four are green here. If the merge still fails, the cause is the
out-of-scope infrastructure in Findings 3–6, not the scoped source, and it needs
an owner/tooling fix rather than another worker pass on this story.

## Why this is not finished

The scoped story work is finished and green; the task remains unmerged because
the integrator's full gate repeatedly fails for out-of-scope infrastructure, not
for the patient-mobile diff. Concretely:

- The failing command recorded in the escalation is the integrator's full gate
  (`scripts/integrator-gate.sh` → `scripts/check.sh` → `apps/api/check.sh` full
  `mvn verify`), which locally exceeds the 20-minute cap while still applying
  migrations (exit 124) and therefore writes no fresh Surefire report — producing
  "integration run failed without fresh, usable test results (compile or setup
  error)". In CI this must be judged by the exact SHA's job logs, not a worker
  re-run.
- The intermittent `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}`
  comes from the integrator's own checkout having TODO.md dirty at validation
  time; this branch's working tree is clean and has no tracker file in scope.
- All four scoped gates pass with exact commands and final lines above. There is
  no in-scope code change left that can make the full gate pass.

Most likely cause: a runner time limit / environment artifact in the integrator,
not a code defect. What was tried: fresh installs of all three frontend modules,
root strict typecheck, module check, coverage check, frontend typecheck, targeted
scoped eslint — all green when run sequentially. Exact next step: an owner
inspects the red CI job's logs for the exact SHA, fixes the Java/gate tooling or
dirty-tracker condition (Findings 3–6) in a separate change, then re-runs the
integrator gate; no further worker pass on this story is productive.

## Lessons for workers

- Never run two frontend gates in parallel: `apps/patient-mobile/check.sh` and
  `scripts/coverage-check.sh` each run their own `npm ci` under
  `apps/patient-mobile` and wipe `node_modules` out from under a concurrent
  typecheck/lint. Run them one at a time, then re-run any gate that saw transient
  `TS2307`/`TS2875`/`ERR_MODULE_NOT_FOUND` before reporting a failure.
- Install module dependencies in **all** frontend modules and the root before
  running the **root** `npm run typecheck`: `npm ci` in `apps/patient-mobile`,
  `apps/staff-web` and the repo root. The root `tsconfig.json` is the only one
  with `exactOptionalPropertyTypes: true`; missing staff-web dependencies mask
  the real strict-optional error with dozens of `TS2307`/`TS2875` errors.
- `ReleasedReport.result` is optional but not `undefined`-writable: omit the key
  (rest destructuring) in fixtures instead of assigning `result: undefined`, or
  the root strict typecheck fails with TS2375 while the module-local typecheck
  stays green.
- In cloud workers, treat a `check-pack.py` "opencode.json is stale" result as a
  CI-profile artifact until the committed blob (`git show HEAD:opencode.json`)
  is proven different from `origin/main`; never "fix" it by editing the
  protected file.
- Do not run the full `mvn verify` / `scripts/check.sh` as a worker; it exceeds
  the 5-minute worker cap. Run only the tests for the code you touched and let
  the integrator/Delivery run the full gate.
- Confirm pre-existing conflict markers are on `origin/main` and outside the task
  `scope` before flagging them; do not resolve them in a scoped worker pass.
