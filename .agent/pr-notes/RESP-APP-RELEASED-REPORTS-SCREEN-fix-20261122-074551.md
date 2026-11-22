# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — 2026-11-22 07:45 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505` (HEAD `d41c8420` at start). I read the
earlier findings first (through the 07:37 note at commit `d41c8420`) and continued
from them rather than starting over.

The scoped repair is already committed on this branch before this pass:
`7a9e8352` (responsive implementation), `5eccf8dc` (strict optional-property test
fix) and `70d4be33` (adjacent doc-comment correction). At
`ReleasedReportsScreen.tablet.test.tsx:236` the optional `ReleasedReport.result`
key is **omitted** via rest destructuring
(`const { result: _discardedResult, ...withoutResult } = report;`) instead of being
assigned `undefined`, which the repo-root strict `tsconfig.json` rejects as TS2375
under `exactOptionalPropertyTypes: true`. No earlier fix needed to be undone.

I rehydrated the governing context (AGENTS.md, CODING_STYLE.md, README-SETUP.md,
docs/WORKFLOW.md, WORKSPACE.md, MEMORY.md, PROJECT.md, the story task file, the
smartclinic-delivery and smartclinic-senior-mobile skills, decision
`0005-patient-app-tablets.md`, both scoped files and `reports/types.ts`) and then
re-ran every scoped gate myself with fresh tool commands. All pass.

An independent bounded advisory review by the `smartclinic-senior-mobile`
specialist confirmed the TS2375 fix is correct and complete with no actionable
correctness, coverage, acceptance or quality-bar findings. Its one low-severity
nit — the component doc comment overstated that *every* window class gets a
capped content column when `maxWidth` is applied only on `large` — I fixed in the
scoped file so the comment stays true (AGENTS.md). **This pass changes only this
notes file and that one comment line in `ReleasedReportsScreen.tsx`** — no
behavior, test logic, config, threshold, exclusion, tracker, workflow, `scripts/`
or `.opencode/` file was edited by me. The comment-only edit cannot change test
or coverage results; all gates were re-run after it and remain green.

Evidence source: **tool** evidence (commands the primary agent ran and observed)
plus a **reviewer** finding from the senior-mobile specialist (documentation nit,
addressed). No simulated-user or human-participant test evidence is claimed. No
named clinician or patient review was available and none is claimed.

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
`SafeAreaInsetsContext`, drives `Dimensions.get`, and asserts tree, labels, insets
and direction. The fix itself is test-only: runtime behavior and all acceptance
scenarios are unaffected.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus a
test-only type repair — no clinical decision, authorization, patient
association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo (`smartclinic-senior-mobile`): its mandatory
definition of done was executed and met (module `check.sh` green, typecheck lines
and coverage summary recorded below, zero `error TS…`). An independent bounded
advisory review by that specialist confirmed the TS2375 fix is correct and
complete (breakpoints 390/700/1024 match `windowClassFor`, `MAX_CONTENT_WIDTH`
960 and `columnGap` arithmetic match the assertions, RTL inset swap is asserted
with asymmetric insets, no coverage gap, no skips or threshold changes) and
returned no actionable correctness, coverage, acceptance or quality-bar finding.
Its single low-severity documentation nit (the "capped content column" wording)
was fixed in this pass. Independent QA and the full integration gate belong to
the integrator/Delivery. No named clinical or patient reviewer was available.

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
unless noted.

- `git merge --no-edit origin/main` (start) → `Already up to date.`; no conflict
  markers in scope (`git grep` for conflict-marker lines under
  `apps/patient-mobile/**` → no output; full expression recorded in Findings).
  Working tree clean (`git status --porcelain` → empty); no tracker file in the
  committed diff against `origin/main`.
- Three dependency installs in parallel (fresh checkout): repo-root `npm ci`
  → `added 164 packages`, `apps/patient-mobile` `npm ci` → `added 1408 packages`,
  `apps/staff-web` `npm ci` → `added 486 packages` (all exit 0).
- Root strict `npm run typecheck` — the `tsconfig.json` with
  `exactOptionalPropertyTypes: true` that originally surfaced TS2375 → **exit 0**,
  final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no output after the command — zero `error TS…`). This gate needs all three
  `npm ci` runs; with staff-web dependencies missing it reports unrelated
  `TS2307`/`TS2875` missing-module errors.
