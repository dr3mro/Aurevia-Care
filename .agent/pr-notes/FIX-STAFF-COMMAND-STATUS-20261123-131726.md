# FIX-STAFF-COMMAND-STATUS — verification resume

## Summary

Resume of an already-applied staff-web fix. The production change was committed at `HEAD` by the first attempt and is present in `origin/main`; this pass re-verifies it end to end and records durable evidence. No new code was written in this pass.

Behavior kept: when a specimen collection or rejection command succeeds and the queue reload returns zero rows, the success notice ("Collection recorded." / "Specimen rejected.") remains visible through a detached `role="status"` announcement instead of disappearing with the rows screen. Pharmacy detail tests now wait for both the accepted and dispensed flags after the refreshed detail commits, while still asserting command routing, body, `If-Match` and `Idempotency-Key`.

## Affected requirements

- LAB-01 — lab collection success notice survives the empty-queue reload.
- RX-02 — pharmacy detail flags asserted only after the committed reload.

## Risk class

Routine (contained staff-web view fix plus test stabilization). No API, database, contract or authorization boundary changed.

## Review owner

Senior React and Vite (staff-web); read-only review by code-reviewer, coverage-auditor and devsecops.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

Unchanged. The active-organization header/tenant remount and pharmacy prescription scope are untouched; no audit event added or removed.

## Migration and rollout considerations

None.

## Test evidence

All commands run from `apps/staff-web` unless stated otherwise. Evidence source is the primary agent running the commands (tool output), plus the named read-only reviewers.

- Focused scoped tests — `npx vitest run src/features/labcollection/CollectionDeskPage.test.tsx src/features/pharmacyinbox/PharmacyItemPage.test.tsx`
  - Final lines: `Test Files  2 passed (2)`, `Tests  39 passed (39)`.
- Full suite with coverage — `npm run test:coverage`
  - Final lines: `Test Files  213 passed (213)`, `Tests  2613 passed (2613)`.
  - The 2,613 test assertions all pass. The command exits 1 only because 19 unrelated pre-existing source files are below the unchanged per-file 100% threshold (see Findings).
- Typecheck (required frontend gate, separate final line) — `npm run typecheck`
  - `> tsc --noEmit -p tsconfig.json`, exit 0; `tsc` prints nothing on success.
- Lint — `npm run lint` → `> eslint . --max-warnings 0`, exit 0.
- Build — `npm run build` → `✓ built in 203ms`, exit 0.
- Bundle budget — `node scripts/check-bundle-size.mjs` → every chunk `ok`, exit 0.
- Full module gate — `bash check.sh` → exit 1, stops at the first step `npm run gen:api:check` with `✘ Generated types are not up-to-date!` (pre-existing out-of-scope drift, see Findings). Stages after it were run individually and are green except the coverage debt above.
- Facilities isolation (out-of-scope test named in the brief) — `npx vitest run tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx` → `Tests  17 passed (17)`; `npx vitest run src/features/admin/FacilitiesPage.test.tsx` → `Tests  22 passed (22)`. The earlier failure did not reproduce; no edit was made.
- Dependency scan — `npm audit --omit=dev --audit-level=high` → `found 0 vulnerabilities`, exit 0; `npm audit --audit-level=high` → exit 0 (moderate dev-only advisories in `minimatch`/`qs` chains, none high/critical). The earlier `registry.npmjs.org` resolution failure was environmental/transient and changed no config.

Entry-point-to-test trace:
- `CollectionDeskPage` render → `handleCollect`/`handleReject` command → `reload()` empties rows → `DetachedCollectionStatus` renders `role="status" data-testid="collection-status"` → asserted in `CollectionDeskPage.test.tsx` (collection 184-207, rejection 269-293) and asserted absent on conflict (209-225, 295-312).
- `PharmacyItemPage` `onCommitted` → `reloadToken` → refreshed detail → `accepted-flag`/`dispensed-flag` → awaited with `waitFor` in `PharmacyItemPage.test.tsx` (257-321), with command URL/body/`If-Match`/`Idempotency-Key` still asserted.

## Coverage

Command: `npm run test:coverage` (Vitest V8, per-file 100% thresholds, all `src/**/*.{ts,tsx}`).

Result for the files touched by this task, read from `coverage/coverage-summary.json`:
- `src/features/labcollection/CollectionDeskPage.tsx` — lines 100%, branches 100%, functions 100%, statements 100%.
- `src/features/pharmacyinbox/PharmacyItemPage.tsx` — lines 100%, branches 100%, functions 100%, statements 100%.

