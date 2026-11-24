# BUILD-48 · Staff web: duplicate patient review worklist with dry run and second approval

PR #309, tested branch tip f98db566aa317b40fdf68edd1206cc3c243cc3e9.

Task: BUILD-48

Swarm worker 6. Local checks: passed. Agent result: ok.

# BUILD-48 — Staff web: duplicate patient review worklist with dry run and second approval

## Summary

Implemented the duplicate-review worklist, side-by-side comparison, dry run and
second-reviewer approval inside `apps/staff-web/src/features/duplicates` on branch
`swarm/BUILD-48-419` (start SHA `b1445c7`). Synthetic data only.

- `domain.ts` — wire types mirroring the Java consolidation records, plus pure
  guards: `hasUnresolvedBlockers`, `approvalBlocker` (no ticket / same reviewer /
  unresolved dependency / clear), IAM-04 confirmation validation, projections,
  local form validation and the approval body builder.
- `messages.ts` — parity-checked en/ar dictionary; machine-code → message mapping
  (`SECOND_REVIEWER_REQUIRED`, `UNRESOLVED_DEPENDENCY`, generic fallback).
- `api.ts` — tenant-scoped descriptors for `GET /api/v1/duplicate-reviews`,
  `GET /{survivorId}/{duplicateId}`, `POST` open and `POST /{reviewId}/consolidate`
  with `If-Match` = duplicate patient version and a fresh `Idempotency-Key` per write.
- `DuplicatesScreen.tsx` — accessible worklist table, side-by-side identity cards,
  conflicting-demographics table, dependency states, dry run, and the open/approve
  panel (two identifier confirmations or one bounded alternate-process code).
- `DuplicatesPage.tsx` — container: the seven WEB-09 states via `ApiPage`, pair
  selection, open-ticket command, second-reviewer approval, in-flight guards and
  conflict/refusal handling. Approval eligibility is advisory in the UI; the API
  remains authoritative.

No new dependency, no migration, no change outside the declared scope. No push,
tag or `gh` call.

## Affected requirements

- **IAM-04** — same-tenant duplicate review worklist; both identities with
  conflicting demographics and dependencies; non-mutating dry run; a second
  authorized reviewer approves and the opener is refused; two issuer-scoped
  identifiers (or a documented approved alternate process) confirmed before commit;
  unresolved ambiguous dependencies block approval; original ids/signatures and
  source references are untouched by this UI slice.
- **WEB-09** — loading, empty, error (with correlation id), timeout/offline,
  forbidden, stale-version conflict and session-expired states; typed work is
  retained on validation failure and conflict; a global error boundary remains
  owned by the app.

Acceptance mapping:

| Story step | Implementation | Test |
|---|---|---|
| 1. Read the consolidation API | `domain.ts` wire types from `ConsolidationController`/`ReviewDetail`/`DuplicateReview`/`Consolidation`/`Patient`; `api.ts` descriptors (the routes are not in `contracts/openapi.yaml`, see Findings) | `api.test.ts` URL/method/header/body assertions |
| 2. Worklist, side-by-side, conflicts, dependencies, dry run | `WorklistScreen`, `ReviewScreen`, `DuplicatesPage` | `DuplicatesScreen.test.tsx`, `DuplicatesPage.test.tsx` (`reaches the side-by-side view and dry run through the worklist`) |
| 3. Second reviewer approves; first reviewer refused | `approvalBlocker`, `ConfirmationIssue` validation, `handleApprove`, `If-Match` on duplicate version | `domain.test.ts`, `DuplicatesPage.test.tsx` (`refuses the reviewer who opened the request…`, `approves with the duplicate version…`) |
| 4. Unresolved conflicts block, same reviewer refused, en/ar | `hasUnresolvedBlockers`, code→message mapping, `messages.ts` | `domain.test.ts`, `messages.test.ts`, `DuplicatesPage.test.tsx`, RTL tests in both screen test files |

## Risk class

Cross-boundary (staff web ↔ consolidation API) and safety-sensitive for patient
identity. The screen never merges records itself, never invents identifiers, shows
the opaque id beside the name, isolates mixed-script runs with `MixedScriptText`,
and leaves authorization, locking and the all-or-nothing commit to the server.
All evidence is synthetic; no clinical or human acceptance is claimed.