- `bash apps/patient-mobile/check.sh` → **exit 0**. It runs `npm ci`,
  `npm run typecheck` and `npm run lint` cleanly, then Jest. Typecheck final
  lines (module):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  Jest final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        13.895 s
  Ran all test suites.
  ```
- `bash scripts/coverage-check.sh` → **exit 0**, final line
  `coverage check: passed` (79 suites / 1127 tests green).
- `bash scripts/frontend-typecheck-check.sh` → **exit 0**, final line
  `frontend typecheck: passed (or no frontend files changed)`. Its lint pass emits
  4 pre-existing warnings, all in out-of-scope files
  (`features/enrollment/screens.tablet.test.tsx`, `features/tracker/presentation.test.ts`);
  0 errors. `./node_modules/.bin/eslint` on the two scope files alone → exit 0,
  zero warnings and zero errors.
- Targeted determinism check:
  `./node_modules/.bin/jest --ci --runInBand ReleasedReportsScreen.tablet.test.tsx reports-screen.test.tsx`
  → **exit 0**, `Test Suites: 2 passed, 2 total`, `Tests: 21 passed, 21 total`.

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

1. **Scoped work is complete and green (not a defect).** The one-line test fix
   at `5eccf8dc` (doc-comment correction at `70d4be33`) and the responsive
   component are complete. The root strict typecheck that produced TS2375, the
   module typecheck, the module check gate, the coverage gate and the frontend
   typecheck gate all pass on fresh tool runs in this pass. The only source edit
   in this pass was the one-line doc-comment correction flagged by the senior
   mobile reviewer; it is comment-only and cannot affect behavior or coverage.
   No functional source change was needed.
2. **Repeated "local checks: failed" pushes did not come from the scoped source.**
   The branch history shows ~30 notes-only commits after `5eccf8dc`; the only
   source commits are `7a9e8352`, `5eccf8dc` and `70d4be33`. Fresh tool runs show
   no scoped failure, so the persistent escalation is not explained by the
   patient-mobile diff.
3. **Root strict typecheck depends on all three `npm ci` runs (environment, not a
   code defect).** With only root + patient-mobile dependencies installed, the
   root `npm run typecheck` fails with `TS2307`/`TS2875` missing-module errors
   across `apps/staff-web`. After `cd apps/staff-web && npm ci` it exits 0. This
   is the most likely cause of a worker's "local checks failed" when it ran the
   root strict typecheck in a fresh checkout.
4. **`check-pack.py` "opencode.json is stale" is a CI-profile artifact, not a
   branch regression (out of scope, not fixed).** The worktree `opencode.json` is
   the CI profile copied under `skip-worktree`, so its hash differs from the
   committed HEAD blob; HEAD's committed blob equals `origin/main`'s.
   `opencode.json` and `.opencode/` are protected files a worker must not edit.
5. **"integration run failed without fresh, usable test results (compile or setup
   error)" is an integrator/tooling condition (out of scope, not fixed).** It is
   emitted by `scripts/worker_gate.py:261-263` when Maven exits nonzero and the
   private Surefire report directory holds no parseable `TEST-*.xml`. Prior
   debugger findings recorded full `mvn verify`/`apps/api/check.sh` exceeding the
   20-minute cap locally while still applying migrations (exit 124) — a runner
   time limit, not a failing assertion. The narrowed `mvn -Pfast test` and
   PostgreSQL integration suites passed in those rounds.
6. **Intermittent `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}`
   integrator lines are a shared-tracker condition (out of scope).** TODO.md is
   owned by the orchestrator/integrator; a swarm worker must not edit it. My
   branch's working tree is clean and its committed diff contains no tracker
   file, so `scripts/worker_gate.py`'s `validate_candidate` sees no tracker
   violation from this branch. The same escalation text also shows
   `{"violations": []}` runs, so it is not deterministic and is unrelated to the
   patient-mobile diff.
7. **Pre-existing conflict markers, out of scope (flagged, not fixed).**
   `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds markers in
   `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
   (inside Javadoc comments, so Java compile/tests are unaffected; verified in
   `Hl7ParseException.java`). They exist on `origin/main` too, are unrelated to
   the patient-mobile reports screen, and `git merge --no-edit origin/main`
   introduced no new conflict. Resolving them belongs to an owner and a separate
   task.
8. **Full `scripts/check.sh` was deliberately not run.** It invokes
   `apps/api/check.sh` (full `mvn verify`, >10 min locally; prior rounds timed
   out at 20 minutes) and `db/check.sh`. Worker rules stop a command that passes
   5 minutes; the integrator and Delivery own the full gate and the 100% coverage
   check. All scoped frontend gates were run.

**Exact next step for the integrator/owner:** re-run
`bash apps/patient-mobile/check.sh`, root `npm run typecheck` (after all three
`npm ci` runs), `bash scripts/coverage-check.sh` and
`bash scripts/frontend-typecheck-check.sh` against this branch HEAD; all four are
green here. If the merge still fails, the cause is the out-of-scope infrastructure
in Findings 3–7, not the scoped source, and it needs an owner/tooling fix rather
than another worker pass on this story.

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
Jest and scoped eslint — all green. Exact next step: an owner inspects the red
CI job's logs for the exact SHA, fixes the Java/gate tooling or dirty-tracker
condition (Findings 4–7) in a separate change, then re-runs the integrator gate;
no further worker pass on this story is productive.

## Lessons for workers

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
- When the issue says a fix worker's "local checks failed" but fresh scoped tool
  runs are green, re-run the gates yourself and record the exact commands and
  final lines instead of re-applying or guessing at a new code change. A fresh
  checkout needs all three `npm ci` runs before the root typecheck.
- Do not run the full `mvn verify` / `scripts/check.sh` as a worker; it exceeds
  the 5-minute worker cap. Run only the tests for the code you touched and let
  the integrator/Delivery run the full gate.
- Confirm pre-existing conflict markers are on `origin/main` and outside the task
  `scope` before flagging them; do not resolve them in a scoped worker pass.
