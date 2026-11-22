# CLN-07 clause 1 — link original PDFs and images to chart, order or result as authorized

## Clause

Link original PDFs and images to chart, order or result as authorized.

## Status

Proven by tests

## Evidence

Active proof (synthetic fixtures only, no database and no real patient data):

- Test class: `com.aurevia.smartclinic.documents.clauses.LinkOriginalPdfsImagesChartOrderResultTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/LinkOriginalPdfsImagesChartOrderResultTest.java`).
  It mirrors the neighbouring `DocumentServiceSubjectLinkTest` fixture: a Mockito
  `DocumentRepository`, a directly invoked `TransactionTemplate` callback and
  `TenantContext.set`/`clear` around each test.
- `linkAttachesOriginalToChartWhenActorIsClinician` — positive proof. With an active
  membership, the `clinician` role and `chartExists` true, `link` inserts one original,
  appends the `link` history event, writes a `SUCCESS` audit row and returns the
  association id.
- `linkAttachesOriginalPngImageToChart` — positive proof. An authorized actor attaches an
  `image/png` original and the test verifies that the bounded media type and file name reach
  `DocumentRepository.insert` unchanged; this proves the "images" part of the clause, the
  "PDFs" part being proved by the chart/order/result cases which use `application/pdf`.
- `linkAttachesOriginalToOrderAndToResult` — positive proof. The same authorized actor
  attaches an original to an `order` and to a `result`; the repository insert is verified
  for each subject type and both calls append a `link` event.
- `linkRefusesActorWithNoActiveMembership` — negative proof. `hasActiveMembership=false`
  yields HTTP 403 `FORBIDDEN` ("no active membership"), a `DENIED` audit row keyed by the
  subject id, and no insert, history event or `SUCCESS` audit.
- `linkRefusesRoleWithoutLinkPermission` — negative proof. An active member with the
  `patient` role has no `DOCUMENT_LINK` permission and is refused with HTTP 403
  `FORBIDDEN` ("missing document permission"), plus a `DENIED` audit and no write. This
  proves the "as authorized" part rather than mere membership.
- `linkRefusesUnknownChart` — negative proof. `chartExists=false` yields HTTP 404
  `NOT_FOUND` before any insert, so an original cannot be orphaned on a chart the actor
  cannot see.

Production classes exercised by the proof:

- `com.aurevia.smartclinic.documents.DocumentService` — `link()` (input validation,
  chart gate, insert, history event, audit) and `authorize()` (active-membership and
  role gates, both with `DENIED` audits).
- `com.aurevia.smartclinic.documents.DocumentPolicy` — `DOCUMENT_LINK` action matrix;
  `clinician` and `reception` are allowed, other roles are not.
- `com.aurevia.smartclinic.documents.DocumentInputs` — bounded subject types
  (`chart`, `order`, `result`), media types (PDF/JPEG/PNG), size and rotation validation.
- `com.aurevia.smartclinic.documents.DocumentRepository` — `hasActiveMembership`,
  `activeRoles`, `chartExists` and `insert` (the RLS-bounded SQL boundary). `subjectExists`
  is invoked inside the store's real `insert`, which is mocked here, not by `link()` itself.
- `com.aurevia.smartclinic.documents.DocumentController` — thin `POST
  /api/v1/documents/associations` adapter that receives the service by injection.
- `db/migrations/V17__document_associations.sql` — association and event tables with
  `ENABLE`/`FORCE ROW LEVEL SECURITY`, tenant policies, a SHA-256 trigger over the retained
  bytes and an immutable-update guard; `db/migrations/V75__build_06.sql` adds the
  verified-content function used by download.

The thin HTTP adapter `DocumentController.link()` is unit-covered by
`apps/api/src/test/java/com/aurevia/smartclinic/it/documents/DocumentControllerTest.java`
(`linkReturns201WithALocation`, with a mocked service). The link POST route is also driven
over real HTTP by `@Tag("integration")` `@SpringBootTest(RANDOM_PORT)` helpers in
`DocumentDownloadHttpIntegrationTest.link(...)` (asserts 201),
`it/documents/DocumentIsolationTest.link(...)` (asserts 201),
`tenancy/clauses/IsolationAcrossSurfacesTest.linkFile(...)` (asserts 201) and
`acceptance/platform/PlatformAcceptanceTest.linkFile(...)` (asserts 201). None of those is
dedicated to this clause's membership/role refusal assertions, which the service-level test
above supplies; this clause's proof therefore stays at the service layer where the
authorization decision lives. `DocumentArtifactControllerHttpIntegrationTest` is a separate
real HTTP test for the X-03 `/api/v1/documents/artifacts/...` commands, not this route.
Two remaining `link()` branches — insert returning zero rows (404 subject not found) and a
`DuplicateKeyException` (409 conflict) — are covered by
`apps/api/src/test/java/com/aurevia/smartclinic/documents/DocumentServiceSubjectLinkTest.java`
and `apps/api/src/test/java/com/aurevia/smartclinic/it/documents/DocumentServiceTest.java`.
No production code, existing test, migration or contract was changed by this evidence task.

## Gap

None for the clause behaviour itself: `link` plus `authorize` are fully implemented and
proven active above, so no test is disabled.

One adjacent contract gap remains, recorded here rather than edited because this task
forbids contract changes. `POST /api/v1/documents/associations` is still listed at
`contracts/openapi/missing-routes.txt:198` and is not yet published in `contracts/openapi.yaml`.
Closing it requires one contract-and-client task: add the operation to `contracts/openapi.yaml`
with its tag and route-parity entry, declaring only headers the controller actually accepts
(`DocumentController.link()` binds no `Idempotency-Key`; only the void route binds `If-Match`),
or pair such a header with a controller change under a separate task; regenerate
`apps/staff-web/src/api/generated/openapi.d.ts` and `contracts/openapi/generated/openapi.yaml`;
and remove the baseline entry from `contracts/openapi/missing-routes.txt`. That work is
already covered by the open `FIX-OPENAPI-04` task ("Publish every remaining route and empty
the OpenAPI missing-route baseline", WEB-05), so no new task is requested.
