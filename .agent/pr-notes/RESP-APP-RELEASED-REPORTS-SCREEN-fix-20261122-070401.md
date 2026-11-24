# RESP-APP-RELEASED-REPORTS-SCREEN (fix / verification pass — 2026-11-22 07:04 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505` (HEAD `72e4f3ad` at start). I read the
earlier findings first and continued from them instead of starting over. The last
hand-off (`...-065506.md`) recorded that the scoped repair was already present and
green and that the only remaining failures belong to the integrator tier and to
the documented cloud-worker config artifact. I re-proved that with fresh tool
runs this pass.

The original defect was
`apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tablet.test.tsx(233,11):
error TS2375`: assigning `{ result: undefined }` to a `ReleasedReport` is invalid
under `exactOptionalPropertyTypes: true` because `result` is optional (`result?`)
in `src/features/reports/types.ts`, not `result: T | undefined`. HEAD line 236
now omits the key through rest destructuring
(`const { result: _discardedResult, ...withoutResult } = report;`), which is the
correct, minimal repair; the production screen already treats an absent result
exactly like an explicit `undefined` (`ReleasedReportsScreen.tsx`, `report.result
=== undefined ? null : ...`). No further code change is needed or made this pass.

One measurement trap was found and ruled out: running `npm run typecheck` (root)
in parallel with `bash apps/patient-mobile/check.sh` produced a flood of transient
`TS2307 Cannot find module 'react-native' / '@jest/globals' / 'react/jsx-runtime'`
errors, because check.sh runs its own pinned `npm ci` and briefly removes and
reinstalls `apps/patient-mobile/node_modules` while tsc is reading it. Run
sequentially (stable dependencies), the root strict typecheck is clean with zero
errors. This is an ordering artifact of my first run, not a defect in the branch.

Evidence provenance: **tool** evidence for every command below (I executed and
observed each run). **Reviewer** evidence is a bounded advisory
`orevia-senior-mobile` review of the scoped diff this pass (report appended
under Findings when received); no named clinician, patient or human-participant
evidence was collected and none is claimed. No patient data, credential or raw
log appears in this note.

## Affected requirements

PAT-01, PAT-02, UX-01 per
`tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`,
`docs/decisions/0005-patient-app-tablets.md`,
`docs/design/accessibility-patterns.md` §9 and
`docs/design/rtl-and-localization.md` §1.

Acceptance mapping (entry point → test), all green this run:

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Reuse the window-width rule so reports reflow on rotation, split-screen and Slide Over; device type never decides layout. | `ReleasedReportsScreen.tablet.test.tsx` 9-case matrix over compact/regular/large × 1.0/1.5/2.0, driving `Dimensions.get` through `useWindowDimensions`; shared `windowClassFor` unchanged. |
| PAT-02 | No persistent clinical cache, no new state/storage, no changed API call or download flow; revision, issuer, effective-date, correction and download labels unchanged. | Component reads no storage client; `reports-screen.test.tsx` integration test stays green; 1127 tests green. |
| UX-01 | Capped/centred content on large; two panes (summary + result detail) on large; >=44 pt pane/touch targets; safe-area insets; no truncation or font cap below 200%; unchanged accessibility-label order; RTL mirrors insets and row direction. | Tablet matrix, Arabic RTL case, no-result/no-provider case and `expectReflowAtTwoHundredPercent`. |

Entry-point-to-test trace: exported `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-{reportId}` →
`report-summary-{reportId}` / `report-detail-{reportId}`. The tablet test renders
the exported screen directly, injects `SafeAreaInsetsContext`, drives
`Dimensions.get`, and asserts the rendered tree, labels, insets and direction.
No runtime behaviour changed in this pass.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus the
already-committed, test-only type repair — no clinical decision, authorization,
patient association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo (`orevia-senior-mobile`) definition of done is
met: `bash apps/patient-mobile/check.sh` passes with typecheck, lint and 100%
coverage. A bounded independent advisory review of the scoped diff was requested
this pass (tool/subagent evidence); its verdict was "correct and complete, no
blocking defect, standards pass" and its four low/advisory findings are recorded
under Findings. No named clinician, patient or human-participant review was
available and none is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix;
no reserved migration version used. Synthetic fixture only.

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
unless noted. HEAD remained `72e4f3ad` throughout; the working tree was clean at
start and only this notes file is added by this pass.

