# MSG-03 clause 7 — Preserve originals, checksums and provenance

## Clause

Preserve originals, checksums and provenance.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.PreserveOriginalsChecksumsProvenanceTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/PreserveOriginalsChecksumsProvenanceTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile. It recreates a
fresh database with Flyway, seeds two synthetic tenants with identical local connection,
unit and patient identifiers, binds tenant context exactly as the HTTP interceptor does,
and checks the append-only fact ledger under forced row-level security.

- `uploadPreservesOriginalChecksumFileNameAndObjectBinding` (rule met) — a synthetic PDF
  whose checksum is the SHA-256 hex of the original bytes enters quarantine; the
  attachment keeps that checksum, file name, media type, size, uploader, connection,
  unit, patient, private `quarantine/` object key and object version 1. After a clean
  scan, explicit publication and a mediated download, a reload from the durable store
  confirms every binding column (including file name, type, size, uploader, connection,
  unit, patient, object key/version and creation time) is unchanged, and the mediated
  handle keeps the checksum, object key/version, content type and size. The fact trail is
  exactly `UPLOADED`, `SCAN_CLEAN`, `PUBLISHED`, `DOWNLOADED`, each attributed to the
  correct actor and tenant.
- `jdbcStoreRefusesChecksumRewrite` (negative) — a direct `UPDATE` of `checksum` and
  `object_key` on a stored row, made by the migration owner, is rejected with SQLSTATE
  `44000` by the immutable-binding trigger and both columns remain unchanged.
- `crossTenantDownloadReturnsNotFoundWithoutAppendingAFact` (negative) — a second tenant
  using the same local identifiers cannot download the first tenant's published file
  (404 `NOT_FOUND`). The owning tenant's fact count starts at the expected three facts
  (upload, clean scan, publication) and is unchanged afterwards, and the calling tenant
  gains no fact. Both counts bind `app.current_org` explicitly, so forced RLS cannot make
  the assertions vacuously true.
- `scanWithStaleVersionIsRefusedLeavingOriginalsUnchanged` (negative) — a stale version
  precondition on a scan returns 409 `STALE_VERSION` and leaves version, checksum,
  file name, object handle, creation time, state and verdict unchanged; the refusal is
  itself recorded as a `conflict`/`STALE_VERSION` provenance fact.
- `wrongRoleCannotPublishLeavingTheFileClean` (negative) — an actor without the publish
  action gets 403 `FORBIDDEN`; the file stays `CLEAN`, keeps its checksum, binding and
  object handle, and its trail stays exactly `UPLOADED`, `SCAN_CLEAN` with no
  `PUBLISHED` fact.

Production classes and files exercised by the service/store path:

- `com.aurevia.smartclinic.messaging.attachments.AttachmentService` — `upload` binds the
  originals into a version-1 attachment, `withState` advances only lifecycle columns on
  scan/publish, `download` returns a mediated handle carrying the preserved checksum and
  object handle, and `view`/`events` expose the append-only fact trail.
- `com.aurevia.smartclinic.messaging.attachments.JdbcAttachmentStore` — the guarded
  `putAttachment` upsert writes the binding columns once, `attachment` reads them under
  the tenant transaction and `appendEvent` records actor-attributed facts.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentContract` — the `Attachment`,
  `MediatedDownload`, `AttachmentEvent` and `UploadRequest` records that carry the
  metadata, checksum and provenance without exposing payload bytes.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentController` — referenced for
  the SHA-256/`HexFormat` derivation pattern in `uploadRequest` only; it is not driven by
  this class. The HTTP-side checksum evidence is
  `AttachmentControllerHttpTest.uploadsThroughMultipartScansAndDownloads`, which asserts
  `$.checksum` on a mediated download whose seeded checksum was derived from the SHA-256
  of the multipart PDF bytes.
- `db/migrations/V82__attachment_store.sql` — `public.attachment` with `checksum`,
  `object_key` and `object_version`; `attachment_binding_immutable()` raising SQLSTATE
  `44000` on any rewrite of the binding; forced row-level security keyed on
  `public.app_org()`; and the append-only `attachment_event` ledger with update, delete
  and truncate triggers.
- Discovery note: `/api/v1/patient/files/**` is baselined in
  `contracts/openapi/missing-routes.txt:78,278-279` rather than published in
  `contracts/openapi.yaml`; only `/api/v1/staff/attachments/**` is published. Contract
  changes are outside this test-only story's scope.

Verified locally with the command and Surefire report:

```
SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security \
  -Dgroups=integration -Dtest=PreserveOriginalsChecksumsProvenanceTest test
<testsuite ... tests="5" errors="0" skipped="0" failures="0" flakes="0">
```

## Gap

Byte-for-byte original preservation and verification is missing on the patient
attachment path. The upload adapter hashes the multipart bytes and then discards them;
the durable row keeps only metadata, a checksum, a private object key/version and an
event trail, and `MediatedDownload` is a handle with no bytes or URL. No object-store
port is injected into `com.aurevia.smartclinic.messaging.attachments`, so neither a
durable original nor a re-hash verification exists there. A parallel staff slice does
have such a boundary (`JdbcStaffAttachmentStore.putBytes`/`bytes`,
`FileSystemObjectStore.getVerified`), but it is a separate, explicitly decoupled table
chain and is not the path this clause's test covers.

The complete test for the missing behaviour, to enable in a later task once the
behaviour exists, is `originalBytesRetainedAndVerified`: upload a synthetic PDF, read the
retained bytes back from the patient path's private object boundary, and assert that the
SHA-256 hex of the retained bytes equals the stored `checksum` and that a tampered byte
stream is refused by the boundary. It is not added as a `@Disabled` test here because
`scripts/check-constraints.py` rejects a newly added `@Disabled` in a Java file, and a
green test-only pull request is required.

Closing the gap needs two separate tasks: (1) a schema and service task adding a
patient-path object boundary and byte-retention columns or table with forced RLS,
append-only provenance and a verified read path under `messaging/attachments` and a new
Flyway migration; and (2) a test task enabling the byte-for-byte assertion above. No
production code, migration or contract was changed by this story.
