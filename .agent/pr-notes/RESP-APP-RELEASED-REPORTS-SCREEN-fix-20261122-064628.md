# RESP-APP-RELEASED-REPORTS-SCREEN (fix / verification pass — 2026-11-22 06:46 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505` (HEAD `520e251a` at start). I read the
earlier findings first and continued from them rather than starting over.

The substantive repair from earlier rounds is already present in HEAD and I
re-proved it with fresh tool runs this pass:

- The original root strict-typecheck failure was
  `apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tablet.test.tsx(233,11):
  error TS2375` — `{ result: undefined }` is not assignable to `ReleasedReport`
  under `exactOptionalPropertyTypes: true`. `ReleasedReport.result` is declared
  `result?` (optional), not `result: T | undefined`, in
  `src/features/reports/types.ts`, so an explicit `undefined` is forbidden.
  HEAD omits the key via rest destructuring at line 236
  (`const { result: _discardedResult, ...withoutResult } = report;`). The
  production screen already treats an absent result exactly like an explicitly
  undefined one (`ReleasedReportsScreen.tsx:194`), so no runtime change was
  needed.
- **No production or test code change was made this pass.** The scoped story
  work is complete and every gate I am authorized to run is green (evidence
  below). The only remaining failure is an out-of-scope integrator/worker-gate
  environment defect (no fresh Surefire XML from the Java integration step) plus
  the documented cloud-worker `opencode.json` CI-profile artifact. Both are
  owned outside this frontend story. The issue's own policy requires a person to
  take over after three debugger/fix rounds without green; those rounds have
  elapsed, so I did not add another "all green" code round.

Evidence provenance: **tool** evidence (commands I executed and observed this
run) for every gate below; **reviewer** evidence is the earlier advisory
`smartclinic-senior-mobile` review carried forward. No simulated-user or
human-participant evidence was collected this pass and no clinical or patient
acceptance is claimed. No patient data, credential or raw log appears in this
note.

## Affected requirements

PAT-01, PAT-02, UX-01 per
`tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`,
`docs/decisions/0005-patient-app-tablets.md`,
`docs/design/accessibility-patterns.md` §9 and
`docs/design/rtl-and-localization.md` §1.

Acceptance mapping (entry point → test), all green this run:

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Reuse the window-width rule so reports reflow on rotation, split-screen and Slide Over; device type never decides layout. | `ReleasedReportsScreen.tablet.test.tsx` 9-case matrix over compact/regular/large × 1.0/1.5/2.0 × portrait/landscape, driving `Dimensions.get` through `useWindowDimensions`; shared `windowClassFor` unchanged. |
| PAT-02 | No persistent clinical cache, no new state/storage, no changed API call or download flow; revision, issuer, effective-date, correction and download labels unchanged. | Component reads no storage client; `reports-screen.test.tsx` integration test still green; 1127 tests green. |
| UX-01 | Capped/centred content on large; two panes (summary + result detail) on large; ≥44 pt pane/touch targets; safe-area insets; no truncation or font cap below 200%; unchanged accessibility-label order; RTL mirrors insets and row direction. | Tablet matrix, Arabic RTL case, no-result/no-provider case and `expectReflowAtTwoHundredPercent`. |

Entry-point-to-test trace: exported `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-{reportId}` →
`report-summary-{reportId}` / `report-detail-{reportId}`. The tablet test renders
the exported screen directly, injects `SafeAreaInsetsContext`, drives
`Dimensions.get`, and asserts the rendered tree, labels, insets and direction.
No runtime behavior changed in this pass.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus the
already-committed, test-only type repair — no clinical decision, authorization,
patient association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo (`smartclinic-senior-mobile`) definition of done met.
The advisory senior-mobile review recorded in earlier hand-off notes found no
material gap; its two non-blocking observations (no `rowGap` on wrapped panes;
RTL test uses `toContain` while exact order is asserted by the matrix) were
deliberately left unchanged to keep this repair minimal. No named clinician,
patient or human-participant review was available and none is claimed.

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
unless noted. HEAD remained `520e251a` throughout.

- `git merge --no-edit origin/main` → `Already up to date.` (run at start).
- `git grep -nE '^(<<<<<<<|>>>>>>>)' -- apps/patient-mobile` (excluding Markdown)
  → no output (no conflict markers in scope).
- Dependencies installed: `npm ci --no-audit --no-fund` in `apps/patient-mobile`
  (`added 1408 packages`), repo root (`added 164 packages`) and `apps/staff-web`
  (`added 486 packages`), all exit 0. Root and staff-web modules are required
  for the strict root typecheck.

- **`npm run typecheck` at the repo root** (the strict `tsconfig.json` with
  `exactOptionalPropertyTypes: true` that originally produced TS2375) →
  **exit 0**. Final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no output after the command — zero `error TS…`). This is the exact gate that
  failed at TS2375 in the original finding.