- `git merge --no-edit origin/main` → `Already up to date.` (at start); no
  conflict markers in scope
  (`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/patient-mobile/src/features/reports`
  → no output).
- Dependencies installed sequentially: `npm ci` at the repo root (`added 164
  packages`), `npm --prefix apps/staff-web ci` (`added 486 packages`) and
  `npm --prefix apps/patient-mobile ci` (`added 1408 packages`), all exit 0.
  Root and staff-web modules are required for the strict root typecheck.

- **`bash apps/patient-mobile/check.sh`** → **exit 0** (pinned `npm ci`,
  shellcheck, typecheck, lint, Jest with the module's enforced 100% coverage
  gate). Mandatory typecheck final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  (no output after the command — zero `error TS…`). Coverage/test final lines:
  ```
  All files          |     100 |      100 |     100 |     100 |
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        12.094 s
  Ran all test suites.
  ```

- **`npm run typecheck` at the repo root** (the strict `tsconfig.json` with
  `exactOptionalPropertyTypes: true` that originally produced TS2375) →
  **exit 0**, run after check.sh with stable dependencies. Final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no output after the command — zero `error TS…`, no TS2375). This is the exact
  gate that failed at TS2375 in the original finding.

- `bash scripts/frontend-typecheck-check.sh` → **exit 0**, final line
  `frontend typecheck: passed (or no frontend files changed)`. ESLint reported
  4 pre-existing warnings (0 errors) in unrelated tracker files, none in a
  scoped file.

- `bash scripts/frontend-check.sh` → **exit 0**, final lines:
  ```
  frontend standards: policy and tool commands passed
  ```

- `bash scripts/check.sh` → **exit 1**, final line:
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Proven to be
  the documented cloud-worker CI-profile artifact, not a regression: worktree
  `opencode.json` sha256 `50005c9cb97d2466…` equals `.github/opencode.ci.json`,
  while `git show HEAD:opencode.json` sha256 `0dc2ac8ee5b53802…` is the local
  profile; `git ls-files -v opencode.json` → `S` (skip-worktree). `opencode.json`
  and `.opencode/` were not touched. No Java/DB gate was run (the integrator owns
  the full integration tier).

## Coverage

Command: `bash apps/patient-mobile/check.sh` (pinned `npm ci` → typecheck →
lint → `npm run test:coverage -- --ci` with the module's enforced 100% global
threshold over every production source file).

Result: `All files 100 | 100 | 100 | 100`;
`features/reports 100 | 100 | 100 | 100`;
`ReleasedReportsScreen.tsx 100 | 100 | 100 | 100` (statements/branches/
functions/lines); 79 suites and 1127 tests green. Type-only `types.ts` files
report `0/0/0/0` because they contain no executable statements. Test files are
excluded from the source scope by `jest.config.ts`; no threshold, exclusion,
`@Generated`/ignore comment, `.skip` or `@Disabled` was added.

This pass added no production line, branch or test, so there is no new branch to
name. The story's branches in the touched files remain covered and reconfirmed
at 100%:

