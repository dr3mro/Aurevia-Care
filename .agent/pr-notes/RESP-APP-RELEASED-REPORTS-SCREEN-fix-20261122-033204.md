# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass)

## Summary

Repair the integration failure that blocked the already-implemented responsive
`ReleasedReportsScreen` story. The branch changed only one line in the new
tablet test so the ROOT strict typecheck (which enables
`exactOptionalPropertyTypes`) accepts it. No production code, type, design,
threshold, exclusion or configuration was touched.

The offending line explicitly assigned `undefined` to `ReleasedReport.result`,
an optional property. Under `exactOptionalPropertyTypes: true` an optional
property may be omitted but may not be explicitly written as `undefined`
(TS2375). The fix omits the key via rest destructuring; the production component
already treats an absent `result` identically to an explicitly undefined one
(`ReleasedReportsScreen.tsx:194`).

Change:
`apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tablet.test.tsx:233`
- before: `const withoutResult: ReleasedReport = { ...report, result: undefined };`
- after:  `const { result: _discardedResult, ...withoutResult } = report;`

The preceding comment explains why the key is omitted rather than assigned.

## Affected requirements

PAT-01, PAT-02, UX-01 (per task `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`).
Acceptance mapping: the story's existing contract — presentational, synthetic
data only, unchanged exported names, labels, focus order and accessibility
labels, 100% coverage — is preserved. The fix removes only a type-illegal test
assignment; no acceptance behavior changed.

## Risk class

Routine. Test-only type repair; no clinical decision, authorization, data,
contract, migration or release change (docs/WORKFLOW.md).

## Review owner

Senior React Native and Expo specialist — bounded advisory review received
(subagent `orevia-senior-mobile`, report only, no edits). It confirmed the
omit idiom is the smallest correct fix, that `_discardedResult` is exempt from
both `noUnusedLocals` (rest-sibling exemption) and
`@typescript-eslint/no-unused-vars` (`varsIgnorePattern: '^_'`), and that
`Omit<ReleasedReport, 'result'>` is assignable to the `reports` prop. No
simulated user or human participant evidence was involved or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.
Synthetic fixture only.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event touched. The component
remains presentational; all report data and download authorization still arrive
from the authenticated client.

## Migration and rollout considerations

None. Existing app users keep the same data flow; no deployment or rollout
change.

## Test evidence

All commands executed by the primary agent (tool evidence). Entry-point-to-test
trace: `ReleasedReportsScreen` → `ScreenFrame` → `reports-content` wrapper →
`report-panes-<id>` → `report-summary-<id>` / `report-detail-<id>`. The repaired
test renders the exported screen directly with a synthetic report whose
`result` key is omitted, injects `SafeAreaInsetsContext`, and asserts the single
summary pane and absent result-detail pane.

Red first (before the edit), ROOT strict typecheck — failed with exactly one
error:
`apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tablet.test.tsx(233,11): error TS2375: Type '{ result: undefined; ... }' is not assignable to type 'ReleasedReport' with 'exactOptionalPropertyTypes: true'. ... Type 'undefined' is not assignable to type '{ readonly label: string; readonly value: string; readonly referenceRange: string; }'.`
(`npm run typecheck`, exit 2.)

After the edit:

- `npm run typecheck` (root, strict) final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  (no output after the command — exit 0, zero `error TS…`).
- `npm run typecheck` in `apps/patient-mobile` final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  (no output after the command — exit 0, zero `error TS…`). This is the exact
  final line required by the frontend definition of done.
- `npm run lint` in `apps/patient-mobile` → `✖ 4 problems (0 errors, 4 warnings)`,
  exit 0. All warnings are pre-existing `import/no-duplicates` /
  `@typescript-eslint/array-type` in unrelated test files, none in the scoped
  file.
