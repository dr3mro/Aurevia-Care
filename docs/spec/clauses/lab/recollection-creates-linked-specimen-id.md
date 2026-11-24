# LAB-01 clause 5 — recollection creates a linked specimen ID

## Clause

Recollection creates a linked specimen ID.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.lab.clauses.RecollectionCreatesLinkedSpecimenIdTest`
(`apps/api/src/test/java/com/aurevia/lab/clauses/RecollectionCreatesLinkedSpecimenIdTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants sharing
the same issuer and accession-number space. The happy path drives a specimen through
accession, collection, receipt and rejection before recollecting it; the original
specimen's new state and both specimens' immutable histories are read back through
`GET /api/v1/lab/accessions/{id}` (the recollect response alone carries only the new
`LabAccession`):

- `recollectLinksNewSpecimenIdToRejectedOriginal` — `POST /api/v1/lab/accessions/{id}/recollect`
  on a rejected specimen returns `201` with a new `id` distinct from the original;
  `replacesAccessionId` names the rejected original, the new row is `accessioned`,
  and its `sourceOrderRef` and `patientId` are carried over from the original. The
  original is read back as `recollected` with a fifth `recollected` history event
  (`rejected -> recollected`); the new specimen has a single `accessioned` event.
- `collectorCannotRecollect` — a `lab_collector` is refused with `403 FORBIDDEN`
  because `SPECIMEN_RECOLLECT` is a verifier duty; the original stays rejected at
  version 4.
- `staleVersionRecollectionRefused` — an `expectedVersion` of 3 against a rejected
  specimen at version 4 is refused with `412 STALE_VERSION`; the original keeps its
  state, version and four-event history.
- `onlyRejectedSpecimenCanBeRecollected` — a merely accessioned specimen is refused
  with `409 INVALID_TRANSITION`; `AccessionState` permits only `REJECTED -> RECOLLECTED`.
- `duplicateAccessionNumberRecollectionRefused` — reusing an accession number that
  already exists for the same tenant and issuer is refused with `409 CONFLICT`.
- `peerTenantCannotRecollect` — a peer tenant's verifier is refused with `404 NOT_FOUND`;
  forced RLS conceals the original row, which stays rejected and unchanged.
- `malformedAccessionNumberRefused` — a malformed `accessionNo` is refused at the HTTP
  boundary with `422 INVALID_ACCESSION`, leaving the original untouched.

Production classes exercised through the real HTTP entry point
`POST /api/v1/lab/accessions/{id}/recollect` and `GET /api/v1/lab/accessions/{id}`:

- `com.aurevia.lab.accession.LabAccessionController` — thin adapter binding
  `RecollectRequest` and mapping the outcome to HTTP 201.
- `com.aurevia.lab.accession.LabAccessionService` — `recollect` re-derives
  membership and role inside one tenant-guarded transaction, requires the rejected
  state and the expected version, inserts the linked row, transitions the original and
  appends both history events and the audit row together.
- `com.aurevia.lab.accession.LabActionPolicy` — `SPECIMEN_RECOLLECT` is
  allowed only for `lab_verifier`.
- `com.aurevia.lab.accession.AccessionState` — the single legal edge is
  `REJECTED -> RECOLLECTED`.
- `com.aurevia.lab.accession.LabInputs` and
  `com.aurevia.lab.accession.AccessionCommands.RecollectRequest` — bound the
  new accession number, issuer and expected version at the trust boundary.
- `com.aurevia.lab.accession.LabAccession`,
  `com.aurevia.lab.accession.AccessionEvent` and
  `com.aurevia.lab.accession.AccessionView` — the returned projection, the
  append-only history and the read view used to verify the link survives a read.
- `com.aurevia.lab.accession.LabAccessionRepository` — tenant-bounded
  insert, version-guarded update and event append.
- `db/migrations/V26__laboratory_accession.sql` — `replaces_accession_id` with an
  organization-scoped foreign key, the `(organization_id, accession_issuer,
  accession_no)` unique index, forced RLS, the version/state update guard and triggers
  that derive the actor from the authenticated subject.

Verified locally: `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RecollectionCreatesLinkedSpecimenIdTest test`
— `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` (see the story notes for the
captured final lines).

## Gap

None. The clause is implemented and proven end to end through the real controller,
service, repository and V26 schema. The published `contracts/openapi.yaml` does not yet
list `POST /api/v1/lab/accessions/{}/recollect` (it appears only in
`contracts/openapi.yaml`), but that contract publication is a separate,
already-tracked debt (FIX-OPENAPI-03) outside this test-only story; the tests drive the
real controller route directly.
