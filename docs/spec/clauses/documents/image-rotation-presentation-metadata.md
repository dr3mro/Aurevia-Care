# CLN-07 clause 3 — image rotation is presentation metadata

## Clause

Image rotation is presentation metadata.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.documents.clauses.ImageRotationPresentationMetadataTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/ImageRotationPresentationMetadataTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and
a fresh, issuer-scoped MRN for each chart. It drives the live controller routes:
`POST /api/v1/documents/associations`, `GET /api/v1/documents/associations/{id}`,
`GET /api/v1/documents/associations/{id}/content`,
`POST /api/v1/documents/rendered` and `GET /api/v1/documents/rendered/{id}/content`.

- `rotationIsStoredAsMetadataWithoutChangingTheRetainedBytes` — a link with
  `rotationDegrees: 90` reports `90` on the association projection while the
  returned `sha256` equals the SHA-256 of the exact original bytes and a content
  download returns those bytes unchanged. The original bytes never appear in the
  view.
- `rotatedRenderDiffersWhileSourceBytesStayIdentical` — rendering the same linked
  source at 0 and 90 degrees produces two distinct immutable PDFs carrying
  `/Rotate 0` and `/Rotate 90` respectively, each with its own database-derived
  digest. Re-reading the source association afterwards shows the same rotation,
  the same checksum and the same original bytes, so rotation changes only the
  presentation of a render.
- `unsupportedRotationIsRefused` — `rotationDegrees: 45` is refused with
  `422 INVALID_ROTATION`, and a direct database count proves no association was
  retained for that subject.
- `peerTenantCannotReadTheRotatedAssociation` — a peer tenant receives `404` for
  both the rotated association and its original content, while the owning
  tenant still reads its own rotation metadata.
- `receptionCannotRenderARotatedPdf` — reception may link and read but a render
  request with rotation 90 is refused with `403 FORBIDDEN`; rendering is a
  clinician-only action.

Production classes exercised through those HTTP entry points:

- `com.aurevia.smartclinic.documents.DocumentController` — thin adapter for the
  link, association, content and render routes.
- `com.aurevia.smartclinic.documents.DocumentInputs` — bounds rotation to
  0/90/180/270 with a null-to-zero default and emits `INVALID_ROTATION`.
- `com.aurevia.smartclinic.documents.DocumentService` — forwards rotation into
  the link command, re-verifies the database-owned digest on download, and
  stamps rotation only onto the immutable render.
- `com.aurevia.smartclinic.documents.DocumentRepository` — persists and reads
  the separate `rotation_degrees` column; it never writes a caller-supplied
  digest.
- `com.aurevia.smartclinic.documents.PdfRenderer` — writes `/Rotate` into the
  rendered page and hashes the whole body, including rotation, into the render's
  identifier.
- `com.aurevia.smartclinic.documents.DocumentPolicy` — reception link/read,
  clinician-only render.
- `com.aurevia.smartclinic.documents.DocumentModel` — immutable command and view
  records carrying the rotation value.
- `db/migrations/V17__document_associations.sql` — the separate bounded rotation
  column, the insert trigger that derives `sha256` from the retained bytes, the
  update guard that pins rotation, and forced tenant RLS.

Broader existing HTTP proofs (`DocumentIsolationTest` and
`DocumentDownloadHttpIntegrationTest`) already cover rotation metadata storage,
the retained-byte digest, the 403 role denial and the 404 tenant concealment.
This test adds the render-level assertions specific to this clause: distinct
zero/ninety-degree artifacts, their `/Rotate` markers and digests, the
before/after invariance of the source original, and the database count for a
refused rotation.

Verified locally:
`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ImageRotationPresentationMetadataTest test`
— `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` (see the story notes for the
captured final line).

## Gap

None. The clause is implemented and proven end to end. The document routes are
currently published in `contracts/openapi.yaml` rather than
`contracts/openapi.yaml`; that contract publication is a separate, already
tracked debt outside this test-only story, and the tests drive the real
controller routes directly.
