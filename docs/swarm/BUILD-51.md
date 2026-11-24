# BUILD-51 · Staff web: tenant export and onboarding import screens

PR #766, tested branch tip e04554d03e641864828c1cac4e81581cdc4af1e3.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (TypeScript/frontend and gate lines):

```text
python coverage: WARNING coverage is not installed; the tests ran without the coverage check
FAIL unreferenced-service: apps/api/src/main/java/com/x/NewService.java
FAIL unreferenced-service: apps/api/src/main/java/com/x/OrphanService.java
FAIL unreferenced-service: apps/api/src/main/java/com/synthetic/OrphanService.java
ERROR: test_maven_failure_with_failing_reports_still_fails (test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails)
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_worker_gate_coverage.py", line 554, in test_maven_failure_with_failing_reports_still_fails
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_worker_gate_coverage.py", line 521, in run_main
```

Task: BUILD-51

Swarm worker 2. Local checks: failed. Agent result: ok.

# BUILD-51 · Staff web: tenant export and onboarding import screens

Task: BUILD-51
Requirements: OPS-07, OPS-08
Scope: apps/staff-web/src/features/dataexchange
Branch: swarm/BUILD-51-959 (no push, no tag, no `gh`)

## Requirements and acceptance mapping

- **OPS-07** (PROJECT_V1_SPEC.md line 140): tenant export with manifest, schema
  version, checksums, authorized documents, provenance and audit history. This
  slice renders the export request, the expiring download list with manifest
  checksum, record count and retention instant, and an explicit expired state
  that withholds the download action.
- **OPS-08** (PROJECT_V1_SPEC.md line 141): versioned onboarding manifest,
  deterministic mapping, dry-run counts / unsupported fields / duplicate risk /
  missing attachments / errors, provenance preservation and authorized sign-off.
  This slice builds a synthetic versioned manifest, renders the whole dry-run
  report and provides a named sign-off step bound to the reviewed manifest.
- **Acceptance 1 — expired export shown as expired**: `ExportPage.test.tsx`
  "shows an expired export as expired and withholds its download action"
  (clock injected after `expires_at`); pure rule in `exportState`.
- **Acceptance 2 — dry-run report lists unsupported fields**:
  `ImportPage.test.tsx` "lists unsupported fields and the other findings in the
  dry-run report" asserts `legacy_ssn` and `free_text_notes` in the dedicated list.
- **Acceptance 3 — forbidden state**: `ExportPage.test.tsx` / `ImportPage.test.tsx`
  "conceals the screen from a role without import/export permission" plus the
  API-403 tests that assert the shared `ForbiddenState`.
- **Only permitted roles**: export is `tenant_admin` only (matches API
  `ExportPermissionGuard`); import review and sign-off are `tenant_admin` and
  `privacy_auditor` (matches `ControlledImportService.AUTHORIZED_ROLES`). The role
  comes from `useRole()`/props, never from a form or the URL.

## Contract finding

The story says BUILD-41/BUILD-43 added these APIs to `contracts/openapi.yaml`.
At this checkout the routes exist only as Spring controllers
(`/api/v1/tenant/exports*`, `/api/v1/tenant/imports/*`); they are absent from
`contracts/openapi.yaml` and the generated `openapi.d.ts`. Scope forbids editing
either, so the feature follows the established `features/admin/api.ts` pattern:
`buildRequestDescriptor` supplies tenant/correlation/idempotency metadata from a
declared contract path and the `url` is bound to the live route, with locally
declared response types mirroring the Java records. See Findings and
`task-requests`.

## Entry-point-to-test trace

Routes `/operations/exports` and `/operations/imports` (self-registering
`routes.ts`, covered by `routes.test.tsx`) -> lazy `ExportPage` / `ImportPage` ->
`api.ts` (`startExport`, `downloadManifest`, `runDryRun`, `submitSignOff`) via
`api/transport.send` (mocked in tests) -> assertions in `ExportPage.test.tsx`,
`ImportPage.test.tsx`, `api.test.ts`, `domain.test.ts`, `labels.test.ts`.

