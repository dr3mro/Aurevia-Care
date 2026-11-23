# CLN-07 clause 3 — image rotation is presentation metadata

## Clause

Image rotation is presentation metadata.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.documents.clauses.ImageRotationPresentationMetadataTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/ImageRotationPresentationMetadataTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the `synthetic-auth`
profile, two synthetic tenants and a private `smartclinic_cln07_c03_it` database with
Flyway migrations. Fixtures are a synthetic PNG signature plus synthetic text and
synthetic staff identities; no patient data is used. Every assertion goes through the
real controller on a random port, with durable rows read back through a
`NOBYPASSRLS` connection under forced row-level security.

Test methods:

- `rotationIsStoredAsMetadataWithoutChangingOriginalBytes` — links a synthetic PNG
  with `rotationDegrees=90` (201), reads back `rotationDegrees=90`, `byteSize` and the
  database-derived `sha256`, downloads byte-identical original bytes, reads
  `rotation_degrees=90` under forced RLS, renders at 90 and at 0 on version-1
  subjects, and proves the PDF carries `/Rotate 90` versus `/Rotate 0` while the
  original bytes, digest and stored turn stay unchanged.
- `linkRejectsRotationOutsideQuarterTurns` — 45 is refused with 422 and
  `INVALID_ROTATION` while every other link field stays valid.
- `renderIsForbiddenForRoleWithoutDocumentPermission` — a receptionist, who may
  link and read, is refused a syntactically valid render with 403 `FORBIDDEN`
  ("missing document permission").
- `contentIsConcealedFromAnotherTenant` — a clinician in organization B receives
  404 for organization A's content, and the same query under organization B's
  forced RLS policy returns no row while organization A still sees the row.
- `renderRejectsInvalidRotation` — 45 is refused at the render boundary with 422
  `INVALID_ROTATION`.
- `omittedRotationDefaultsToZero` — an omitted rotation is stored as 0 and
  rendered as `/Rotate 0`.
- `rotationCannotBeRewrittenAfterLinking` — a direct database rewrite of
  `rotation_degrees` is rejected and the original bytes remain unchanged.
- `everyBoundedQuarterTurnIsAcceptedAndRetained` — 0, 90, 180 and 270 are each
  accepted, retained and emitted as the matching PDF `/Rotate` directive.

Production classes exercised:

- HTTP entry points: `com.aurevia.smartclinic.documents.DocumentController`
  (`POST /api/v1/documents/associations`,
  `GET /api/v1/documents/associations/{id}`,
  `GET /api/v1/documents/associations/{id}/content`,
  `POST /api/v1/documents/rendered`,
  `GET /api/v1/documents/rendered/{id}/content`).
- Boundary validation: `com.aurevia.smartclinic.documents.DocumentInputs.rotation`.
- Domain commands: `com.aurevia.smartclinic.documents.DocumentService.link`,
  `association`, `associationContent`, `render`, `renderedContent`, `authorize`.
- SQL boundary: `com.aurevia.smartclinic.documents.DocumentRepository.insert`,
  `findAssociation`, `verifiedAssociationContent`, `mapAssoc`.
- PDF emitter: `com.aurevia.smartclinic.documents.PdfRenderer.render`.
- Authorization matrix: `com.aurevia.smartclinic.documents.DocumentPolicy.allows`.
- Uniform errors: `com.aurevia.smartclinic.api.ApiErrorHandler`.
- Schema: `db/migrations/V17__document_associations.sql` — `rotation_degrees`
  `SMALLINT NOT NULL DEFAULT 0 CHECK (IN (0, 90, 180, 270))`, tenant primary key,
  `ENABLE`/`FORCE ROW LEVEL SECURITY`, the `tenant_isolation` policy and the
  `guard_document_association_update()` trigger that also pins rotation against
  mutation (the direct-rewrite test is refused first by the column-level
  `UPDATE` grant, which excludes `rotation_degrees`); `db/migrations/V75__build_06.sql`
  re-verifies the digest from the retained bytes.

Where the clause is implemented today (survey result):

- `DocumentInputs.rotation` accepts only 0/90/180/270, treats a missing value as
  0 and raises `422 INVALID_ROTATION` otherwise. `DocumentService.link` validates
  before its transaction and passes the turn to `DocumentRepository.insert`, which
  writes the separate `rotation_degrees` column; the original bytes and digest
  are derived by the V17 trigger from `content` alone.
- `DocumentService.render` validates the same turn and passes it into `PdfSpec`;
  `PdfRenderer.render` writes it as the PDF page `/Rotate` value into a new
  immutable revision and folds it into the document identifier, so a turn changes
  the render but never the linked original. `RenderedDocument` deliberately has
  no rotation field: the turn lives in the PDF bytes and the association column.
- The clause is implemented in one reachable server-side path. The staff web has
  a local `rotateDocument` presentation helper (`apps/staff-web/src/features/chart/domain.ts`),
  which is not API evidence. The artifact/upload services and their
  `/api/v1/documents/artifacts/**` routes contain no rotation behavior and were
  not used as evidence. All document routes are baselined in
  `contracts/openapi/missing-routes.txt`, so this test-only story needs no
  OpenAPI or client regeneration.

## Gap

None
