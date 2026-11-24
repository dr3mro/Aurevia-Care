# ARC-06 clause 1 — API test evidence

## Clause

Validate size, supported type, checksum and scan state; keep quarantine private.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.documents.clauses.ValidateSizeSupportedTypeChecksumScanStateTest`
(path: `apps/api/src/test/java/com/aurevia/documents/clauses/ValidateSizeSupportedTypeChecksumScanStateTest.java`).
All five tests are active (none disabled) and pass against real PostgreSQL with synthetic fixtures only:
`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` from
`mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=ValidateSizeSupportedTypeChecksumScanStateTest test`.

- `validUploadStaysPrivateUntilCleanScanAndAuthorizedCompletionMakesItDownloadable` — positive proof.
  A valid upload returns 201 in `quarantined` state with a `pending` verdict and a private
  `quarantine/<org>/` object key. A non-uploader cannot view it (404), a download before completion
  is refused (409 `NOT_COMPLETED`), and completion is refused until the object version verifies
  (409 `OBJECT_UNVERIFIED`). After an independent clean scan and an authorized completion, the
  clinician download returns only the five metadata fields of the mediated handle; the original
  name, payload bytes and URLs stay absent.
- `oversizeUnsupportedTypeBadChecksumAndMismatchedMagicAreRefusedBeforeStoringAnything` — validation
  negatives. One field at a time is invalidated so an earlier guard cannot mask the intended one:
  422 `INVALID_SIZE_BYTES` (10 MiB + 1), 422 `UNSUPPORTED_CONTENT_TYPE` (unknown MIME),
  422 `INVALID_CHECKSUM` (non-hex checksum), 422 `CONTENT_MISMATCH` (PNG leading bytes declared as
  PDF) and 422 `EXTENSION_MISMATCH` (PDF content type with a `.png` name). Each response has no
  artifact id or `Location` header, and an RLS-scoped count of `document_artifact` rows is unchanged.
- `downloadBeforeCompletionAndScanAfterCompletionAreRefused` — state negatives. A quarantined and
  a merely clean artifact both refuse download (409 `NOT_COMPLETED`), completing a non-clean draft
  is refused (409 `NOT_CLEAN`), and rescanning a completed artifact is refused
  (409 `INVALID_SCAN_STATE`) with the current `If-Match` version so the state guard, not the version
  guard, fires.
- `foreignTenantAndWrongRoleCannotReadOrScanQuarantineArtifact` — boundary negatives. A peer-tenant
  principal gets 404, a same-tenant clinician gets 404 for the private projection, a clinician
  cannot scan (403), a patient principal is fail-closed at the controller (403), and a renderer
  cannot upload (403).
- `staleVersionIsRejectedOnScan` — concurrency negative. A scanned artifact is at version 2; a scan
  with `If-Match: 9` is refused with 412 `STALE_VERSION`, and a missing precondition with 428.

Production classes exercised through the normal HTTP entry point
`POST/GET /api/v1/documents/artifacts` (`com.aurevia.documents.DocumentArtifactController`,
which derives tenant and roles from the request context and injects its services):

- `com.aurevia.documents.QuarantineService` — upload validation order, private
  quarantine, independent scan verdict, verified-object completion, state guards, stale-version
  guard and mediated download.
- `com.aurevia.documents.UploadValidation` and
  `com.aurevia.documents.ArtifactInputs` — the shared 10 MiB bound, SHA-256 checksum,
  file name and MIME/leading-byte rules that raise the stable 422 codes.
- `com.aurevia.documents.DocumentGuard` and
  `com.aurevia.documents.DocumentActions` — authorization and the 403/404/409/412
  mapping. The proof asserts that a non-empty fact trail is visible after a completed download;
  append-only enforcement and payload-free fact detail are guaranteed by the guard and V81 triggers
  and are not separately asserted by this clause test.
- `com.aurevia.documents.JdbcDocumentStore` behind
  `com.aurevia.documents.DocumentStore` — tenant-scoped persistence of artifacts,
  authorizations and verified objects.
- `db/migrations/V81__document_store.sql` — creates the document tables with row-level security
  enabled and forced; the test applies it with Flyway and seeds only synthetic organizations,
  staff, grants and object metadata.
- `contracts/openapi.yaml` — the REST contract file. The twelve document-artifact routes are not yet
  published there; that contract gap is tracked separately and is outside this clause's scope.

## Gap

None.
