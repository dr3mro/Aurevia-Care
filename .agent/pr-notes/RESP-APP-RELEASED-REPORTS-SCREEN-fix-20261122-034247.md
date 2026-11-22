# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass)

## Summary

Continuation of the responsive `ReleasedReportsScreen` fix. The branch already
carries the committed one-line repair at HEAD `5eccf8dc`
(`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505`), which replaces
`const withoutResult: ReleasedReport = { ...report, result: undefined };` with
rest-destructuring omission
`const { result: _discardedResult, ...withoutResult } = report;` in
`apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tablet.test.tsx`
(line 236). This run independently re-verified that repair end to end and adds
this hand-off note. No production code, type, config, threshold, exclusion or
tracker was changed by me.

Root cause: the ROOT `tsconfig.json` sets `exactOptionalPropertyTypes: true`,
under which an optional property may be omitted but may not be written as
`undefined` (TS2375). The patient-mobile `tsconfig.json` extends
`expo/tsconfig.base` and does not enable that flag, so the module-local
typecheck passed while the integrator's root typecheck failed.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`).

Acceptance mapping: the story's presentation-only contract is preserved —
synthetic data, no behaviour/text/API/exported-name change, unchanged focus
order and accessibility labels, and 100% coverage. The fix only removes a
type-illegal test assignment; the component's `report.result === undefined`
guard (`ReleasedReportsScreen.tsx:194`) treats an absent key identically to an
explicitly undefined one, so the asserted outcome (no result-detail pane) is
unchanged.

## Risk class

Routine. Test-only type repair; no clinical decision, authorization, patient
association, data, contract, migration or release change
(`docs/WORKFLOW.md`).

## Review owner

Senior React Native and Expo specialist. A bounded advisory review was run this
pass (subagent `smartclinic-senior-mobile`, read-only, no edits) and returned
**no blocking findings**: rest-omission is the smallest correct fix;
`Omit<ReleasedReport, 'result'>` is assignable to the `reports` prop;
`_discardedResult` is exempt from `noUnusedLocals` (rest-sibling rule) and from
`@typescript-eslint/no-unused-vars` (`varsIgnorePattern: '^_'` in
`apps/patient-mobile/eslint.config.mjs`); runtime behaviour is identical. No
simulated user or human participant evidence was involved or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or
forward-fix. Synthetic fixture only; no patient data or credential appears in
the change or this note.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event was touched. The screen
remains presentational; all report data and download authorization still arrive
from the authenticated client.

## Migration and rollout considerations

None. No deployment or rollout change; existing app users keep the same data
flow.

## Test evidence

Evidence sources: **tool** (primary agent executed every command below) and
**reviewer** (bounded `smartclinic-senior-mobile` advisory report). No simulated
user or human participant was involved.

Entry-point-to-test trace: `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The repaired test (`keeps a single summary pane when a
report has no result detail and tolerates no provider`) renders the exported
screen directly with a synthetic report whose `result` key is omitted, injects
`SafeAreaInsetsContext`, drives `Dimensions.get` via `useWindowDimensions`, and
asserts the rendered tree and accessibility labels.

Red first (reproduced locally this pass by temporarily restoring the original
line, then restoring the file exactly — `git diff --exit-code` confirmed a
clean worktree):
- root `npm run typecheck` → single error
  `apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tablet.test.tsx(233,11): error TS2375: Type '{ result: undefined; ... }' is not assignable to type 'ReleasedReport' with 'exactOptionalPropertyTypes: true'. ... Type 'undefined' is not assignable to type '{ readonly label: string; readonly value: string; readonly referenceRange: string; }'.`

Green after the committed fix:
- `git merge --no-edit origin/main` → `Already up to date.` (run at start; no
  conflict markers — `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/patient-mobile`
  returned none).
- root `npm run typecheck` (with both modules' deps installed, as
  `scripts/frontend-check.sh` does) final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  exit 0, zero `error TS…`.
- `npm run typecheck` inside `apps/patient-mobile` final lines (the mandatory
  frontend definition of done):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  exit 0, zero `error TS…`.
- `npm run lint` in `apps/patient-mobile` → exit 0, no new warnings in the
  scoped file (`_discardedResult` covered by `varsIgnorePattern: '^_'`).
- `bash apps/patient-mobile/check.sh` → exit 0. Final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        11.774 s
  Ran all test suites.
  ```
- `bash scripts/frontend-check.sh` → exit 0:
  `frontend standards: policy and tool commands passed` (includes both modules'
  `npm ci`/typecheck/lint/test, npm audit, JS/TS policy and the ROOT strict
  typecheck — the exact integrator gate that previously failed).
- `bash scripts/check.sh` → exit 1, stopping at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (a
  pre-existing environment artifact; see Findings). Full output did not reach
  module gates because `set -e` stopped at this policy step.

Not run locally: `apps/api/check.sh` / full `mvn verify` and `db/check.sh`. The
standing "no full mvn verify" rule and front-end-only scope leave these to the
integrator and Delivery. No Java or migration file was changed.

