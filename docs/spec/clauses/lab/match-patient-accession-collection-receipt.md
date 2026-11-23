# LAB-01 clause 3 — API test evidence

## Clause

Match patient and accession at collection and receipt.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.lab.clauses.MatchPatientAccessionCollectionReceiptTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/lab/clauses/MatchPatientAccessionCollectionReceiptTest.java`).
All eight tests are active (none are disabled) and use synthetic data only. Each command is
driven through its normal entry point on the domain service with a mocked repository, transaction
template and audit appender, matching the fixture style of the neighbouring laboratory tests.

- `collectMatchesPatientAndAccessionThenTransitions` — positive proof at collection. An active
  `lab_collector` collects a specimen whose patient id, accession number and issuer all match the
  stored requisition. The test asserts the `ACCESSIONED -> COLLECTED` transition, the repository
  update carrying the collection fields, the immutable `collected` event whose last argument is the
  matched patient id, and the `SUCCESS` `LAB_SPECIMEN_COLLECT` audit row.
- `receiveMatchesPatientAndAccessionThenRecordsReceipt` — positive proof at receipt. An active
  `lab_verifier` receives a matching specimen. The test asserts the `COLLECTED -> RECEIVED`
  transition, the recorded receipt time and quality, the `received` event carrying the matched
  patient id, and the `SUCCESS` `LAB_SPECIMEN_RECEIVE` audit row.
- `collectRefusesMismatchedPatientAccessionOrIssuer` — negative proof at collection. Wrong patient
  id, wrong accession number and wrong issuer are submitted one field at a time so every operand
  of the identity check is evaluated. Each yields HTTP 422 `PATIENT_ACCESSION_MISMATCH`, with no
  repository update, no history event and no `SUCCESS` audit.
- `receiveRefusesMismatchedPatientAccessionOrIssuer` — negative proof at receipt. The same three
  single-field mismatches are re-submitted at the receipt touchpoint, each yielding HTTP 422
  `PATIENT_ACCESSION_MISMATCH` with no state change.
- `collectRefusesActorWithoutCollectorPermission` — duty-separation negative. A verifier has active
  membership but lacks the collection action; collection is refused with HTTP 403 `FORBIDDEN`, a
  `DENIED` audit is written and no accession is even read.
- `receiveRefusesActorWithoutVerifierPermission` — duty-separation negative. A collector lacks the
  receipt action; receipt is refused with HTTP 403 `FORBIDDEN` and a `DENIED` audit.
- `collectAndReceiveRefuseActorWithoutActiveMembership` — both commands fail closed with HTTP 403
  `FORBIDDEN` when the subject has no active membership, and both denials are audited.
- `receiveRefusesStaleVersionForbiddenStateOrLostUpdate` — guard negatives at receipt: a forbidden
  `ACCESSIONED -> RECEIVED` jump yields HTTP 409 `INVALID_TRANSITION`; a stale expected version and
  a lost optimistic lock each yield HTTP 412 `STALE_VERSION`; none records a history event or a
  `SUCCESS` audit.

Production classes exercised through the normal command entry points
(`LabAccessionService.collect` and `LabAccessionService.receive`):

- `com.aurevia.smartclinic.lab.accession.LabAccessionService` — owns the `match` rule and calls it
  inside the tenant-guarded `transition` at both collection and receipt, then commits the state
  change, immutable event and audit row together.
- `com.aurevia.smartclinic.lab.accession.LabActionPolicy` — grants `lab_collector` the collect duty
  and `lab_verifier` the receive duty, enforcing separation of duties.
- `com.aurevia.smartclinic.lab.accession.LabInputs` — bounds every client field at the trust
  boundary before the transaction opens.
- `com.aurevia.smartclinic.lab.accession.LabAccessionRepository` — the tenant-guarded store whose
  `update` and `appendEvent` calls carry the matched identity into state and history.
- `com.aurevia.smartclinic.lab.accession.AccessionState` — the guarded `ACCESSIONED -> COLLECTED ->
  RECEIVED` lifecycle.
- `com.aurevia.smartclinic.lab.accession.LabAccessionController` — the thin HTTP adapter exposing
  `POST /api/v1/lab/accessions/{id}/collect` and `POST /api/v1/lab/accessions/{id}/receive`. This
  clause test drives the injected service directly; the controller's HTTP path is proven by the
  integration test cited below.

Corroborating evidence already in the repository (not changed by this task): the real-PostgreSQL
HTTP proof `com.aurevia.smartclinic.it.lab.SpecimenAccessionIsolationTest.collectionAndReceiptVerifyPatientAndAccession`,
which posts to both routes through the real controller and store, and the service-level branch
proof `com.aurevia.smartclinic.it.lab.LabAccessionServiceTest` (tests at lines 190-284). The clause
is built and proven by active tests; no route, contract or migration entry was added.

## Gap

None.
