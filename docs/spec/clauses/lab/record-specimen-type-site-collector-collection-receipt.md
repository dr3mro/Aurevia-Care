# LAB-01 clause 2 — Record specimen type, site, collector, collection/receipt time and quality

## Clause

Record specimen type, site, collector, collection/receipt time and quality.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.lab.clauses.RecordSpecimenTypeSiteCollectorCollectionReceiptTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/lab/clauses/RecordSpecimenTypeSiteCollectorCollectionReceiptTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile. It creates a fresh
database with Flyway, seeds two synthetic organizations with a collector, a verifier and a
reception subject, and drives the real HTTP entry points `POST /api/v1/lab/accessions`,
`POST /api/v1/lab/accessions/{id}/collect`, `POST /api/v1/lab/accessions/{id}/receive` and
`GET /api/v1/lab/accessions/{id}`. Each test starts from its own accessioned specimen.

- `collectionRecordsSpecimenTypeSiteCollectorAndCollectionTime` — a collection command with
  `specimenType`, `siteCode`, `collector` and `collectedAt` returns HTTP 200, advances the
  version to 2, moves the state to `collected`, and every captured field (including the
  submitted instant, parsed back from PostgreSQL's `timestamptz` text form) round-trips
  through a later read through the controller.
- `receiptRecordsQualityAndReceiptTime` — a receipt command by the verifier with `quality`
  and `receivedAt` returns HTTP 200, advances the version to 3, moves the state to `received`,
  preserves the collected fields, and both new fields round-trip through `GET /{id}`.
- `verifierCannotCollectSpecimen` (negative) — a `lab_verifier` subject is refused with 403
  `FORBIDDEN` on collection and the specimen stays `accessioned`.
- `collectorCannotReceiveSpecimen` (negative) — a `lab_collector` subject is refused with 403
  `FORBIDDEN` on receipt and the specimen stays `collected`.
- `otherTenantCannotCaptureSpecimen` (negative) — an active collector of organization B cannot
  collect a real, concealed accession of organization A; forced RLS yields 404 `NOT_FOUND` and
  the source row is unchanged.
- `staleVersionIsRejected` (negative) — a wrong positive expected version is refused with
  412 `STALE_VERSION` on both collect and receive.
- `malformedOrMissingSpecimenFieldsAreRejected` (negative) — malformed or missing inputs are
  refused with 422 and the field-specific codes `INVALID_SPECIMEN_TYPE`, `INVALID_SITE`,
  `INVALID_COLLECTOR`, `INVALID_TIMESTAMP` and `INVALID_QUALITY`.
- `secondCollectionIsForbidden` (negative) — collecting an already collected specimen is
  refused with 409 `INVALID_TRANSITION` and the state remains `collected`.
- `mismatchedPatientOrAccessionIsRejected` (negative) — a different but well-formed patient
  identifier, accession number or issuer is refused with 422 `PATIENT_ACCESSION_MISMATCH` at
  collection, and the same check applies at receipt.

Production classes and files exercised by each request path:

- `com.aurevia.smartclinic.lab.accession.LabAccessionController` — thin HTTP adapter binding
  the commands and mapping the service outcome to a status code.
- `com.aurevia.smartclinic.lab.accession.LabAccessionService` — validates every field at the
  boundary, re-derives membership and role inside the guarded transaction, matches patient and
  accession at collection and receipt, and enforces version, state and audit together.
- `com.aurevia.smartclinic.lab.accession.LabInputs` — bounded specimen type, site, collector,
  quality and ISO-8601 instant validation with field-specific error codes.
- `com.aurevia.smartclinic.lab.accession.LabActionPolicy` — separation of duties: only
  `lab_collector` collects, only `lab_verifier` receives and verifies.
- `com.aurevia.smartclinic.lab.accession.AccessionState`, `AccessionCommands`, `LabAccession`
  and `AccessionView` — the state machine, command payloads and the returned projection.
- `com.aurevia.smartclinic.api.ApiErrorHandler` — uniform status and error-code body for the
  negative cases.
- `db/migrations/V26__laboratory_accession.sql` — the `specimen_type`, `site_code`, `collector`,
  `collected_at`, `received_at` and `quality` columns with their state checks, forced row-level
  security and the update guard that pins ownership and advances the version by one.

Adjacent coverage already on `main` (not re-counted here): `it/lab/SpecimenAccessionIsolationTest`
covers the same controller's tenant, role and mismatch paths, and `lab/accession/IssuerCodeBoundaryTest`
covers the shared issuer/code primitive. The collect and receive routes are baselined in
`contracts/openapi/missing-routes.txt`; changing the contract is outside this story's scope.

## Gap

None.