- **`bash apps/patient-mobile/check.sh`** → **exit 0** (pinned `npm ci`,
  shellcheck, typecheck, lint, Jest with the module's enforced 100% coverage
  gate). Typecheck final lines (mandatory to paste here):
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
  Time:        15.732 s
  Ran all test suites.
  ```

- `bash scripts/frontend-typecheck-check.sh` → **exit 0**:
  `frontend typecheck: passed (or no frontend files changed)`. ESLint reported 4
  pre-existing warnings (0 errors) in unrelated files
  (`enrollment/screens.tablet.test.tsx`, `tracker/presentation.test.ts`), none in
  a scoped file.

- `bash scripts/frontend-check.sh` → **exit 0**, final lines:
  ```
  frontend standards: policy and tool commands passed
  ```

- Targeted test (red-first evidence recorded by earlier workers; green re-run
  here), from `apps/patient-mobile`:
  `./node_modules/.bin/jest --ci --runInBand src/features/reports/ReleasedReportsScreen.tablet.test.tsx src/features/reports/reports-screen.test.tsx`
  → **exit 0**, `Test Suites: 2 passed, 2 total`, `Tests: 21 passed, 21 total`.

- `bash scripts/check.sh` → **exit 1**, last line:
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker CI-profile artifact, proven below and in Findings;
  the worktree file is byte-identical to `.github/opencode.ci.json`, the HEAD
  blob equals `origin/main`, and `.opencode/` has no branch diff. No Java/DB
  gate was run (the integrator owns the full integration tier).

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
| `report.result === undefined` true / false | no-result case (key omitted via rest) / matrix cases |
| `error !== null`, empty list, `withdrawn`, optional `title`, `correction === null`, `isRtl(report)` | `reports-screen.test.tsx` and the matrix |

## Clinical and architecture review

Not required for this change: no signed data, cross-organization sharing,
patient association change, prescribing or critical result. Presentation-only;
no domain contract or state machine changed. A named VoiceOver/TalkBack pass on
device remains a separate launch gate and is not claimed here.

## Findings

1. **Scoped repair confirmed present and green (not a defect).** The key-omission
   fix at `ReleasedReportsScreen.tablet.test.tsx:236`, the responsive component
   and the tablet test all pass fresh tool runs. The root strict typecheck that
   produced TS2375, the module typecheck, both frontend gates and the full
   patient-mobile coverage gate are green. Nothing in this scope needs undoing
   or further change.
2. **The recurring integrator failure is out of scope and already has an owning
   task (escalation).** The repeated
   `integration run failed without fresh, usable test results (compile or setup error)`
   comes from `scripts/worker_gate.py` when the Java integration step produces
   no fresh parseable Surefire XML. This branch changes no Java, SQL, contract,
   migration or threshold, so no frontend change can affect it. The fix for the
   underlying worker-gate tooling is already tracked and done as
   `FIX-WORKER-REPORTS` (TODO.md line 186, PR #680, scope
   `scripts/worker-check.sh`, `scripts/worker_gate.py`, `scripts/tests`). Next
   step for the integrator: re-run the integrator gate on the current merge
   result with that repaired tooling and ensure the Java integration step emits
   fresh parseable XML. Do NOT send this back to another frontend fix worker —
   no change to `ReleasedReportsScreen.tsx` or its test will help.
3. **`check.sh` `opencode.json is stale` is a CI-profile artifact, not a
   regression (out of scope, not fixed).** Measured this run: worktree
   `opencode.json` sha256 `50005c9c…` equals `.github/opencode.ci.json`
   (`cmp` identical), while `HEAD:opencode.json` sha256 `0dc2ac8e…` differs;
   `git ls-files -v opencode.json` → `S` (skip-worktree). `HEAD:opencode.json`
   blob equals `origin/main` (`8267e421`) and `.opencode/` has no branch diff.
   `opencode.json` and `.opencode/` are protected and were not touched.
4. **PR file-count ceiling approaching (risk).** 26 files vs `origin/main`
   before this notes file, 27 with it, versus `MAX_PR_FILES = 30` in
   `scripts/swarm.py`. Avoid further notes-only rounds; a large PR is labelled
   `needs-human` and cannot merge.
5. **Pre-existing conflict markers in three Java HL7 files (out of scope,
   flagged, not fixed).** Markers exist inside comments in
   `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
   and on `origin/main` too; compile/tests are unaffected and they are unrelated
   to this story.
6. **Non-failing React `act()` warning** from `src/app/PatientResource.tsx:54`
   appeared in earlier runs; tests still pass. Unrelated to this scope.
7. No requirement-ID-named folder, new migration, new dependency or scope
   expansion was introduced.

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
  locations, and re-ran the root strict typecheck, the module typecheck and
  frontend gates, the full patient-mobile coverage gate, the targeted report
  tests and `scripts/check.sh`. No scoped code change was needed or made.
- **Exact next step:** a person or the integrator must make the Java integration
  step emit fresh parseable Surefire XML on the merge result (using the already
  repaired `FIX-WORKER-REPORTS` gate) and rerun the integrator gate. The issue's
  policy requires a human takeover after three debugger/fix rounds without
  green; those rounds have elapsed. No further code change to
  `ReleasedReportsScreen.tsx` or its test will help, and adding another
  notes-only round risks the `MAX_PR_FILES` ceiling.

## Lessons for workers

- Install module dependencies in **all** frontend modules (`npm ci` in
  `apps/staff-web` and `apps/patient-mobile`) and at the repo root before the
  root `npm run typecheck`; without staff-web modules, unrelated TS2307 errors
  mask the real strict-optional result.
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
