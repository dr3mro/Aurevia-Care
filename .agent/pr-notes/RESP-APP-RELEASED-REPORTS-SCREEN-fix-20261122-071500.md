# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — 2026-11-22 07:15 UTC)

## Summary

Continuation of issue #656 / PR #655 on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505` (HEAD `034fafd7` at start). I read
the earlier findings first and continued from them rather than starting over.
The scoped repair was **already present and committed** before this pass:
commit `5eccf8dc` ("fix strict optional-property typecheck in tablet test"),
an ancestor of HEAD. At `ReleasedReportsScreen.tablet.test.tsx:236` the optional
`ReleasedReport.result` key is **omitted** via rest destructuring
(`const { result: _discardedResult, ...withoutResult } = report;`) instead of
being assigned `undefined`, which the repo-root strict `tsconfig.json` rejects
as TS2375 under `exactOptionalPropertyTypes: true`. A later commit `70d4be33`
corrected the adjacent doc comment. No earlier code fix needs to be undone.

I rehydrated the governing context (AGENTS.md, CODING_STYLE.md, README-SETUP.md,
docs/WORKFLOW.md, WORKSPACE.md, TODO.md, MEMORY.md, PROJECT.md, the task file,
decision `0005-patient-app-tablets.md`, the spec clause
`verified-adult-patients-see-appointments-released-reports.md`, both scoped
files and the layout hook) and loaded the matching skills:
`orevia-delivery`, `orevia-senior-mobile` and
`orevia-senior-typescript`. I then re-ran every scoped gate myself with
fresh tool commands; all pass. **This pass changes only this notes file** — no
production, test, config, threshold, exclusion, tracker, workflow, `scripts/`
or `.opencode/` file was edited by me.

Evidence source: **tool** evidence (commands the primary agent ran and
observed). No reviewer, simulated-user or human-participant evidence is
claimed. No named clinician or patient review was available and none is
claimed. Specialists were loaded as advisory skills and made no edits.

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
`report-detail-<id>`. The new test renders the exported screen directly,
injects `SafeAreaInsetsContext`, drives `Dimensions.get`, and asserts tree,
labels, insets and direction. The fix itself is test-only: runtime behavior and
all acceptance scenarios are unaffected.

## Risk class

Routine (`docs/WORKFLOW.md`). Presentation-only responsive layout plus a
test-only type repair — no clinical decision, authorization, patient
association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo (`orevia-senior-mobile`) and Senior TypeScript
(`orevia-senior-typescript`) guidance loaded and applied; their definition
of done is met. Independent QA and the full integration gate belong to the
integrator/Delivery. No named clinical or patient reviewer was available.

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
unless noted. `git merge --no-edit origin/main` (start) → `Already up to date.`;
`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- 'apps/patient-mobile/**'` → no output
(no conflict markers in scope).

