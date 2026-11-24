# RESP-APP-RELEASED-REPORTS-SCREEN (fix/verification pass — 2026-11-22 06:29 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505` (HEAD `70d4be33` at start).
I read the earlier findings first and continued from them rather than starting
over. The substantive repair from earlier rounds is already present in HEAD and
I re-proved it with fresh tool runs this pass:

- `ReleasedReportsScreen.tablet.test.tsx:236` omits the optional
  `ReleasedReport.result` key via rest destructuring
  (`const { result: _discardedResult, ...withoutResult } = report;`). This is what
  the ROOT strict `tsconfig.json` (`exactOptionalPropertyTypes: true`) requires;
  the old `{ ...report, result: undefined }` produced TS2375. The production
  component already treats an absent result like an explicitly undefined one at
  `ReleasedReportsScreen.tsx:194`, so no runtime change was needed.
- The previous commit (`70d4be33`) attached an orphaned doc comment to the
  `renderScreen` function instead of the `RenderedView` type; that is in HEAD.

**I made no production or test code change this pass.** The scoped story work is
complete and every gate I am authorized to run is green (evidence below). The
only remaining failure is an out-of-scope integrator/tooling defect (Java
integration Surefire reports) plus the documented cloud-worker `opencode.json`
CI-profile artifact. Both are recorded under Findings and escalated; a person
must take over the integrator defect because the earlier three debugger/fix
rounds have elapsed. I did not add another "all green" code round.

Evidence source: **tool** evidence (commands I executed and observed this run).
I also carried forward the earlier advisory `orevia-senior-mobile` review as
reviewer evidence. No new reviewer, simulated-user or human-participant evidence
was collected; no clinical or patient acceptance is claimed.

## Affected requirements

