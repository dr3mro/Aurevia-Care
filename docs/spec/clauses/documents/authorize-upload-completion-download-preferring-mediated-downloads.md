# ARC-06 clause 3 — authorize upload completion and mediated downloads

## Clause

Authorize upload completion and each download, preferring mediated downloads for clinical artifacts.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.documents.clauses.AuthorizeUploadCompletionDownloadPreferringMediatedDownloadsTest`
(`apps/api/src/test/java/com/aurevia/documents/clauses/AuthorizeUploadCompletionDownloadPreferringMediatedDownloadsTest.java`).
It is a synthetic, database-free clause test in the same `clauses` style as
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: the real
`DocumentArtifactController` is constructed with the real `QuarantineService` and
`DocumentGuard` over a mocked `DocumentStore`, a real `PdfBindingService`, a
mocked fresh-role source and a transaction template that runs the guarded
callback synchronously. `TenantContext` supplies the organization and subject;
roles are never taken from a request body. Every case enters through
`DocumentArtifactController.complete` or `DocumentArtifactController.download`,
the commands behind `POST /api/v1/documents/artifacts/{id}/complete` and
`GET /api/v1/documents/artifacts/{id}/download`. All identifiers, subjects and
payload metadata are synthetic.

Completion authorization (15 synthetic tests in total):

- `completeAcceptsACleanVerifiedArtifactForAReviewer` — a `document_reviewer`
  completes a clean artifact whose private object verifies; state advances to
  `COMPLETED`, version increments, the compare-and-set writes the exact
  projected artifact and one `UPLOAD_COMPLETED` fact is appended (rule met).
- `completeIsDeniedForARoleWithoutCompletionPermission` — a `pdf_renderer` gets
  403 `FORBIDDEN` even though the object would verify; an `ACCESS_DENIED` fact
  is recorded and no object read, compare-and-set or completion fact occurs
  (negative: wrong role).
- `completeRejectsAStaleVersion` — 412 `STALE_VERSION` before any object access
  or write (negative: stale precondition).
- `completeRefusesAnArtifactThatIsNotClean` — 409 `NOT_CLEAN` for a quarantined
  artifact (negative: forbidden state).
- `completeRefusesAnUnverifiedPrivateObject` — 409 `OBJECT_UNVERIFIED` and no
  completion fact when the immutable object version or checksum does not match.
- `completeRejectsALostCompareAndSet` — a deterministic false return from
  `replaceArtifactIfVersion` yields 412 `STALE_VERSION` with no success fact,
  so a competing write cannot overwrite committed state.

Download authorization and mediation:

- `downloadReturnsAMetadataOnlyMediatedHandleForAnAuthorizedReviewer` — an
  authorized reviewer receives a `MediatedDownload` carrying exactly the five
  fields `objectKey`, `objectVersion`, `checksum`, `contentType` and `sizeBytes`;
  the file name does not appear in its string form and one `DOWNLOADED` fact is
  appended (mediation met).
- `downloadIsDeniedForARoleWithoutDownloadPermission` — a `pdf_renderer` gets
  403 `FORBIDDEN` with an `ACCESS_DENIED` fact and no object access.
- `downloadConcealsAnArtifactOwnedByAnotherTenant` — an ORG_B actor who does
  hold a download-permitting role gets 404 `NOT_FOUND` from the tenant-scoped
  store lookup; no ORG_A lookup, no object access and no fact is appended, so
  the ledger cannot be used as an existence probe (negative: wrong tenant,
  fail closed).
- `downloadRefusesAnArtifactThatIsNotDownloadable` — 409 `NOT_COMPLETED`.
- `downloadRejectsAStaleVersion` — 412 `STALE_VERSION` before the object is read.
- `downloadRefusesAnUnverifiedPrivateObject` — 409 `OBJECT_UNVERIFIED` and no
  `DOWNLOADED` fact.
- `completionAndDownloadFailClosedForAPatientRoleActor` — the controller
  refuses both commands with 403 `FORBIDDEN` for a `patient` role before the
  service runs and records no fact, because a patient actor here has no
  verified patient link.
- `completionAndDownloadRequireAnAuthenticatedTenantContext` — both commands
  fail 401 `UNAUTHENTICATED` with no role lookup or fact when no context is bound.
- `downloadRequiresAVersionPrecondition` — a missing `If-Match` is refused with
  428 `PRECONDITION_REQUIRED` before the artifact is read.

Production classes and files surveyed and exercised by these entry points:

- `com.aurevia.documents.DocumentArtifactController` —
  `complete` (lines 109–113), `download` (136–140) and actor derivation (238–262):
  organization and subject come from the bound tenant context, roles from
  current active membership inside the tenant-guarded transaction, and the
  patient role fails closed.
- `com.aurevia.documents.QuarantineService` — `complete` (136–149)
  and `download` (187–201): authorize, tenant-scoped load, version precondition,
  state guard, object verification, compare-and-set and append-only facts;
  `verifyObject` (249–255), `requireVersion` (257–261) and `replace` (263–267).
- `com.aurevia.documents.DocumentGuard` — `authorize`, `deny`,
  `conflict`, `stale`, `notFound` and `record`: one policy table for denials with
  durable, non-sensitive `ACCESS_DENIED` facts.
- `com.aurevia.documents.DocumentActions` — `COMPLETE` and
  `DOWNLOAD` grants; `document_reviewer` may do both, `pdf_renderer` neither,
  and `clinician` may download.
- `com.aurevia.documents.DocumentContract` — `Actor`, `Artifact`,
  `ArtifactState.downloadable()`, `CompleteRequest`, `MediatedDownload` (656–669,
  no payload, file name or URL), `DocumentEvent` and `EventType`
  (`UPLOAD_COMPLETED`, `DOWNLOADED`, `ACCESS_DENIED`).
- `com.aurevia.documents.DocumentStore` / `JdbcDocumentStore` —
  tenant-scoped `artifact`, `verifyObject`, `replaceArtifactIfVersion` and
  `appendEvent`; `com.aurevia.api.IfMatch` — 428/422 precondition
  parsing (the 412 is raised by `requireVersion` and `replace` above).

Broader evidence already present (not repeated here):
`documents/DocumentArtifactTest` covers the same domain branches without HTTP
(`completionRequiresCleanStateAnAuthorizedRoleAndFreshVersion`,
`mediatedDownloadRequiresCompletedCleanAndAuthorizedParticipant`,
`downloadHandleAndFactsCarryNoPayloadFileNameOrUrl`,
`completionAndDownloadVerifyTheObjectBoundary`), and
`documents/DocumentArtifactControllerHttpIntegrationTest` drives both routes
through the real controller and PostgreSQL (reviewer completion and clinician
download with `If-Match: 3`); the same test class also covers 404 cross-tenant
concealment, wrong-role denial, a missing precondition and `NOT_COMPLETED` on
the neighbouring `/{id}` view and `/scan` routes.
`documents/it/JdbcDocumentStoreIntegrationTest`
covers object verification and cross-tenant reads against real RLS.
`db/migrations/V81__document_store.sql` creates the tenant tables with FORCE ROW
LEVEL SECURITY and the append-only `document_event` ledger; there is no payload
column on the mediated handle. The twelve artifact routes, including these two,
are currently baselined in `contracts/openapi.yaml` by the
`FIX-OPENAPI-01` gate rather than described in `contracts/openapi.yaml`;
publishing the remaining baselined routes is follow-up contract work under
`FIX-OPENAPI-04` (after `FIX-OPENAPI-02` and `FIX-OPENAPI-03`). This is
contract-documentation debt, not a behaviour gap.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AuthorizeUploadCompletionDownloadPreferringMediatedDownloadsTest test`
— `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