| Branch in `ReleasedReportsScreen.tsx` | Test that takes it |
|---|---|
| `windowClass === 'large'` (maxWidth 960, row panes, `flex: 1`) | tablet matrix `large / 1`, `large / 1.5`, `large / 2`; Arabic RTL case; no-result case |
| `windowClass !== 'large'` (no maxWidth, column panes, natural height) | tablet matrix `compact` and `regular` cases |
| `locale === 'ar'` (mirrored insets) | `mirrors the panes, safe-area insets and row direction under Arabic RTL` |
| `locale !== 'ar'` (LTR insets) | all 9 matrix cases |
| `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context defined | tablet test provider + module mock |
| … context undefined | `reports-screen.test.tsx` via global Jest setup mock |
| `useContext(...) ?? NO_INSETS` — non-null | matrix + RTL cases |
| … null insets | `keeps a single summary pane … and tolerates no provider` |
| `report.result === undefined` true / false | no-result case (key omitted via rest at line 236) / matrix cases |
| `error !== null`, empty list, `withdrawn`, optional `title`, `correction === null`, `isRtl(report)` | `reports-screen.test.tsx` and the matrix |

## Clinical and architecture review

Not required for this change: no signed data, cross-organization sharing,
patient association change, prescribing or critical result. Presentation-only;
no domain contract or state machine changed. A named VoiceOver/TalkBack pass on
device remains a separate launch gate and is not claimed here.

## Findings

1. **Scoped repair present and green (not a defect).** The key-omission fix at
   `ReleasedReportsScreen.tablet.test.tsx:236`, the responsive component and the
   tablet test all pass fresh tool runs. The root strict typecheck that produced
   TS2375, the module typecheck, both frontend gates and the full patient-mobile
   coverage gate are green. Nothing in this scope needs undoing or further
   change.
2. **Transient root-typecheck module errors were a run-ordering artifact
   (ruled out).** `npm run typecheck` run in parallel with check.sh's own
   pinned `npm ci` reads `apps/patient-mobile/node_modules` while it is being
   reinstalled, yielding spurious `TS2307`/`TS2875` errors. Sequential runs are
   clean. Run these gates one at a time.
3. **The recurring integrator failure is out of scope and already has an owning
   task (escalation).** The repeated
   `integration run failed without fresh, usable test results (compile or setup error)`
   comes from `scripts/worker_gate.py` when the Java integration step produces
   no fresh parseable Surefire XML. This branch changes no Java, SQL, contract,
   migration or threshold, so no frontend change can affect it. The fix for the
   underlying worker-gate tooling is already tracked as `FIX-WORKER-REPORTS`
   (scope `scripts/worker-check.sh`, `scripts/worker_gate.py`, `scripts/tests`).
   Next step for the integrator: re-run the integrator gate on the current merge
   result with that repaired tooling and ensure the Java integration step emits
   fresh parseable XML. Do NOT send this back to another frontend fix worker —
   no change to `ReleasedReportsScreen.tsx` or its test will help.
4. **`check.sh` `opencode.json is stale` is a CI-profile artifact, not a
   regression (out of scope, not fixed).** Re-measured this run (hashes in Test
   evidence); `opencode.json` and `.opencode/` are protected and were not touched.
5. **PR file-count ceiling is close (risk).** Adding this notes file makes 29
   files versus `origin/main` (28 before), against `MAX_PR_FILES = 30` in
   `scripts/swarm.py`. Avoid further notes-only rounds; a large PR is labelled
   `needs-human` and cannot merge.
6. **Pre-existing conflict markers in three Java HL7 files (out of scope,
   flagged, not fixed).** Markers exist inside comments in
   `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
   and on `origin/main` too; compile/tests are unaffected and they are unrelated
   to this story.
7. No requirement-ID-named folder, new migration, new dependency or scope
   expansion was introduced. No new task is requested: the out-of-scope tooling
   defect already has an owning task, so no `task-requests` block is needed.
