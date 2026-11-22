# CLN-07 clause 2 — retain original bytes and checksum

## Clause

Retain original bytes and checksum.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.documents.clauses.RetainOriginalBytesChecksumTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/RetainOriginalBytesChecksumTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic organizations, memberships and
one synthetic chart subject per test:

- `linkThenVerifiedDownloadReturnsTheExactRetainedOriginalBytes` — links an
  original through `POST /api/v1/documents/associations`, downloads it through
  `GET /api/v1/documents/associations/{id}/content` and asserts byte-for-byte
  equality with the uploaded original. It then reads the stored `content` and
  `sha256` columns and asserts that the database-derived digest equals the
  SHA-256 of the retained bytes. The link request carries no digest, so a
  caller-supplied or recomputed-from-input checksum cannot satisfy the assertion.
- `tamperedRetainedBytesAreRefusedWithChecksumMismatchAndAudited` — alters the
  retained bytes as a superuser with triggers disabled so the stored digest is
  deliberately stale, then asserts a 409 `CHECKSUM_MISMATCH`, a response body
  that contains neither the original nor the tampered bytes, and exactly one
  durable `TAMPERED` audit for that association. The stale digest is asserted
  directly against the altered bytes. This covers the `!verified.intact()`
  refusal in `DocumentService.associationContent`.
- `anotherTenantCannotReadTheRetainedOriginal` — a clinician of another tenant
  receives 404 through forced RLS, while the owning tenant still downloads the
  unchanged original.
- `actorWithoutDocumentReadRoleCannotDownload` — an active `tenant_admin` of the
  owning tenant receives 403; the test first confirms that actor has active
  membership and the role, so the refusal comes from the document action matrix
  rather than a missing fixture.

Production classes exercised through the document controller routes above:
`com.aurevia.smartclinic.documents.DocumentController` (injected service, thin
HTTP adapter), `com.aurevia.smartclinic.documents.DocumentService`
(`link` and `associationContent` and their tenant-guarded transactions;
`voidAssociation` preserves the original but is exercised by the pre-existing
`DocumentDigestIntegrationTest`, not by this class),
`com.aurevia.smartclinic.documents.DocumentRepository`
(`insert` omits any digest and `verifiedAssociationContent` reads the
recomputed flag), `com.aurevia.smartclinic.documents.DocumentModel.VerifiedContent`
(defensive copy of the retained bytes), `com.aurevia.smartclinic.documents.DocumentPolicy`
(clinician and reception may read; tenant administration may not) and
`com.aurevia.smartclinic.documents.DocumentInputs` (documents that the database,
not the caller, owns the digest). The database invariants come from
`db/migrations/V17__document_associations.sql` (`content bytea`, the BEFORE
INSERT trigger deriving `sha256` from those bytes, the update guard pinning
bytes and digest, FORCE RLS and the append-only `document_association_event`)
and `db/migrations/V75__build_06.sql`
(`document_association_verified_content`, which returns the retained bytes with
an `intact` flag instead of raising a SQL error so the tamper can be audited).

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.smartclinic.documents.DocumentDownloadHttpIntegrationTest`
(`verifiesDownloadThenDetectsTamperingAndConcealsOtherTenants`),
`com.aurevia.smartclinic.documents.DocumentDigestIntegrationTest`
(`retainedBytesVerifyThenTamperingIsFlagged`,
`voidKeepsTheOriginalAndAnotherTenantCannotReadIt`) and
`com.aurevia.smartclinic.documents.DocumentContentRepositoryTest` (unit proof of
`verifiedAssociationContent`).

Verified locally:
`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RetainOriginalBytesChecksumTest test`
—— `tests="4" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the document routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the document
association routes are absent from `contracts/openapi.yaml` and are listed in
`contracts/openapi/missing-routes.txt` (lines 35–36 and 198–199). Publishing
them would change `contracts/openapi.yaml`, the generated client and the
contract tests — one separate ARC-04/WEB-05 contract task. No production file,
existing test, migration or contract was changed by this proof.
