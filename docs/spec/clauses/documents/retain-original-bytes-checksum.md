# CLN-07 clause 2 — retain original bytes and checksum

## Clause

Retain original bytes and checksum.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.documents.clauses.RetainOriginalBytesChecksumTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/RetainOriginalBytesChecksumTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives
the HTTP entry point `GET /api/v1/documents/associations/{id}/content` with
synthetic fixtures. It seeds two organizations with a `clinician` and a
`reception` member, registers a synthetic chart and links a base64 PDF through
`POST /api/v1/patients` and `POST /api/v1/documents/associations`, and applies
only the production Flyway migrations from `db/migrations`.

- `originalBytesAreRetainedWithTheirChecksumAndServedVerified` (rule met) —
  downloads the linked original over HTTP and asserts the served bytes equal the
  uploaded bytes byte for byte. It then reads the stored row back and asserts
  that the retained `content` is byte-for-byte the original, that `byte_size`
  equals the original length, and that `sha256` equals both an independently
  computed SHA-256 of the original and `encode(sha256(content), 'hex')`
  recomputed from the bytes actually stored.
- `tamperedRetainedBytesAreRefusedWithChecksumMismatch` (negative) — alters the
  stored bytes as a storage-layer fault, then asserts the download is refused
  with HTTP 409 `CHECKSUM_MISMATCH`, a durable `TAMPERED` audit row is appended
  rather than the transaction being aborted, and the retained checksum no longer
  matches a digest recomputed from the altered bytes. No altered bytes are served.
- `retainedBytesOfAnotherTenantAreConcealed` (negative) — a `clinician` of the
  second organization requesting the first tenant's association is answered
  with HTTP 404, proving forced row-level security hides the retained original.

Production classes and files exercised by this request path:

- `com.aurevia.smartclinic.documents.DocumentController` — the thin HTTP adapter
  for the verified-content download route under `/api/v1/documents/associations`.
- `com.aurevia.smartclinic.documents.DocumentService` — `associationContent`
  authorizes the read, refuses a concealed or missing association with 404,
  audits and refuses a checksum mismatch with 409, and otherwise returns the
  verified original bytes.
- `com.aurevia.smartclinic.documents.DocumentRepository` —
  `verifiedAssociationContent` is the RLS-bounded SQL boundary that calls the
  verified-content database function.
- `db/migrations/V17__document_associations.sql` — the `document_association`
  table with its `content bytea`, `byte_size` and `sha256` columns and the
  `document_association_digest` trigger that derives size and digest from the
  exact uploaded bytes.
- `db/migrations/V75__build_06.sql` — `document_association_verified_content(uuid)`,
  which recomputes the digest from the retained bytes and reports an `intact`
  flag beside them without aborting the caller's transaction.

## Gap

None.