- `bash apps/patient-mobile/check.sh` → exit 0. Final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        15.26 s
  Ran all test suites.
  ```
- `bash scripts/frontend-typecheck-check.sh` → exit 0:
  `frontend typecheck: passed (or no frontend files changed)`.
- `bash scripts/frontend-check.sh` → exit 0:
  `frontend standards: policy and tool commands passed` (includes both modules'
  `npm ci`/typecheck/lint/test, npm audit, JS/TS policy, and root
  `npm run typecheck`).
- `git merge --no-edit origin/main` → `Already up to date.` (run at start and
  again before finishing); no conflict markers in the scoped file.

Not run locally: `apps/api/check.sh` (full `mvn verify`, >10 min) and
`db/check.sh` (PostgreSQL). The standing no-full-mvn-verify rule and the
front-end-only scope leave these to the integrator and Delivery. No Java or
migration file was changed.

## Coverage

Command: `bash apps/patient-mobile/check.sh` → `npm ci` → `npm run typecheck` →
`npm run lint` → `npm run test:coverage -- --ci` with the module's 100% global
threshold; corroborated by `npm run test:coverage -- --ci` (exit 0).

Result: `All files          |     100 |      100 |     100 |     100 |`;
`ReleasedReportsScreen.tsx |     100 |      100 |     100 |     100 |`;
`Test Suites: 79 passed, 79 total`; `Tests: 1127 passed, 1127 total`.
No threshold, exclusion, `@Generated`, ignore comment or skip was added.

New/changed branches and their tests (the fix changes no production branch):

| Branch | Test |
|---|---|
| `report.result === undefined` → no result-detail pane (unchanged branch, now type-legal) | `keeps a single summary pane when a report has no result detail and tolerates no provider` (uses `withoutResult`) |
| `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context undefined | same test (renders with `withInsets = false`) |

All other production branches remain covered by
`ReleasedReportsScreen.tablet.test.tsx` and `reports-screen.test.tsx` as recorded
in the original worker's notes.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical result was changed. Presentation-only test repair; no
domain contract or state machine changed. No named VoiceOver/TalkBack or human
clinical review is claimed.

## Findings

1. **Root cause (confirmed, high confidence).** The failing integrator check
   was the ROOT strict typecheck added by `scripts/frontend-check.sh`, not the
   module-local typecheck. The module `tsconfig.json` extends
   `expo/tsconfig.base`, which does not enable `exactOptionalPropertyTypes`, so
   the original worker's `npm run typecheck` passed while the integration gate
   failed. Fixed by omitting the key instead of assigning `undefined`.
2. **Pre-existing policy artifact — `check-pack.py` reports `opencode.json is stale`.** In this worker checkout `git ls-files -v opencode.json` is `S` (skip-worktree): the worktree file is the CI profile copied by `agent-run.sh` and its SHA differs from `HEAD:opencode.json`. `git status` is clean and my diff contains only the test file, so this is not a branch regression. I did not edit `opencode.json` or any agent file.
3. **Pre-existing policy artifact — `python-coverage.sh` reports `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}`.** `git diff -- TODO.md` is empty; I did not touch any tracker (swarm-worker rule). This also appeared in earlier integrator logs.
4. **Out-of-scope worker-gate defect (not fixed).** Earlier findings identify two `scripts/tests/test_worker_gate*.py` `FileNotFoundError` errors when `apps/api/target/surefire-reports` is absent. They are pre-existing on `origin/main`, not caused by this three-file branch, and editing `scripts/` is forbidden for swarm workers (it would block this PR's auto-merge). The integrator's Java step generates surefire reports, so it is unaffected. Flag separately; do not fold into this fix.
5. **Out-of-scope React warning.** Earlier runs show a non-failing `act()` console warning from `src/app/PatientResource.tsx:54`; tests still pass. Unrelated to this file.
6. No new defect was introduced by this fix.

## Lessons for workers

Run the ROOT `npm run typecheck` (or `bash scripts/frontend-check.sh`) before
calling a patient-mobile task green: the module's own `tsc -p` does not enable
`exactOptionalPropertyTypes`, so a test that assigns `undefined` to an optional
property can pass locally and fail only at integration. Under
`exactOptionalPropertyTypes`, omit an optional key with rest destructuring
(`const { key: _unused, ...rest } = value;`) rather than writing `key: undefined`.
