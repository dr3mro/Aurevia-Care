# IAM-04 clause 8 — API test evidence

## Clause

Use an audited support workflow to correct erroneous consolidation.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.patient.clauses.UseAuditedSupportWorkflowCorrectErroneousConsolidationTest`
(path: `apps/api/src/test/java/com/aurevia/patient/clauses/UseAuditedSupportWorkflowCorrectErroneousConsolidationTest.java`).
All five tests are active (no `@Disabled`) and pass with synthetic UUID fixtures only.
Each request goes through the real HTTP entry point
`POST /api/v1/duplicate-reviews/{reviewId}/correct` in
`ConsolidationController`, backed by the real `ConsolidationService` and
`DuplicatePolicy`; only the RLS-bounded `DuplicateRepository` is a synthetic
Mockito boundary.

- `correctionAppendsAuditedVersionReversesVisibilityAndPreservesOriginal` — positive proof.
  A `tenant_admin` support actor corrects a version-1 `CONSOLIDATED` review with
  `If-Match: "1"`. HTTP 200 returns a version-2 `CORRECTED` event carrying the
  correction reason, the support actor as approver and the original approver and
  consolidation reason as preserved provenance. The original event object still has
  status `CONSOLIDATED` and no correction reason (append-only history). Both records
  are locked, a `REVERSED` chart association restores visibility with the duplicate's
  snapshotted version and preserved MRN/issuer, a `CORRECTED` consolidation event is
  appended, and one `CONSOLIDATION_CORRECT` / `SUCCESS` audit event is written for the
  active tenant and actor.
- `correctionViaApiDeniesNonAdminRolesAndAuditsTheRefusal` — negative proof of the
  support-role boundary. `privacy_auditor` (allowed to review and approve) and
  `reception` both receive HTTP 403; no lock, no association, no consolidation event,
  and a `CONSOLIDATION_CORRECT` / `DENIED` audit is written for each refusal.
- `correctionViaApiRefusesStaleVersionWithoutAppending` — negative proof of the version
  precondition. `If-Match: "2"` against a version-1 event gives HTTP 412
  `STALE_VERSION`, a `STALE` audit and no writes.
- `correctionViaApiRefusesAnAlreadyCorrectedConsolidation` — negative proof of the
  forbidden state. A version-2 `CORRECTED` event gives HTTP 409 `CONFLICT`, a
  `CONFLICT` audit and no writes, so a second correction cannot append a second
  reversal.
- `correctionViaApiRefusesUnknownConsolidationAndIncompleteInput` — negative proof of
  missing input and missing state. Unknown review gives HTTP 404 `NOT_FOUND` with a
  `NOT_FOUND` audit; a blank `If-Match` gives HTTP 428 `PRECONDITION_REQUIRED`; a blank
  reason gives HTTP 422 `INVALID_REASON`; none appends a write.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.patient.consolidation.ConsolidationController` — thin
  adapter binding `POST /{reviewId}/correct`, `If-Match` and `ReasonRequest`; it
  injects the service and maps domain failures through
  `com.aurevia.api.ApiErrorHandler`.
- `com.aurevia.patient.consolidation.ConsolidationService.correct` — the
  audited support workflow: admin-only authorization, current-version precondition,
  `CONSOLIDATED` state check, pair lock, post-lock patient read, append of a `REVERSED`
  association and a `CORRECTED` event, and `CONSOLIDATION_CORRECT` audit.
- `com.aurevia.patient.consolidation.DuplicatePolicy` — `CORRECT` is
  granted only to `tenant_admin` (reviewers and reception are refused).
- `com.aurevia.patient.consolidation.Consolidation` — immutable event
  whose prior version, approver and reason survive a later correction.
- `com.aurevia.patient.consolidation.DuplicateRepository` — RLS-bounded
  append operations (`lockPair`, `insertAssociation`, `insertConsolidation`,
  `appendAudit`) mocked at the boundary; the migration-backed implementation is in
  `db/migrations` and is proven by the real-database HTTP suite below.
- `com.aurevia.api.IfMatch`, `com.aurevia.tenant.TenantContext`
  and `com.aurevia.web.TenantContextInterceptor` — precondition parsing
  and per-request tenant binding used by the entry point.

Source survey (found, not changed by this task): the implementation lives in
`apps/api/src/main/java/com/aurevia/patient/consolidation/`
(`ConsolidationService.correct`, `ConsolidationController.correct`,
`DuplicatePolicy.CORRECT`, `Consolidation`, `DuplicateReview`, `ReviewDetail`,
`DuplicateRepository.insertAssociation` / `insertConsolidation` / `appendAudit`),
with neighbouring identity code in `patient/identity/` (`IdentityProofingService`,
`IdentityProofingController`, `ChartLink`, `IdentityActionPolicy`). The real-PostgreSQL
HTTP-level acceptance already exists in
`apps/api/src/test/java/com/aurevia/it/patientidentity/ConsolidationIsolationTest.java`
(`correctionReversesVisibilityAndAuditsAndHistoryIsImmutable`) and
`ConsolidationServiceTest` covers the unit branches of `correct`. The append-only
association and consolidation tables are created by Flyway migration
`db/migrations/V9__duplicate_review_consolidation.sql`. None of these files was
modified by this proof-only task.

## Gap

The clause's behavior is implemented and proven by active tests, but its public
route is not yet in the API contract:
`POST /api/v1/duplicate-reviews/{reviewId}/correct` is listed in
`contracts/openapi.yaml` (line 202), the baseline of Spring routes
not yet documented in `contracts/openapi.yaml`; a grep of `contracts/openapi.yaml`
finds no `duplicate-reviews` path. Closing this contracts gap needs one separate
cross-boundary task: add the path, request and response schemas to
`contracts/openapi.yaml`, remove its `missing-routes.txt` line and regenerate the
staff-web OpenAPI types. That is outside this proof-only task's scope; the route
itself works end to end against a real database in `ConsolidationIsolationTest`.

No behavioral gap was found: the support workflow, role boundary, version
precondition, append-only history and audit events are all implemented and proven.
This task added proof only; it did not change production code, an existing test,
the OpenAPI contract or a migration.
