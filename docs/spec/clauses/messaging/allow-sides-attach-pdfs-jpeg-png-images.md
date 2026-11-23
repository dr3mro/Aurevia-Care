# MSG-03 clause 1 — API test evidence

## Clause

Allow both sides to attach PDFs and JPEG/PNG images.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.AllowSidesAttachPdfsJpegPngImagesTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/AllowSidesAttachPdfsJpegPngImagesTest.java`).
All three tests are active (none are disabled) and use synthetic data only.

- `patientAndStaffCanEachUploadPdfJpegAndPng` — positive proof. A verified patient actor
  and an authorized unit staff (`reception`) actor each upload all three supported file
  types through `AttachmentService.upload`. The test guards the three-type set, then for
  every one of the six actor/type combinations asserts the parsed type, `quarantined`
  state, `pending` verdict, original file name, size, checksum, initial versions, server
  timestamp, tenant-prefixed private object key, and — crucially — that the unit, patient,
  organization and uploader subject were bound from the server-side connection grant
  rather than from the command. It also asserts six distinct attachment ids and object
  keys and that both actor subjects were exercised.
- `uploadRefusesUnsupportedAndMismatchedTypes` — negative proof of type and integrity.
  `text/plain`, `image/gif` and an absent content type yield HTTP 422
  `UNSUPPORTED_CONTENT_TYPE`; a PDF declaration carrying the PNG leading bytes yields 422
  `CONTENT_MISMATCH`; a PDF declaration naming a `.png` file yields 422
  `EXTENSION_MISMATCH`.
- `uploadRefusesUnauthorizedActorAndForeignBinding` — negative proof of actor, binding
  and grant. A `privacy_auditor`, a role with no upload permission and a null actor are
  refused with HTTP 403 `FORBIDDEN`; a patient bound to another chart, staff assigned to
  another unit and staff with no unit at all are refused with HTTP 403 `WRONG_UNIT`; an
  unknown connection is refused with HTTP 404 `NOT_FOUND`.

Production classes exercised through the normal command entry point
(`AttachmentService.upload`, using the public `InMemoryAttachmentStore` test fake and a
fixed clock):

- `com.aurevia.smartclinic.messaging.attachments.AttachmentService` — owns upload
  validation, the verified-grant binding and the append-only `ACCESS_DENIED` facts.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentContract.FileType` — the only
  three accepted media types, their canonical extensions and their magic-byte prefixes;
  `Actor`, `ConnectionGrant`, `UploadRequest` and `Attachment` supply the typed command,
  grant snapshot and returned projection.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentInputs` — rejects unsupported
  content types before any durable change; magic, name, size and checksum validation is
  shared with the document upload primitive.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentActions` — grants
  `ATTACHMENT_UPLOAD` to `MESSAGE_SENDERS`, which includes the `patient` role and staff
  sender roles such as `reception`, while withholding it from auditor and unknown roles.

"Both sides" is evidenced across two complementary packages. The new clause-focused test
drives the shared `AttachmentService` with both a patient and a staff actor. The staff
side's own type coverage is asserted by
`com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentDomainTypesTest.contentTypeResolutionIsBounded`
(accepts `application/pdf`, `IMAGE/JPEG` and `image/png`) and
`com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentServiceTest.pdfJpegAndPngAreAcceptedButOtherTypesAreRefused`.
The two protected upload entry points are exercised over HTTP by
`com.aurevia.smartclinic.messaging.attachments.AttachmentControllerHttpTest.uploadsThroughMultipartScansAndDownloads`
(`POST /api/v1/patient/files/upload`) and
`com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentControllerHttpTest.uploadsScansListsSendsAndDownloadsThroughHttp`
(`POST /api/v1/staff/attachments/upload`); both HTTP fixtures use a PDF payload, so the
exhaustive three-type proof is the domain tests named above. The end-to-end PostgreSQL
proof that an uploaded file and its binding are persisted to and read back from the
database with an immutable binding is
`com.aurevia.smartclinic.messaging.attachments.it.JdbcAttachmentStoreIntegrationTest`
(`draftIsBoundToItsUploaderAndConversationAndCannotBeReused`,
`twoTenantsWithIdenticalLocalIdentifiersStayIsolated`), backed by
`db/migrations/V82__attachment_store.sql`, whose `file_type` check names exactly the three
media types and whose attachment tables have row-level security with forced enforcement.

Contract state: the staff routes are published in `contracts/openapi.yaml` under
`/api/v1/staff/attachments/*`. The patient routes `POST /api/v1/patient/files/upload`,
`POST /api/v1/patient/files/{}/scan` and `GET /api/v1/patient/files/{}/download` remain
listed in `contracts/openapi/missing-routes.txt` as pre-existing OpenAPI-parity debt. That
is a separate documentation concern and does not mean a side cannot attach a file.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not
change production code, an existing test, the contract or a migration.
