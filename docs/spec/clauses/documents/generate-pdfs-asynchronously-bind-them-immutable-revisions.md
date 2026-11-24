# ARC-06 clause 4 — API test evidence

## Clause

Generate PDFs asynchronously, bind them to immutable revisions, and prevent re-release with incomplete artifacts where a PDF is required.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.documents.clauses.GeneratePdfsAsynchronouslyBindThemImmutableRevisionsTest`
(`apps/api/src/test/java/com/aurevia/documents/clauses/GeneratePdfsAsynchronouslyBindThemImmutableRevisionsTest.java`),
a focused domain-service test over a tenant-scoped in-memory store, a fixed
clock and synthetic actors in two synthetic tenants:

- `asyncRenderBindAndReleaseWithRequiredPdfSucceeds` — a render job moves
  `REQUESTED → RENDERING → COMPLETE`; an identical re-request returns the
  original job while a different-language request starts a distinct job; the
  completed job binds to the exact revision (an identical re-bind returns the
  same binding), and a PDF-required release then succeeds and is idempotent;
  the render, bind and release facts are recorded.
- `renderIsRefusedWithoutThePdfRendererRole` — a `security_scanner` is refused
  with 403 `FORBIDDEN` for request, pickup, finish, bind and release, and the
  job stays `REQUESTED`.
- `foreignRevisionAndForeignJobAreRefused` — a revision from another tenant is
  refused with 403 `WRONG_TENANT`; a foreign render job is concealed with
  404 `NOT_FOUND` on bind and pickup.
- `staleJobVersionIsRefused` — a stale expected version is refused with
  412 `STALE_VERSION`, and re-finishing a completed job is refused with
  409 `INVALID_RENDER_STATE`.
- `incompleteOrUnverifiedPdfCannotBeBoundOrReleased` — binding a requested job
  and releasing without a binding are refused with 409 `PDF_INCOMPLETE`; after
  the backing object is removed the bound-but-unverified release is refused
  the same way and no release decision is recorded.
- `aCorrectedRevisionCannotReuseTheBindingOrASecondJob` — a corrected revision
  at the same version is refused with 409 `REVISION_MISMATCH` and 409
  `PDF_INCOMPLETE` while no prior release exists at that key; a second completed
  job for the same revision is refused with 409 `PDF_ALREADY_BOUND`; the
  corrected revision succeeds only with its own fresh job and binding.

Production classes exercised through their public commands:
`com.aurevia.documents.PdfBindingService` (requestRender,
pickupRender, finishRender, bind, release and the sameWork, sameRevision,
pdfReady and requireVersion decisions),
`com.aurevia.documents.DocumentGuard` (role and tenant checks and
audit facts), `com.aurevia.documents.DocumentActions` (role grants)
and `com.aurevia.documents.DocumentContract` (immutable commands and
states). The HTTP entry points are `DocumentArtifactController` routes
`POST /api/v1/documents/artifacts/renders`, `.../renders/{jobId}/pickup`,
`.../renders/{jobId}/finish`, `.../bindings` and `.../releases`, already covered
by `DocumentArtifactControllerHttpIntegrationTest.pdfCommandsAreReachableThroughTheController`
and `.pdfBindingIsImmutableAndReleaseRequiresTheExactRevision`. The store
backing the bean is confirmed JDBC by
`it/JdbcDocumentStoreIntegrationTest.productionBeansUseTheJdbcStore`, which also
exercises tenant isolation and immutability over the tables created in
`db/migrations/V81__document_store.sql` with row level security, forced row
level security and immutability triggers; it does not itself replay the render
sequence over JDBC. Two remaining clause-adjacent failure paths are evidenced by
the pre-existing `DocumentArtifactTest` A4 section: `pdfReady` version mismatch
(`:486-488`) and the competing-release `RELEASE_CONFLICT` (`:695-698`).

Verified locally:
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=GeneratePdfsAsynchronouslyBindThemImmutableRevisionsTest test`
  → EXIT=0 (6 tests, 0 failures, 0 skipped).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check`
  → EXIT=0, 0 findings (SpotBugs 4.10.5.0). OWASP/NVD is an integrator-only gate
  and no dependency changed in this change.

## Gap

None.