Neither touched file appears in the coverage-error list. No threshold, exclusion, skip, suppression or generated-code ignore was added.

Branch map for `DetachedCollectionStatus({ hasRows, message })` and its call site:
- `hasRows === true` → returns `null` (rows present); taken by every command test before the reload resolves and by the paging/tenant tests after rows render — `CollectionDeskPage.test.tsx:184-207`, `316-381`; assertion: the desk screen is present and no detached notice is expected while rows exist (`getByRole('heading', { name: labText('en','collectionDesk') })`).
- `hasRows === false && message === ''` → returns `null`; taken by the initial-empty test — `CollectionDeskPage.test.tsx:148-153`; assertion: `findByText(stateViewText('en','empty'))`.
- `hasRows === false && message !== ''` → renders `<p role="status" data-testid="collection-status">`; taken by the collection and rejection tests after an explicitly empty reload — `CollectionDeskPage.test.tsx:184-207` and `269-293`; assertions: empty state plus `getByTestId('collection-status').textContent` contains `collectionRecorded` / `specimenRejected`.
- Failure paths keep `statusMessage` empty (`setStatusMessage('')` in every catch: lines 120, 130, 142). The conflict cases assert no success text — `CollectionDeskPage.test.tsx:209-225`, `295-312` (against the in-screen `collection-status` node, which also proves the detached node is absent because `getByTestId` throws on duplicates). The unexpected-failure case `227-239` asserts the typed error and `correlationUnavailable` but not absence of success text; the expired-session case `172-176` is a list-load failure only, with no command dispatched. The same catch branch is already taken by the conflict tests, so no success path is uncovered.
- Render ternary chain: loading (`135-146`), error (`155-176`), empty (`148-153`), rows (`184-207` and paging tests) each have a render test.
- Pharmacy both-flag `waitFor` after `onCommitted`: accept → `accepted-flag` = flagYes, `dispensed-flag` = flagNo (`PharmacyItemPage.test.tsx:257-278`); dispense → flagNo, flagYes (`280-321`).

## Clinical and architecture review

Not required for this slice. No patient identity, result release, order, prescription or downtime behavior changed; the notice is a localized UI string and pharmacy assertions are test-only. No new sign-off is introduced.

## Review verdicts

- smartclinic-test-engineer: GO — all three `DetachedCollectionStatus` arcs have named, assertion-bearing tests; pharmacy `waitFor` proves both flags after the committed reload. Four Optional hardening ideas recorded under Findings.
- smartclinic-senior-react: GO — status lifecycle, `role="status"` accessibility, RTL queries, failure/tenant-switch/select resets and pharmacy both-flag assertions all correct. Two Optional (non-blocking) observations recorded under Findings.
- smartclinic-code-reviewer: GO — no Critical/Required finding; no API/route/OpenAPI/migration change introduced; success-notice lifecycle and pharmacy assertions correct. Three Optional observations recorded under Findings.
- smartclinic-coverage-auditor: GO — mapped every new/changed branch (both sides of `hasRows || message === ''`, the JSX return, both try/catch arms for collect/reject/print, the render ternary chain and the pharmacy post-reload `waitFor`) to an assertion-bearing test; no missing test, no suppression.
- smartclinic-devsecops: GO
- smartclinic-plan-reviewer: not consulted — no new code in this pass (verification and documentation only).

## Findings