8. **Bounded senior-mobile advisory review (tool: subagent; report only, no
   edits).** Verdict: the scoped implementation is correct and complete for the
   acceptance criteria, frontend standards pass, no blocking defect. Four
   low/advisory findings were returned and decided as follows:
   - A (claimed unreachable `NO_INSETS_CONTEXT` fallback) — **rejected after
     verification**. The global `jest.setup.ts` mock resolves to the library's
     `jest/mock`, which exports only `default`; the named
     `SafeAreaInsetsContext` import is therefore `undefined` in
     `reports-screen.test.tsx`, so `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT`
     legitimately takes the fallback there. The tablet test's module mock does
     provide the named context, so both operands are exercised. No deletion.
     Evidence: `jest.setup.ts:20-22`, `react-native-safe-area-context/lib/commonjs/jest/mock.js`
     (`exports.default` only), tablet test L42-45.
   - B (no-provider test lacks inset-value assertion) — advisory; branch is
     already executed and covered, so no change needed for 100% coverage.
   - C (RTL case uses `toContain` not the full ordered label array) — advisory;
     JSX order is locale-independent and the full order is asserted in the
     matrix case. No change.
   - D (two panes retained at `fontScale` 2 on large) — advisory; UX-01 asks for
     two panes on large unconditionally and the reflow test proves no clipping
     at 200%. Confirm with the acceptance owner before changing; no change here.

## Why this is not finished

The **scoped story work is finished and every gate I am authorized to run is
green**, but the pull request is not yet mergeable, so I am handing back rather
than claiming the PR is done:

- **What still fails:** the integrator's repeated
  `integration run failed without fresh, usable test results (compile or setup error)`
  and, in cloud workers, the `check.sh` CI-profile `opencode.json is stale`
  artifact. Neither is a regression from this branch; both are owned outside
  this frontend story.
- **Most likely cause:** the Java integration step produces no fresh parseable
  Surefire XML when `scripts/worker_gate.py` checks its report directory; the
  cloud worker installs the CI `opencode.json` under `skip-worktree` while
  `scripts/check.sh` judges the working file. Java sources are not changed here.
- **What I tried:** rehydrated the context, merged `origin/main`, confirmed no
  conflict markers in scope, installed dependencies in all three frontend
  locations, and re-ran the root strict typecheck, the module typecheck and both
  frontend gates, the full patient-mobile coverage gate and `scripts/check.sh`.
  No scoped code change was needed or made.
- **Exact next step:** a person or the integrator must make the Java integration
  step emit fresh parseable Surefire XML on the merge result (using the already
  repaired `FIX-WORKER-REPORTS` gate) and rerun the integrator gate. The issue's
  policy requires a human takeover after three debugger/fix rounds without
  green; those rounds have long elapsed (28 hand-offs). No further code change to
  `ReleasedReportsScreen.tsx` or its test will help, and adding another
  notes-only round risks the `MAX_PR_FILES` ceiling.

## Lessons for workers

- Install module dependencies in **all** frontend modules (`npm ci` in
  `apps/staff-web` and `apps/patient-mobile`) and at the repo root before the
  root `npm run typecheck`; without staff-web modules, unrelated TS2307 errors
  mask the real strict-optional result.
- Never run `npm run typecheck` (root) in parallel with
  `bash apps/patient-mobile/check.sh`: check.sh runs its own pinned `npm ci`,
  which temporarily removes `apps/patient-mobile/node_modules` and produces
  spurious `TS2307`/`TS2875` errors in tsc. Run them sequentially.
- `ReleasedReport.result` is optional but not `undefined`-writable: omit the key
  with rest destructuring in fixtures instead of assigning `result: undefined`,
  or the root strict typecheck fails with TS2375 while the module-local typecheck
  stays green.
- In cloud workers, prove an `opencode.json is stale` result is the CI-profile
  `skip-worktree` artifact by comparing `sha256sum opencode.json` with
  `.github/opencode.ci.json` and `git show HEAD:opencode.json`; never edit the
  protected file.
- When fresh scoped gates are green but the integrator is still red, do not
  re-apply code fixes or add another "all green" notes round. Name the exact
  failing gate and owning file, confirm it is outside this story's scope, check
  whether an owning task already exists, and escalate with a concrete next
  step. A Java `compile or setup error` from `worker_gate.py` is not repairable
  by a frontend change.
- Each fix pass adds a notes file; check `MAX_PR_FILES` before adding another,
  because a large PR is labelled `needs-human` and cannot auto-merge.
