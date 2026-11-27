# QA-SOURCE-04 · Show and retry staff scheduling pagination failures

PR #1779, tested branch tip 1b60bbd109ef98c63bb142efcc81e86faab7b78e.

Task: QA-SOURCE-04

Swarm worker 2. Checks: passed. Local checks: failed. Agent result: ok.

# QA-SOURCE-04 — Show and retry staff scheduling pagination failures

## Summary

Make the staff scheduling worklist retain already-loaded rows when a later page
fails and offer a working localized Retry that reuses the same cursor, appending
the recovered rows exactly once. This closes the WEB-09 error/retry gap for the
appointment worklist while keeping CLN-01 visit data intact. The change is
contained to `apps/staff-web/src/features/scheduling`.

## Affected requirements

- CLN-01 — visit data stays intact; loaded rows are not discarded on a later-page failure.
- WEB-09 — every screen defines an error/offline state with a recovery control and a component test, in English and Arabic.

## Risk class

Routine (contained staff-web view and test change; no migration, route, contract or clinical release behavior).

## Review owner

Primary agent; read-only specialists named in the task brief (senior-react, senior-typescript, context-optimizer, test-engineer, plan-reviewer, code-reviewer, coverage-auditor, devsecops).

## Data changes

None. No migration, backfill, RLS, lock or volume impact; no rollback or forward-fix SQL.

## Authorization boundaries and audit events

Unchanged. Retry reuses the active tenant scope (`X-Active-Organization`) and the same day/week window and cursor; no audit event added. Error view renders only a typed error and correlation id, never patient data. Synthetic fixtures only (`patient-001`, `patient-002`).

## Migration and rollout considerations

None. Frontend-only change; no OpenAPI, generated client or route change; GET retry needs no idempotency key.

## Skills loaded

- aurevia-delivery — every story; delivery loop and swarm hand-off.
- aurevia-senior-react — staff-web scheduling view, error recovery and view tests.
- aurevia-senior-typescript — strict TypeScript typing of the locale helper and view wiring.
- aurevia-context-optimizer — bounded discovery of requirement IDs and paths.
- aurevia-test-engineer — red regression test and branch-to-test map.
- aurevia-plan-reviewer — plan verdict before coding.
- aurevia-code-reviewer — code verdict after coding.
- aurevia-coverage-auditor — 100% branch coverage audit after coding.
- aurevia-devsecops — defensive/tenant-scope review after coding.

## Test evidence

Tested revision: 6f3e1399ae5ed4fe3553b533b12e9350b1734258

All commands were run from the repository root unless noted. Exact final lines are quoted; scratch output stayed under `/tmp/aurevia-qa-source-04/`.

