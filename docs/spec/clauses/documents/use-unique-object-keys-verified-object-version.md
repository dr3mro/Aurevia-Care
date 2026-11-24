# ARC-06 clause 2 — use unique object keys and verified object version

## Clause

Use unique object keys and verified object version.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.documents.clauses.UseUniqueObjectKeysVerifiedObjectVersionTest`
(`apps/api/src/test/java/com/aurevia/documents/clauses/UseUniqueObjectKeysVerifiedObjectVersionTest.java`),
an HTTP-level integration test on real PostgreSQL with synthetic tenants, staff
and payload metadata. Every command goes through the real controller and the
production JDBC store against a dedicated database that is dropped, migrated
and seeded per class. The six tests are active (none disabled) and pass.

- `eachUploadGetsAUniqueTenantScopedKeyAtVersionOneAndTheVerifiedObjectCompletes` —
  positive proof. Two uploads into the same context produce distinct artifact IDs
  and distinct private keys matching `quarantine/<orgId>/<random-uuid>/v1`; the
  keys carry neither the patient-shaped token, the file name nor the context.
  Both start at immutable object version 1. After a clean scan and a completion
  backed by the exact verified object row, the artifact is `COMPLETED` at artifact
  version 3 while its object key and object version stay unchanged, and the
  mediated download handle carries that same key, version, checksum, content type
  and size.
- `completionRefusesWhenTheStoredObjectVersionDoesNotMatch` — negative proof for
  the version. Only object version 2 is on record while the artifact is bound to
  version 1; completion returns 409 `OBJECT_UNVERIFIED`, the state stays `CLEAN`
  and no completion fact is recorded. Seeding the exact version and retrying the
  same `If-Match: 2` command then succeeds, proving the refusal was the version
  mismatch rather than a stale precondition.
- `completionRefusesWhenNoVerifiedObjectRowExists` — negative proof that a
  completion cannot release an object with no verified row at all.
- `completionRefusesWhenTheVerifiedChecksumDiffers` — negative proof that the
  checksum is part of the verified tuple, not just the key and version.
- `anotherTenantsVerifiedObjectRowCannotSatisfyThisArtifactsObjectKey` — negative
  proof that the tenant is part of the key and the verified tuple: an identical
  local context in another tenant gets its own key namespace, and a verified row
  owned by the other tenant (same key, version and checksum) still cannot verify
  this artifact.
- `downloadRefusesWhenTheVerifiedObjectIsRemovedAfterCompletion` — negative proof
  that a completed artifact re-verifies its object on download: after the verified
  row is removed, the download is refused with 409 `OBJECT_UNVERIFIED`, not
  `NOT_COMPLETED`.

Production classes and database objects exercised through the normal HTTP entry
point `POST /api/v1/documents/artifacts` and its scan, complete and download
routes (`com.aurevia.documents.DocumentArtifactController`):

- `com.aurevia.documents.QuarantineService` — creates the unique key
  and version-one binding on upload, copies them unchanged through state changes,
  and calls `verifyObject` before completion and again before download.
- `com.aurevia.documents.ArtifactInputs` and
  `com.aurevia.documents.UploadValidation` — build the tenant-scoped
  `quarantine/<organizationId>/<random-uuid>/v<version>` key and validate positive
  object versions; the alias is a random UUID, not a patient or file-derived value.
- `com.aurevia.documents.JdbcDocumentStore` — `verifyObject` queries
  the full `(organization_id, object_key, object_version, checksum)` tuple.
- `db/migrations/V81__document_store.sql` — `document_verified_object`, with row
  level security enabled and forced, is the durable authority for that tuple.
- `contracts/documents/document-artifacts.md` — records the upload, completion,
  mediated-download and object-version guarantees.

Related implementations observed in the source survey but not reached by this
HTTP path (they are exercised by the neighbouring storage tests
`ObjectKeyTest`, `FileSystemObjectStoreTest` and `S3ObjectStoreTest`):
`com.aurevia.documents.storage.ObjectKey`, `ObjectStorePort`,
`FileSystemObjectStore.getVerified` and `S3ObjectStore.getVerified`, which verify
the immutable version and checksum at the byte-storage boundary; and
`com.aurevia.documents.PdfBindingService`, which records a verified
rendered object version for immutable PDF binding.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=UseUniqueObjectKeysVerifiedObjectVersionTest test`
— Surefire: `tests="6" errors="0" skipped="0" failures="0"`.

## Gap

None.