## Coverage

Command: `bash apps/patient-mobile/check.sh` → `npm ci` → `npm run typecheck` →
`npm run lint` → `npm run test:coverage -- --ci` with the module's enforced 100%
global threshold.

Result: `All files | 100 | 100 | 100 | 100`;
`ReleasedReportsScreen.tsx | 100 | 100 | 100 | 100`;
`Test Suites: 79 passed, 79 total`; `Tests: 1127 passed, 1127 total`.
No threshold, exclusion, `@Generated`, ignore comment or skip was added.

The fix adds no production line or branch. New/changed test branches and the
tests that take them:

| Branch | Test |
|---|---|
| `report.result === undefined` true → no result-detail pane (now type-legal via omission) | `keeps a single summary pane when a report has no result detail and tolerates no provider` |
| `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context undefined | same test (`renderScreen(..., false)`) |

All other production branches (`windowClass === 'large'` true/false, `locale ===
'ar'` true/false, insets non-null/null, and the result-present path) remain
covered by `ReleasedReportsScreen.tablet.test.tsx` and
`reports-screen.test.tsx` as recorded in the original implementation notes.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical result was changed. Presentation-only test repair; no
domain contract or state machine changed. A named VoiceOver/TalkBack pass on
device remains a separate launch gate and is not claimed here.

## Findings

1. **Fix verified, no code change needed this pass.** The committed repair at
   HEAD `5eccf8dc` is correct; root typecheck, `apps/patient-mobile/check.sh`
   and `scripts/frontend-check.sh` are all green. The only file I add is this
   note.
2. **Pre-existing policy artifact — `check-pack.py` reports `opencode.json is stale`.**
   In this worker checkout `git ls-files -v opencode.json` is `S` (skip-worktree):
   the worktree file is the CI profile copied by `agent-run.sh` and its SHA
   differs from `HEAD:opencode.json`. `git status --short opencode.json` is
   empty and my diff touches no agent file. This is a local environment
   artifact, not a branch regression; earlier integrator logs show the same.
3. **Pre-existing policy artifact — `python-coverage.sh`** prints
   `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` and
   `integration run failed without fresh, usable test results (compile or setup
   error)`. `git diff -- TODO.md` is empty (swarm-worker rule forbids editing
   trackers), and the compile/setup errors are because `apps/api/target/surefire-reports`
   does not exist in a worker that is forbidden from running full `mvn verify`.
   Both are documented in earlier findings as pre-existing on `origin/main`;
   the integrator's Java step generates surefire reports, so it is unaffected.
4. **Out-of-scope React `act()` warning** from `src/app/PatientResource.tsx:54`
   appeared in earlier full-suite runs; tests still pass. Unrelated to this
   scoped file and not fixed here.
5. **No new defect was introduced by this fix.**

## Lessons for workers

A patient-mobile module-local `tsc --noEmit` does **not** enable
`exactOptionalPropertyTypes`; the root `npm run typecheck` does. Always run
`bash scripts/frontend-check.sh` (or at least root `npm run typecheck` after
installing both modules' deps) before calling a frontend fix green. Under
`exactOptionalPropertyTypes`, omit an optional key with rest destructuring
(`const { key: _unused, ...rest } = value;`) instead of writing `key: undefined`.
In this worker environment, `check-pack.py`'s "opencode.json is stale" and
`python-coverage.sh`'s `TODO.md` tracker/surefire findings are known skip-worktree
and missing-build artifacts — record them as pre-existing rather than editing
`opencode.json`, `scripts/` or any tracker.

## Why this is not finished

The scoped code fix is finished and all gates for the touched area are green
(Tool evidence above: root typecheck EXIT=0, `apps/patient-mobile/check.sh`
EXIT=0 at 100% coverage, `scripts/frontend-check.sh` EXIT=0). The one local
command that still fails is `bash scripts/check.sh`:

- Command/error: `timeout 300 bash scripts/check.sh` → exit 1 with final line
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
  Separately, `bash scripts/python-coverage.sh` → exit 1 with
  `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` and several
  `integration run failed without fresh, usable test results (compile or setup
  error)` lines.
- Most likely cause: both are pre-existing worker-environment artifacts — a
  skip-worktree `opencode.json` holding the CI profile (`git ls-files -v` = `S`,
  clean `git status`) and absent `apps/api/target/surefire-reports` because
  workers are forbidden from running full `mvn verify`. Neither is caused by
  this branch and neither is fixable from within task scope (editing
  `opencode.json`, `scripts/` or trackers is forbidden and would block
  auto-merge).
- What I tried: confirmed the exact errors, confirmed the worktree is clean for
  `opencode.json` and `TODO.md`, confirmed no conflict markers, and ran every
  applicable frontend gate directly (all green). I did not edit any out-of-scope
  file to force `scripts/check.sh` green.
- Exact next step: let the trusted integrator/CI run `scripts/check.sh` in an
  environment with the committed `opencode.json` profile and freshly generated
  surefire reports; its Java step supplies the missing verdict. No further code
  change is needed for this fix.
