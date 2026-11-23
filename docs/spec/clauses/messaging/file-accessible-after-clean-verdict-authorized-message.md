# MSG-03 clause 5 — API test evidence

## Clause

A file is accessible only after a clean verdict and authorized message publication.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.FileAccessibleAfterCleanVerdictAuthorizedMessageTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/FileAccessibleAfterCleanVerdictAuthorizedMessageTest.java`).
All eight tests are active (none disabled) and pass. "Proven by tests" is scoped to the patient-file
download entry point proved here: the clean-verdict and explicit-publication gating conditions and the
tenant, role and version refusals are exercised through HTTP. The limitations in `## Gap` are not
claimed as proved by this class.

The harness mirrors the neighbouring HTTP fixture: MockMvc `standaloneSetup` with
`ApiErrorHandler` and `TenantContextInterceptor` under the `synthetic-auth` profile, an
`InMemoryAttachmentStore`, a stubbed `StaffRepository` and `TransactionTemplate`, a fixed clock,
and only synthetic tenant, subject, unit, patient, connection and message identifiers. Every
request carries the two identity headers `X-Synthetic-Subject` and `X-Active-Organization`; the
versioned scan and download requests additionally carry `If-Match` (upload has no version
precondition). It is a fast
servlet-level proof, not a real-PostgreSQL integration test; the database controls are cited below
as supporting, not executed, evidence.

- `downloadAfterCleanVerdictAndExplicitPublishReturnsMediatedHandle` — positive proof. A PDF is
  uploaded through `POST /api/v1/patient/files/upload`, scanned clean through
  `POST /api/v1/patient/files/{id}/scan`, published explicitly with the domain send command, and
  then retrieved through `GET /api/v1/patient/files/{id}/download`. The response is HTTP 200 and
  the test asserts every mediated-handle field (`objectKey`, `objectVersion`, `checksum`,
  `contentType`, `sizeBytes`) plus the append-only `DOWNLOADED` fact. The precondition versions are
  1 (upload), 2 (clean verdict) and 3 (publication), so the download can only succeed after both
  gating conditions have occurred.
- `downloadIsRefusedForQuarantinedFileBeforeCleanVerdict` — negative proof of the verdict gate.
  An uploaded, unscanned file returns HTTP 409 `NOT_PUBLISHED`.
- `downloadIsRefusedForCleanButUnpublishedFile` — negative proof that a clean verdict alone is not
  enough: a scanned, still-unpublished file also returns HTTP 409 `NOT_PUBLISHED`.
- `downloadIsRefusedForFileWithFailedVerdict` — a failed verdict never becomes downloadable; HTTP
  409 `NOT_PUBLISHED`.
- `downloadIsRefusedForAnotherTenant` — a peer tenant selecting the same local file id receives
  HTTP 404 `NOT_FOUND`, so tenant isolation conceals existence.
- `downloadIsRefusedWithStaleVersion` — a stale `If-Match` against a published file receives HTTP
  409 `STALE_VERSION` before any handle is produced.
- `downloadIsRefusedForRoleWithoutDownloadPermission` and
  `downloadIsRefusedForScannerThatOnlyRecordsVerdicts` — a `privacy_auditor` and the
  `security_scanner` that records the verdict are both refused HTTP 403 `FORBIDDEN`: recording a
  verdict never implies access to the payload.

Production classes exercised through the entry point and its transaction:

- `com.aurevia.smartclinic.messaging.attachments.AttachmentController` — thin adapter binding
  `GET /api/v1/patient/files/{id}/download` to the domain service; it loads the tenant-scoped
  attachment, derives a fresh actor and parses `If-Match`. Upload and scan on the same controller
  create the preconditions.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentService` — `download` authorizes
  `ATTACHMENT_DOWNLOAD`, loads the file, checks the version precondition, rejects any state whose
  `downloadable()` is false with `NOT_PUBLISHED`, checks the participant, returns a
  `MediatedDownload` with no bytes, file name or URL, and records the download fact. `scan` records
  the independent verdict and `publish` requires `CLEAN` state before binding a message and
  recipient unit.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentContract` — `AttachmentState.downloadable()`
  is true only for `PUBLISHED`; `MediatedDownload` carries only the private object handle and
  integrity metadata.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentActions` — bounds `DOWNLOAD` to message
  sender roles and excludes the scanner, auditor and other non-participant roles.
- `com.aurevia.smartclinic.api.IfMatch`, `com.aurevia.smartclinic.web.TenantContextInterceptor` and
  `com.aurevia.smartclinic.api.ApiErrorHandler` — version precondition, synthetic tenant context and
  the uniform machine-readable error body asserted by the negative cases.

Supporting, not executed by this servlet-level test: `db/migrations/V82__attachment_store.sql`
defines the tenant-owned `attachment`, `attachment_connection_grant` and `attachment_event` tables
with `ENABLE` and `FORCE ROW LEVEL SECURITY`, the immutable-binding trigger that fixes uploader,
connection, unit, patient, checksum and object key, and append-only event triggers;
`contracts/openapi.yaml` publishes the staff-side `/api/v1/staff/attachments/*` flow, whose
`scan` and `send` operations express the same clean-then-publish ordering for staff messages.

The existing domain suite `com.aurevia.smartclinic.messaging.attachments.AttachmentAcceptanceTest`
independently covers the service-level participant and tenant denials for download
(`refusesDownloadBeforeCleanVerdictAndPublication`,
`deniesDownloadToWrongUnitWrongPatientAndNonDownloader`,
`twoTenantsWithIdenticalLocalIdentifiersStayIsolated`). This task adds a focused clause-only proof
and changes neither that suite nor production code.

## Gap

The gate itself is built and proven at this entry point; the following adjacent limitations are
recorded rather than overclaimed:

1. Download-time participant binding is not enforced through this controller.
   `AttachmentController.download` builds the actor with the `patientId` of the requested
   attachment, so `AttachmentService.participant()` always compares that value with itself. A
   caller holding any role in `AttachmentActions`' download set can therefore obtain the mediated
   handle of any published file in the same tenant, regardless of the conversation or patient it
   belongs to. The service-level denial is real and is proved by
   `AttachmentAcceptanceTest.deniesDownloadToWrongUnitWrongPatientAndNonDownloader`, but it is not
   reachable through this HTTP route. Closing it would change `AttachmentController.download` to
   derive the patient or unit binding from the authenticated principal and verified connection
   rather than from the attachment, plus new HTTP negative cases — one implementation task and one
   test task.
2. The patient-file routes are not published in the authoritative contract:
   `GET /api/v1/patient/files/{}/download`, `POST /api/v1/patient/files/upload` and
   `POST /api/v1/patient/files/{}/scan` appear only in `contracts/openapi.yaml`.
   Publishing them (with tag, `If-Match` and error responses) and regenerating clients is a
   separate cross-cutting contract task, outside this test-only scope.
3. The patient controller has no HTTP publish route; publication is a domain-service command, so
   the positive test drives `AttachmentService.publish` directly. The staff flow has its own
   `/api/v1/staff/attachments/send` operation. No new route was added in this task.