New production files: `domain.ts`, `api.ts`, `labels.ts`, `ExportPage.tsx`,
`ImportPage.tsx`, `routes.ts`. No existing file was changed. No dependency added.

## Test evidence

All commands run from `apps/staff-web` unless noted. Final lines / results:

- `bash apps/staff-web/check.sh` (from repo root) — EXIT 0:
  `Test Files  164 passed (164)`, `Tests  1940 passed (1940)`,
  `No files with missing coverage. 213 files fully covered. 4 empty files skipped.`
  Coverage summary: `Statements 100% (3880/3880)`, `Branches 100% (1964/1964)`,
  `Functions 100% (1315/1315)`, `Lines 100% (3703/3703)`.
  The stack traces in that output come from the pre-existing
  `tests/theme/ThemeProvider.test.tsx` negative test ("useTheme must be used
  inside ThemeProvider") and did not fail the run.
- `npm run gen:api:check` — `gen:api: generated client matches the contract` (EXIT 0).
- `npm run typecheck` — final lines:
  `> aurevia-staff-web@0.1.0 typecheck`
  `> tsc --noEmit -p tsconfig.json`
  EXIT 0, no diagnostics. No `error TS…`, no `any`, no `@ts-ignore`.
- `npm run lint` — `eslint . --max-warnings 0`, EXIT 0.
- `npm run build` — `✓ built in 158ms`, EXIT 0.
- `node scripts/check-bundle-size.mjs` — all chunks `ok`, EXIT 0 (new
  `labels-*.js` chunks are 3068 B and 2458 B, within budget).
- `npx vitest run src/features/dataexchange` — `Test Files 6 passed (6)`,
  `Tests 49 passed (49)` (the parity test was split into `labels.test.ts`).
- Fast repository policy stages (full `scripts/check.sh` was intentionally not
  run because it invokes `apps/api/check.sh`, i.e. the forbidden full Maven
  verify): `python3 -I scripts/check-names.py` EXIT 0; `python3 -I
  scripts/check-docs.py` EXIT 0; `python3 -I scripts/check-secrets.py` EXIT 0;
  `bash scripts/frontend-check.sh` — `frontend standards: policy and tool
  commands passed`, EXIT 0.
- `git merge --no-edit origin/main` at start and after implementation:
  `Already up to date.` No new conflict markers in any file under the scope.

Evidence source: executed tool commands only. No reviewer, simulated user or
human participant evidence was used for this story.

## Coverage

Command: `npx vitest run src/features/dataexchange --coverage
--coverage.include='src/features/dataexchange/**'` -> `No files with missing
coverage. 6 files fully covered.` Statements 159/159, Branches 72/72, Functions
57/57, Lines 148/148, all 100%. The full-gate coverage above also confirms 100%
on every file in the module. Every new branch and failure path has a named test:

- `domain.ts` `canStartExport` / `canReviewImport` `.includes` true/false ->
  `domain.test.ts` "permits only a tenant admin…" and "permits an admin and a
  privacy auditor…".
- `exportState` invalid expiry -> "fails closed on an unreadable expiry instant";
  invalid current instant -> "fails closed on an unreadable current instant";
  `now >= expiry` -> "returns expired…"; before expiry -> "returns active…".
- `toExportListItem` both states -> "projects an API result into a list row…".
- `toDryRunView` full report and `counts?`/`?? []` defaults -> "keeps the
  unsupported fields…" and "defaults every missing finding…".
- `signOffRejection` blank approver / unauthorised role / rejected decision /
  accepted -> the four tests under "sign-off validation".
- `api.ts` `descriptor` POST (idempotency key) vs GET -> "starts an export…"
  and "downloads a manifest using an encoded job identifier"; POST with body ->
  "runs a dry run…" and "submits a sign-off…". `manifestFromDraft` filter
  keep/drop -> "trims and keeps named unsupported fields…" and "drops blank
  field names…". `asDataExchangeError` transport vs unknown -> two tests.
- `ExportPage.tsx` `role ?? sessionRole` and `now ?? new Date()` both sides ->
  "uses the session role and current clock when no story overrides are
  supplied" plus every explicit-prop test. `canStartExport` false -> "conceals
  the screen…". `status 