- `bash apps/patient-mobile/check.sh` → **exit 0**. Final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        18.242 s
  Ran all test suites.
  ```
  `npm ci`, `npm run typecheck`, `npm run lint` all passed inside the gate.
- `cd apps/patient-mobile && npm run typecheck` → **exit 0**. Typecheck final
  lines (no output after the command — zero `error TS…`):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- `cd apps/patient-mobile && npm run lint` → **exit 0**: `4 problems
  (0 errors, 4 warnings)`; all 4 warnings are pre-existing `import/no-duplicates`
  and `@typescript-eslint/array-type` in unrelated
  `features/tracker/presentation.test.ts` and
  `features/enrollment/screens.tablet.test.tsx`, not in a scoped file.
- Root `npm ci --no-audit --no-fund` → exit 0 (`added 164 packages`). Root
  `npm run typecheck` (the strict `tsconfig.json` with
  `exactOptionalPropertyTypes: true` — the gate that originally surfaced
  TS2375) → **exit 0**, final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  This gate only succeeds once `apps/staff-web` dependencies are also installed
  (see Finding 2); without them it reports unrelated `TS2307`/`TS2875`
  missing-module errors in staff-web.
- `cd apps/staff-web && npm ci --no-audit --no-fund` → exit 0
  (`added 486 packages`).
- `bash scripts/frontend-typecheck-check.sh` → **exit 0**, final line:
  `frontend typecheck: passed (or no frontend files changed)` (module typecheck
  + lint green; same 4 unrelated warnings, 0 errors).
- Targeted coverage confirmation:
  `cd apps/patient-mobile && ./node_modules/.bin/jest --ci --coverage --coverageReporters=text --runInBand`
  → **exit 0**, 79 suites / 1127 tests green, `ReleasedReportsScreen.tsx`
  `100 | 100 | 100 | 100`.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (pinned `npm ci` → typecheck →
lint → `npm run test:coverage -- --ci` with the module's enforced 100% global
threshold over every production source file), corroborated by the targeted
Jest coverage command above.

Result: `apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tsx`
**100 | 100 | 100 | 100** (statements/branches/functions/lines);
`patient-mobile/src/features/reports` **100 | 100 | 100 | 100**;
`All files` **100 | 100 | 100 | 100**; 1127 tests green. Test files are excluded
from the source scope by `jest.config.ts`; no threshold, exclusion,
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

1. **Scoped repair confirmed present and green (not a defect).** The one-line
   test fix at `5eccf8dc` (doc-comment correction at `70d4be33`) and the
   responsive component are complete. The root strict typecheck (the gate that
   produced TS2375), the module typecheck, the fast frontend gate and the full
   patient-mobile coverage gate all pass on fresh tool runs. The repeated
   "local checks: failed" pushes in the issue did not change the scoped source.
2. **Root strict typecheck depends on staff-web dependencies (environment, not
   a code defect).** With only root + patient-mobile dependencies installed,
   `npm run typecheck` at the repo root failed with `TS2307`/`TS2875`
   missing-module errors across `apps/staff-web` (react, react-dom/client,
   @testing-library/react). After `cd apps/staff-web && npm ci` it exits 0 with
   no errors. This is the most likely cause of a worker's "local checks failed"
   when it ran the root strict typecheck in a fresh checkout.
3. **`check-pack.py` "opencode.json is stale" is a CI-profile artifact, not a
   branch regression (out of scope, not fixed).** Prior debugger findings
   recorded that the worktree `opencode.json` is the CI profile copied under
   `skip-worktree`, so its hash differs from the committed HEAD blob; HEAD's
   committed blob equals `origin/main`'s. `opencode.json` and `.opencode/` are
   protected files a worker must not edit. The integrator's failure log
   contained the TS2375 error, not this one.
4. **A `FileNotFoundError` for the surefire reports directory reproduces on a
   clean main worktree (out of scope, not fixed).** Earlier debugging recorded
   that it disappears when the surefire directory exists; it is a tooling/
   environment condition in `scripts/python-coverage.sh`, not a scoped source
   defect.
5. **Full `scripts/check.sh` was deliberately not run.** It invokes
   `apps/api/check.sh` (full `mvn verify`, >10 min locally; prior rounds timed
   out at 20 minutes still applying migrations) and `db/check.sh`. Worker rules
   stop a command that passes 5 minutes; the integrator and Delivery own the
   full gate and the 100% coverage check. Scoped frontend gates were run.
6. **Pre-existing conflict markers, out of scope (flagged, not fixed).**
   `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds markers in
   `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
   (inside comments, so compile/tests are unaffected). They exist on
   `origin/main` too and are unrelated to the patient-mobile reports screen;
   this worker did not touch them.
7. **React `act()` warning is not a failure (out of scope).** An
   `act(...)` warning from `src/app/PatientResource.tsx:54` appears in some
   test output; all 1127 tests still pass. It is unrelated to the reports
   screen and was not changed.
8. No requirement-ID-named folder, new migration, new dependency or scope
   expansion was introduced.

## Lessons for workers

- Install module dependencies in **all** frontend modules and the root before
  running the **root** `npm run typecheck`: `npm ci` in `apps/patient-mobile`,
  `apps/staff-web` and the repo root. The root `tsconfig.json` is the only one
  with `exactOptionalPropertyTypes: true`; missing staff-web dependencies mask
  the real strict-optional error with dozens of `TS2307`/`TS2875` errors.
- `ReleasedReport.result` is optional but not `undefined`-writable: omit the
  key (rest destructuring) in fixtures instead of assigning
  `result: undefined`, or the root strict typecheck fails with TS2375 while the
  module-local typecheck stays green.
- In cloud workers, treat a `check-pack.py` "opencode.json is stale" result as
  a CI-profile artifact until the committed blob (`git show HEAD:opencode.json`)
  is proven different from `origin/main`; never "fix" it by editing the
  protected file.
- When the issue says a fix worker's "local checks failed" but fresh scoped
  tool runs are green, re-run the gates yourself and record the exact commands
  and final lines instead of re-applying or guessing at a new code change.
  A fresh checkout needs all three `npm ci` runs before the root typecheck.
- Do not run the full `mvn verify` / `scripts/check.sh` as a worker; it exceeds
  the 5-minute worker cap. Run only the tests for the code you touched and let
  the integrator/Delivery run the full gate.