PAT-01, PAT-02, UX-01 per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`,
`docs/decisions/0005-patient-app-tablets.md`,
`docs/design/accessibility-patterns.md` §9 and
`docs/design/rtl-and-localization.md` §1.

Acceptance mapping (entry point → test), all green this run:

| ID | Acceptance | Evidence |
|---|---|---|
| PAT-01 | Reuse the window-width rule so reports reflow on rotation, split-screen and Slide Over; device type never decides layout. | `ReleasedReportsScreen.tablet.test.tsx` 9-case matrix over compact/regular/large × 1.0/1.5/2.0 × portrait/landscape, driving `Dimensions.get` through `useWindowDimensions`; shared `windowClassFor` unchanged. |
| PAT-02 | No persistent clinical cache, no new state/storage, no changed API call or download flow; revision, issuer, effective-date, correction and download labels unchanged. | Component reads no storage client; existing `reports-screen.test.tsx` integration test still green; 1127 tests green. |
| UX-01 | Capped/centred content on large; two panes (summary + result detail) on large; ≥44 pt pane/touch targets; safe-area insets; no truncation or font cap below 200%; unchanged accessibility-label order; RTL mirrors insets and row direction. | Tablet matrix, Arabic RTL case, no-result/no-provider case and `expectReflowAtTwoHundredPercent`. |

Entry-point-to-test trace: exported `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-{reportId}` → `report-summary-{reportId}` /
`report-detail-{reportId}`. The new test renders the exported screen directly,
injects `SafeAreaInsetsContext`, drives `Dimensions.get`, and asserts the
rendered tree, labels, insets and direction. No runtime behavior changed in this
pass.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus the
already-committed test-only type repair — no clinical decision, authorization,
patient association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo (`orevia-senior-mobile`) definition of done
met. The advisory senior-mobile review recorded in earlier hand-off notes found
no material gap; its two non-blocking observations (no `rowGap` on wrapped
panes; RTL test uses `toContain` while exact order is asserted by the matrix)
were deliberately left unchanged to keep this repair minimal. No named
clinician, patient or human-participant review was available and none is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix;
no reserved migration version used. Synthetic fixture only — no patient data,
real identifier, credential or raw log appears in the change or this note.

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
unless noted. `git merge --no-edit origin/main` → `Already up to date.` (run at
start); a `git grep` for conflict-marker lines under `apps/patient-mobile/**` →
no output (no conflict markers in scope). HEAD remained `70d4be33` throughout.

- `bash apps/patient-mobile/check.sh` → **exit 0** (pinned `npm ci`, shellcheck,
  typecheck, lint, Jest with the module's enforced 100% coverage gate).
  Final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        14.226 s
  Ran all test suites.
  ```
  Patient-mobile typecheck final lines from the gate (required by the prompt):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  (no output after the command — zero `error TS…`).
- `npm run typecheck` at the **repo root** (the strict `tsconfig.json` with
  `exactOptionalPropertyTypes: true` that originally produced TS2375) → **exit 0**.
  Final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  Root `npm ci` and `apps/staff-web/npm ci` were run first; without the
  staff-web modules the root typecheck reports unrelated TS2307 errors.
- `bash scripts/frontend-typecheck-check.sh` → **exit 0**:
  `frontend typecheck: passed (or no frontend files changed)`. The patient-mobile
  module typecheck exited 0; ESLint reported 4 pre-existing warnings
  (0 errors) in unrelated files (`enrollment/screens.tablet.test.tsx`,
  `tracker/presentation.test.ts`), none in a scoped file.
- `python3 -I scripts/check-docs.py` → **exit 0** and
  `python3 -I scripts/check-secrets.py` → **exit 0** (required because this pass
  edited a Markdown notes file). No docs link, requirement-ID, secret or
  PHI-shaped string violation.
- `bash scripts/check.sh` → **exit 1**, single line:
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker CI-profile artifact, not a branch regression:
  `git rev-parse HEAD:opencode.json` and `git rev-parse origin/main:opencode.json`
  are byte-identical (`8267e421…`), `git diff --name-only origin/main...HEAD -- .opencode/`
  is empty, `git status --short opencode.json` is clean, and
  `git ls-files -v opencode.json` shows `S` (skip-worktree) with a working-file
  hash `2e3e6111…` — the CI profile installed over the committed local profile.
  `opencode.json` and `.opencode/` are protected and were NOT edited.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (pinned `npm ci` → typecheck →
lint → `npm run test:coverage -- --ci` with the module's enforced 100% global
threshold over every production source file).

Result: `All files 100 | 100 | 100 | 100`;
`features/reports 100 | 100 | 100 | 100`;
`ReleasedReportsScreen.tsx 100 | 100 | 100 | 100` (statements/branches/
functions/lines); 79 suites and 1127 tests green. Test files are excluded from
the source scope by `jest.config.ts`; no threshold, exclusion,
`@Generated`/ignore comment, `.skip` or `@Disabled` was added.

This pass added no production line, branch or test, so there is no new branch to
name. The story's production branches remain covered and reconfirmed at 100%:

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
| `report.result === undefined` true / false | no-result case / matrix cases |
| `error !== null`, empty list, `withdrawn`, optional `title`, `correction === null`, `isRtl(report)` | `reports-screen.test.tsx` and the matrix |

## Clinical and architecture review

Not required for this change: no signed data, cross-organization sharing,
patient association change, prescribing or critical result. Presentation-only;
no domain contract or state machine changed. A named VoiceOver/TalkBack pass on
device remains a separate launch gate and is not claimed here.

## Findings

1. **Scoped repair confirmed present and green (not a defect).** The key-omission
   fix in `ReleasedReportsScreen.tablet.test.tsx:236`, the responsive component
   and the tablet test all pass fresh tool runs. The root strict typecheck that
   produced TS2375, the module typecheck, the frontend typecheck gate and the
   full patient-mobile coverage gate are green. Earlier "local checks: failed"
   pushes changed no scoped source; nothing needs undoing.
2. **The recurring integrator failure is out of scope and needs an owner
   decision (escalation).** Earlier findings record repeated
   `integration run failed without fresh, usable test results (compile or setup error)`
   on this PR. I confirmed the mechanism by reading (not editing)
   `scripts/worker-check.sh:43-55`: the worker gate runs
   `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` into
   a private temp report directory and calls
   `python3 -I scripts/worker_gate.py "$mvn_status" "$reports_dir"`;
   `scripts/worker_gate.py:54-69` (`usable_reports`) returns false when that
   directory has no parseable `TEST-*.xml`, so a compile/setup failure that
   writes no reports is reported as this error. `apps/api/target/surefire-reports`
   is absent locally. This is a Java integration/environment defect on the
   integrator side, not a regression from this three-file story branch; the
   branch changes no Java, SQL, contract, migration or threshold. The issue also
   states that after three debugger/fix rounds without green a person must take
   over; those rounds have elapsed. I have no `gh` access and must not edit
   `scripts/`, so I did not attempt a fix. Next step for the owner/integrator:
   make the integration step emit fresh parseable Surefire XML before
   `worker_gate.py` runs (or repair the tooling in a separate owner-approved PR),
   then rerun the integrator gate. Do NOT send this back to another fix worker
   for a code change — no change to `ReleasedReportsScreen.tsx` or its test will
   help.
3. **`check.sh` `opencode.json is stale` is a CI-profile artifact, not a
   regression (out of scope, not fixed).** HEAD and `origin/main` blobs are
   identical and the worktree file is the skip-worktree CI profile; see Test
   evidence for the exact hashes. `opencode.json` and `.opencode/` are protected
   and were not touched.
4. **PR file-count ceiling is approaching (risk).** `scripts/swarm.py:31` sets
   `MAX_PR_FILES = 30`; this branch changes 22 accumulated notes files plus the
   component and its two test files. Check the ceiling before adding further
   notes-only rounds; a large PR is labelled `needs-human` and cannot merge.
5. **Pre-existing conflict markers in three Java HL7 files (out of scope,
   flagged, not fixed).** Markers exist inside comments in
   `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
   and on `origin/main` too; compile/tests are unaffected and they are unrelated
   to this story.
6. **Non-failing React `act()` warning** from `src/app/PatientResource.tsx:54`
   appeared in earlier runs; tests still pass. Unrelated to this scope.
7. No requirement-ID-named folder, new migration, new dependency or scope
   expansion was introduced.

## Why this is not finished

The **scoped story work is finished and all gates I am authorized to run are
green**, but the pull request is not yet mergeable, so I am handing back rather
than claiming completion:

- **What still fails:** the integrator's repeated
  `integration run failed without fresh, usable test results (compile or setup error)`
  and the `check.sh` CI-profile `opencode.json is stale` artifact. Neither is a
  regression from this branch; both are owned outside this story.
- **Most likely cause:** the Java integration step produces no fresh parseable
  Surefire XML when `scripts/worker_gate.py` checks the private report directory;
  and the cloud worker installs the CI `opencode.json` under `skip-worktree` while
  `scripts/check.sh` judges the working file.
- **What I tried:** rehydrated the context, merged `origin/main`, confirmed no
  conflict markers in scope, installed dependencies in all three frontend
  locations, re-ran `bash apps/patient-mobile/check.sh`, the root strict
  typecheck, the frontend typecheck gate and `scripts/check.sh`, and verified the
  `opencode.json` blob identity and skip-worktree flag. No scoped code change was
  needed or made.
- **Exact next step:** a person or the integrator must make the integration step
  emit fresh parseable Surefire XML (or repair the worker-gate tooling in a
  separate owner-approved PR), then rerun the integrator gate on this branch.
  No further code change to `ReleasedReportsScreen.tsx` or its test will help.

## Lessons for workers

- Install module dependencies in **all** frontend modules (`npm ci` in
  `apps/staff-web` and `apps/patient-mobile`) and at the repo root before the
  root `npm run typecheck`; without staff-web modules, unrelated TS2307 errors
  mask the real strict-optional result.
- `ReleasedReport.result` is optional but not `undefined`-writable: omit the key
  with rest destructuring in fixtures instead of assigning `result: undefined`,
  or the root strict typecheck fails with TS2375 while the module-local typecheck
  stays green.
- In cloud workers, treat a `check.sh` / `check-pack.py` `opencode.json is stale`
  result as a CI-profile `skip-worktree` artifact until
  `git show HEAD:opencode.json` differs from `origin/main`; never edit the
  protected file.
- When fresh scoped gates are green but the integrator is still red, do not
  re-apply code fixes or add another "all green" notes round. Name the exact
  failing gate and owning file, confirm it is outside this story's scope, and
  escalate with a concrete next step.
- Each fix pass adds a notes file; check `MAX_PR_FILES` before adding another,
  because a large PR is labelled `needs-human` and cannot auto-merge.