- `bash apps/staff-web/check.sh` cannot be green at current `origin/main` for reasons outside this task's scope. It stops at `gen:api:check`: `contracts/openapi.yaml` contains `/api/v1/staff/instant/events` and `/api/v1/staff/instant/events/stream` (added by SMSG-04, commit `2844218b`) but `apps/staff-web/src/api/generated/openapi.d.ts` (last regenerated in SMSG-08, `b0bc46b8`) does not. Regenerating that client is a documented step of existing tasks (FIX-INSTANT-OPENAPI, FIX-INSTANT-SEND-CONTRACT, FIX-OPENAPI-04), so no new task is requested here and no out-of-scope file was edited.
- `npm run test:coverage` reports 19 source files below the unchanged per-file 100% threshold (48 threshold errors). Examples: `src/features/labcollection/CollectionDeskScreen.tsx` (91.17% lines, 84.21% branches), `src/components/invariant.ts` (66.66% lines), several `fixtures.ts` files, `src/features/chart/NotePage.tsx` and scheduling files. This debt is already owned by the sibling task FIX-STAFF-COMMAND-STATUS's neighbour `FIX-FRONTEND-GATES` (its `pending` line records the same 19 files). The two files touched by this task are at 100%.
- The brief's Facilities RTL failure did not reproduce in this environment: both `FacilitiesPage.responsive.test.tsx` (17 tests) and `src/features/admin/FacilitiesPage.test.tsx` (22 tests) pass in isolation, and all 2,613 tests pass in the full run. Recorded as an earlier-environment/flaky observation, not a current blocker.
- The brief's `npm audit` registry-resolution failure was environmental and transient: the scan resolves now (0 production vulnerabilities, no high/critical). No config or dependency was changed.
- The `git diff origin/main...HEAD` is empty for this pass because the earlier attempt's fix is already merged; the only file added by this pass is these notes.
- Optional (senior React, non-blocking) — in the empty-queue path the `role="status"` live region mounts already carrying its text, which some screen readers announce less reliably than populating a stable region after mount. Hardening would keep one page-level live region mounted at all times. No functional or coverage failure; not changed here (out of the story's minimal scope).
- Optional (senior React and code reviewer, non-blocking) — if the list `refetch()` fails after a successful command, the success notice and the blocking error can be shown together (`CollectionDeskPage.tsx:109,118,140,165,168`). The notice is factually correct (the command committed) but a future story could gate the detached notice on `result.status !== 'error'`. No test covers this path; not changed here.
- Optional (code reviewer, non-blocking) — the "no stale notice on failure" conflict assertions start from an initially empty `statusMessage`, so `not.toContain(...)` is trivially true and does not prove a prior success notice is cleared by a later failure. Production code does clear it; a future hardening test could drive success then failure.
- Optional (test engineer, non-blocking) — branches (a)/(b) of `DetachedCollectionStatus` rely on implicit absence (`getByTestId` would throw on duplicates) rather than an explicit `queryByTestId(...) === null`; the dispensing test asserts URL/body but not `If-Match`/`Idempotency-Key` (the accept test does, and `api.ts` sends them). None is a coverage or behavior gap.

## Lessons for workers

- Before treating `bash apps/staff-web/check.sh` as the gate for a staff-web story, run its steps in order and inspect the first failure: a stale generated client or unrelated coverage debt can stop it before the code you touched is measured. Run the individual stages (`npm run typecheck`, `lint`, `build`, `node scripts/check-bundle-size.mjs`, `npm run test:coverage`) and read `coverage/coverage-summary.json` for your own files.
- A pre-existing failure recorded in a task's `pending:` may disappear after main advances (fix merged, flake resolved). Re-run it in isolation and record the current result instead of repeating the earlier diagnosis.
- `git status --short` plus `git rev-parse HEAD origin/main` quickly distinguish "fix not applied" from "resume of an already-merged fix"; do not re-implement a merged fix.
- `grep -c` returning 0 gives exit code 1 and stops an `&&` chain — separate the verification command from the next one.

## Why this is not finished

The story's production fix and its tests are complete, green and at 100% coverage for every touched file. The full module gate `bash apps/staff-web/check.sh` is **not** green at current `origin/main`, for two pre-existing out-of-scope reasons documented above: (1) `gen:api:check` fails on contract drift introduced by SMSG-04 (client regeneration is assigned to existing tasks), and (2) `npm run test:coverage` fails the unchanged 100% per-file threshold on 19 unrelated source files (owned by FIX-FRONTEND-GATES). Per swarm rules this worker did not edit either area.

Command and final lines:
- `bash check.sh` → `✘ Generated types are not up-to-date!` (exit 1).
- `npm run test:coverage` → all 2,613 tests pass, but 48 `ERROR: Coverage ... does not meet global threshold (100%)` lines across 19 files (exit 1).

Most likely cause: main advanced with contract and coverage debt after the first attempt's evidence was captured. What was tried: isolated the Facilities test (passes), ran every gate stage individually (typecheck/lint/build/bundle pass), confirmed both touched files at 100%, confirmed npm audit resolves. Exact next step: the integrator should let the owning tasks regenerate the staff-web OpenAPI client (SMSG-04 routes) and close the 19-file coverage debt, then rerun `bash apps/staff-web/check.sh` on the exact merge SHA.
