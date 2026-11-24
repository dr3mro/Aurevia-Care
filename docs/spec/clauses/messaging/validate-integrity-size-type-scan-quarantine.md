# MSG-03 clause 4 — API test evidence

## Clause

Validate integrity, size and type and scan in quarantine.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.messaging.clauses.ValidateIntegritySizeTypeScanQuarantineTest`
(path: `apps/api/src/test/java/com/aurevia/messaging/clauses/ValidateIntegritySizeTypeScanQuarantineTest.java`).
All three tests are active (none disabled). They drive the normal HTTP entry point with MockMvc
through `AttachmentController` over a real `AttachmentService` backed by the synthetic
`InMemoryAttachmentStore`, using the `synthetic-auth` profile, a fixed clock and a server-derived
connection grant. All identifiers, subjects and payload bytes are synthetic. The proof drives the
real controller through MockMvc but deliberately uses `InMemoryAttachmentStore`, so forced-RLS and
JDBC persistence are out of scope here (they are covered by `JdbcAttachmentStoreIntegrationTest` in
`apps/api/src/test/java/com/aurevia/messaging/attachments/it/`); unlike sibling clause
tests that use `@Tag("integration")` and a real PostgreSQL database, this fixture makes no
restart or RLS claim.

- `validPdfIsIntegritySizeTypeCheckedQuarantinedAndUnreachableUntilCleanScan` — positive proof.
  A multipart PDF is accepted with HTTP 201 in state `quarantined`, verdict `pending`, declared type
  `application/pdf`, the measured byte size and the SHA-256 checksum of the payload, under a private
  `quarantine/<org>/` object key bound to the grant's unit and patient. The payload is refused with
  409 `NOT_PUBLISHED` while still quarantined and again after a clean verdict (state `clean`),
  proving that neither integrity validation nor a clean scan alone grants access; explicit message
  publication is still required. A `security_scanner` clean verdict against `If-Match: 1` is
  recorded. The append-only facts are asserted to carry no payload bytes, file name, checksum or URL.
- `rejectsUploadWithWrongTypeMismatchedBytesOversizeOrBadChecksum` — negative proof of the
  validation guards. A `text/plain`/`.txt` upload is refused with 422 `UNSUPPORTED_CONTENT_TYPE`;
  PDF leading bytes declared as `image/jpeg` with 422 `CONTENT_MISMATCH`; a `.png` extension on a
  PDF with 422 `EXTENSION_MISMATCH`; a payload one byte over the shared 10 MiB bound with 422
  `INVALID_SIZE_BYTES`. Because the HTTP adapter derives a well-formed magic prefix and checksum
  from the uploaded bytes, malformed metadata (422 `INVALID_MAGIC_HEX` and 422 `INVALID_CHECKSUM`)
  is proven at the service boundary with crafted upload commands over the same store fixture.
- `refusesScanByWrongRoleOrStaleVersionAndFailedVerdictKeepsFileQuarantined` — negative proof of
  the scan and access controls. A patient cannot issue a verdict (403 `FORBIDDEN`); a wrong
  `If-Match` is refused (409 `STALE_VERSION`); a `failed` verdict moves the file to state `failed`
  and the payload stays unreachable (409 `NOT_PUBLISHED`); a foreign tenant cannot locate the
  attachment (404 `NOT_FOUND`).

Production classes exercised through the entry point:

- `com.aurevia.messaging.attachments.AttachmentController` — thin multipart adapter that
  derives magic prefix, SHA-256 checksum and byte size from verified payload bytes and builds the
  actor from the server-side grant; never logs or returns payload bytes.
- `com.aurevia.messaging.attachments.AttachmentService` — `upload` validates type,
  leading bytes, file name, size and checksum format before storing into quarantine; the adapter
  derives the checksum from the payload bytes and the positive test compares it to an independently
  computed SHA-256, while verification at the private object boundary is out of scope; `scan`
  records an independent verdict; `download` permits only a published attachment.
- `com.aurevia.messaging.attachments.AttachmentActions` — bounds `SCAN` to the distinct
  `security_scanner` principal and refuses a patient scanner.
- `com.aurevia.messaging.attachments.AttachmentInputs` and
  `com.aurevia.documents.UploadValidation` — shared size bound, media-type parser and
  magic/checksum format rules.

The existing `AttachmentControllerHttpTest` and `AttachmentAcceptanceTest` cover the same behaviour;
this task adds a focused, clause-only proof and changes neither.

## Gap

None for this clause: it is built and proven by active tests through the HTTP entry point. This was
a test-only proof, so there was no legitimate red step to observe (green on first run is expected);
specificity comes from the negative assertions. No production code, existing test, OpenAPI contract
or migration was changed.

One adjacent, non-blocking debt is recorded for completeness: the routes
`POST /api/v1/patient/files/upload`, `POST /api/v1/patient/files/{}/scan` and
`GET /api/v1/patient/files/{}/download` are listed in `contracts/openapi.yaml` as
baselined routes, so the attachment API is not yet published in the authoritative OpenAPI contract.
Publishing those routes (and regenerating the client) is a separate cross-cutting contract task,
outside this clause's test-only scope and not required to prove the validation-and-quarantine rule.