- Red, before the production edit (from `apps/staff-web`): `./node_modules/.bin/vitest run src/features/scheduling/SchedulingPage.test.tsx --exclude 'e2e/**'` → `Test Files  1 failed (1)`; `Tests  3 failed | 24 passed (27)`; `Duration  5.26s`. All three new tests failed at the missing localized error/correlation assertions (`SchedulingPage.test.tsx:422`, `:466`, `:490`), confirming the acceptance gap.
- Green after the production edit, same targeted command → `Test Files  1 passed (1)`; `Tests  27 passed (27)`; `Duration  2.29s`.
- Canonical gate: `bash apps/staff-web/check.sh` → exit 0. Stage lines: `> aurevia-staff-web@0.1.0 gen:api:check` / `> bash scripts/gen-api-types.sh --check`; `> aurevia-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json`; `> aurevia-staff-web@0.1.0 lint` / `> eslint . --max-warnings=0`; `> aurevia-staff-web@0.1.0 build` / `> tsc --noEmit -p tsconfig.json && vite build`; `✓ 215 modules transformed.`; bundle budget script ran with no output and exit 0; `Test Files  295 passed (295)`; `Tests  3296 passed (3296)`; `No files with missing coverage.`; `292 files fully covered.`; `11 empty files skipped.`
- Typecheck final lines (from the gate): `> tsc --noEmit -p tsconfig.json`, no `error TS…` output, exit 0. Lint final line: `> eslint . --max-warnings=0`, exit 0.
- Formatting: `node_modules/.bin/prettier --check apps/staff-web/src/features/scheduling/SchedulingPage.tsx apps/staff-web/src/features/scheduling/SchedulingPage.test.tsx` → `All matched files use Prettier code style!`, exit 0. Root `npm ci --no-audit --no-fund` installed the pinned formatter first (`added 164 packages in 1s`).
- `git diff --check` → exit 0 (`DIFF_CHECK_OK`). Branch was merged with `git merge --no-edit origin/main` → `Already up to date.`; `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no conflict markers.

Entry-point-to-test trace: `SchedulingPage` renders `pageErrorNotice(result.error, handleRetryPage)` inside its existing loaded-rows fragment; the notice calls `StateView.forTransportError(error, { onRetry })`, whose `ErrorState` button invokes `handleRetryPage`; that calls `result.reload()` from `useApi`, bumping only the request generation; `listAppointments` (`api.ts:133-154`) re-sends the unchanged `from`/`to`/`cursor` with `X-Active-Organization`. `SchedulingPage.test.tsx` 'pagination failure recovery' clicks the real Retry and the real Load more button, then asserts the third `RequestDescriptor` URL, headers and appended rows. Tool evidence only; no simulated user or human feedback was used.

## Coverage

Command: `bash apps/staff-web/check.sh` (its `npm run test:coverage` step is `vitest run --coverage --exclude 'e2e/**'`; `apps/staff-web/vite.config.ts` sets `provider: 'v8'`, `perFile: true` and 100% lines/branches/functions/statements). Result: exit 0, `Statements   : 100% ( 5824/5824 )`, `Branches     : 100% ( 2936/2936 )`, `Functions    : 100% ( 1918/1918 )`, `Lines        : 100% ( 5598/5598 )`, `No files with missing coverage.`

Touched file from `apps/staff-web/coverage/coverage-summary.json` (`SchedulingPage.tsx`): lines 66/66, statements 69/69, functions 19/19, branches 30/30 — all 100%. No threshold, exclusion, skip or suppression was added.

Branch-to-test map (new or changed branches only):

| Branch | True side / test and failing assertion | False side / test |
|---|---|---|
| `pageErrorNotice`: `error === undefined ? null : <notice>` (`SchedulingPage.tsx`) | 'keeps earlier rows and retries the same cursor when a later page fails' asserts `findByText(stateViewText('en','error'))`, `corr-page2` and Retry; ar case asserts Arabic error/Retry; Load-more-after-failure asserts `corr-page2` | Every ready/loading test, e.g. 'loads the next page while a cursor exists and keeps earlier rows' and 'shows the loading state until the first page resolves', which would see an unexpected error notice |
| `activateNextPage`: `if (status === 'error') onRetry(); return;` | 'retries the same cursor when Load more is pressed after a failure' clicks Load more while failed and asserts `descriptorAt(2).url` contains `cursor=cursor-2` plus one `patient-002` row | Existing 'loads the next page while a cursor exists and keeps earlier rows' clicks Load more in `ready` state and asserts the cursor page is appended |
| New `handleRetryPage` callback (`result.reload()`) | Primary regression clicks Retry and asserts a third GET with `cursor=cursor-2`, same `from`/`to` and tenant header; `getAllByText('patient-002')).length === 1` and `getAllByText('patient-001')).length === 1` fail if rows are reset or duplicated | Existing paging and command tests never invoke the failed-page callback; function coverage is provided by the true-side tests above |
| Existing `result.status === 'loading' && loaded.length === 0` false side | Primary regression holds the retry with a deferred and asserts the `patient-001` rowheader stays visible and no Loading heading replaces it | Existing loading test takes the true side |
| Existing `result.status === 'error' && loaded.length === 0` false side | Three new tests assert rows stay visible with an inline error rather than the full-page error | Existing `it.each(['server','timeout','offline','invalid','rate-limited'])` plus forbidden/session-expired tests take the true side |
| Existing `nextCursor !== undefined ? <Load more/> : null` in error state | Load-more-after-failure asserts the button is pre