## Review owner

Senior React/Vite and Senior TypeScript specialists (bounded static reviews — see
Findings). Integrator and Delivery own final acceptance.

## Data changes

None. No Flyway migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

- Tenant comes only from `useActiveOrganization()` and is sent as
  `X-Active-Organization` by the shared builder; organization and actor are never
  taken from the request body or URL.
- Writes carry a fresh `crypto.randomUUID()` idempotency key per user action and
  a synchronous in-flight `useRef` guard; the approve command also carries
  `If-Match` = `ReviewDetail.duplicate.version` (the version the server compares).
- 403 → `ForbiddenState`; 409/412 → conflict state plus a code-specific refusal;
  401 → session-expired. No new audit event is added by this UI slice; the server
  owns `DUPLICATE_WORKLIST`, `DUPLICATE_REVIEW`, `CONSOLIDATE` and identifier
  confirmation audits.
- No name, MRN, reason or identifier appears in a URL, page title, log or browser
  storage. Routes (owned by BUILD-52) carry opaque ids only.

## Migration and rollout considerations

None. No route was added in this story (see Findings): route registration and
navigation are BUILD-52's scope, so no `routes.ts` was created and no app-level
route-registry test was disturbed.

## Test evidence

Commands executed, with final lines:

- TDD red: `npx vitest run src/features/duplicates/domain.test.ts` → failed to load
  config before dependencies were installed; after `npm ci`, the two red tests
  failed to resolve `./domain` and `./fixtures` (the expected missing modules).
- Feature green: `npx vitest run src/features/duplicates/` →
  `Test Files  5 passed (5)`, `Tests  85 passed (85)`.
- Canonical frontend gate: `bash apps/staff-web/check.sh` → **exit 0**;
  `gen:api: generated client matches the contract`; build and WEB-08 bundle-size
  gate passed (all chunks `ok`); `Test Files  63 passed (63)`;
  `Tests  826 passed (826)`; `No files with missing coverage.`;
  `101 files fully covered.`
- `npm run typecheck` (`tsc --noEmit -p tsconfig.json`) final lines:
  ```
  > aurevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  Exit 0; no `error TS…`.
- `npm run lint` (`eslint . --max-warnings 0`) → exit 0 (part of `check.sh`).
- `python3 -I scripts/check-names.py`, `python3 -I scripts/check-secrets.py`,
  `python3 -I scripts/check-wiring.py` → all exit 0 (wiring printed only the two
  pre-existing "fixed; delete from baseline" debt notes).
- Not run: repository-root `scripts/check.sh`, because it invokes `mvn verify`
  with every integration test, which this worker is forbidden to run. The
  mandated `bash apps/staff-web/check.sh` was run and passed.

Entry-point-to-test trace: `DuplicatesPage` → `ApiPage` → `api.ts` descriptors →
mocked `send()` → `WorklistScreen` row action → `ReviewPanel` →
`getReviewDetail` → `ReviewWorkflow` → `openReview`/`approveConsolidation` →
`ReviewScreen`/`ApprovalPanel` assertions. Named tests prove the trace:
`reaches the side-by-side view and dry run through the worklist`,
`opens a review ticket and shows who opened it`,
`refuses the reviewer who opened the request without sending an approval`,
`blocks approval while an ambiguous dependency is unresolved`,
`approves with the duplicate version, a fresh idempotency key and the confirmation body`.

## Coverage

Command: `bash apps/staff-web/check.sh` (its `npm run test:coverage` step).
Result: `Test Files 63 passed (63)`, `Tests 826 passed (826)`,
`Statements 100% (2032/2032)`, `Branches 100% (1087/1087)`,
`Functions 100% (666/666)`, `Lines 100% (1945/1945)`;
`No files with missing coverage.` / `101 files fully covered.`

Every new branch and the test that takes it:

`domain.ts`
- `hasUnresolvedBlockers` `ambiguous && !resolved` true → `blocks only on an ambiguous dependency that is not resolved`; resolved true → `does not block on a resolved ambiguous dependency`; ambiguous false → `d
