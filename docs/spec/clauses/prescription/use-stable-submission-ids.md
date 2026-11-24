# RX-02 clause 6 — use stable submission IDs

## Clause

Use stable submission IDs.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.prescription.clauses.UseStableSubmissionIdsTest`
(`apps/api/src/test/java/com/aurevia/prescription/clauses/UseStableSubmissionIdsTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the normal HTTP
entry points `POST /api/v1/prescriptions`, `/attest`, `/transmit`, `/verify-partner-status`, `/retry`,
`/renew`, `/confirm-delivery`, `/pharmacy-response` and `/dispensing`. Every test is active (none
disabled) and uses synthetic fixtures only. The suite recreates its own database
(`aurevia_use_stable_submission_ids_it`) from the authoritative Flyway migrations, seeds two
tenants with bounded roles, market/entitlement/channel policies and one synthetic patient each, and
asserts against both the HTTP response and the persisted `rx02_submission` ledger.

- `electronicSubmissionKeepsOneStableIdAcrossRetryAndRenewal` — positive proof. Transmitting
  `E_PRESCRIBE` with an ambiguous outcome returns a lowercase canonical UUID `submissionId`; the
  single `rx02_submission` ledger row is bound to that id and channel. After the partner reports the
  submission `absent`, a retry reuses the same id, leaves exactly one ledger row and two `submitted`
  facts that both carry the id, and a renewal keeps the prescription's id and links its own fact to
  it. The partner-status fact also carries the stable id.
- `aDifferentValidSubmissionIdIsRefusedByEveryDownstreamCommand` — negative proof of id binding.
  A different but valid UUID is refused with HTTP 422 `SUBMISSION_MISMATCH` by confirm-delivery,
  pharmacy-response and dispensing on a delivered prescription, and by verify-partner-status and
  retry on an ambiguous one. A refused confirmation leaves the prescription in `submitted` and a
  refused retry adds no ledger row.
- `retryByAnotherClinicianIsForbidden` — negative proof of prescriber-only routing. A different
  active clinician in the same tenant, presenting the correct version, state and submission id, is
  refused HTTP 403 `FORBIDDEN`; no submission is created.
- `aForeignTenantCannotRetryAnotherTenantsSubmission` — negative proof of tenant isolation. A
  clinician in the peer tenant cannot retry the owner's prescription id and stable submission id;
  forced row-level security conceals it as HTTP 404 `NOT_FOUND` and the owner's ledger is untouched.
- `aStaleRetryVersionIsRejectedWithoutResubmitting` — negative proof of optimistic concurrency. A
  retry carrying a stale `If-Match` is refused HTTP 412 `STALE_VERSION`; it adds no ledger row and
  no second `submitted` fact.

Entry-point-to-assertion trace: `POST /api/v1/prescriptions/{id}/transmit` →
`RxController.transmit` → `RxService.transmit` (`RxInputs.newSubmissionId`,
`RxRepository.insertSubmission` with `ON CONFLICT DO NOTHING`, `repository.transmit`) →
`db/migrations/V44__rx02_prescription_transmission.sql` (`rx02_submission` with
`UNIQUE (organization_id, submission_id)`, forced RLS and an immutability guard). The retry and
mismatch paths reach `RxService.retry` and `RxService.requireSubmission`, which reuses or refuses
the same id.

Production classes exercised through the routes and their transactions:

- `com.aurevia.prescription.rx.RxController` — thin adapter binding the nine prescription
  routes to the service and parsing `If-Match` versions.
- `com.aurevia.prescription.rx.RxService` — mints the id once on transmit, reuses it on
  retry and renewal, and refuses a mismatch on confirm, verify, retry, pharmacy response and
  dispensing.
- `com.aurevia.prescription.rx.RxInputs` — `newSubmissionId` and canonical UUID
  validation of every supplied `submissionId`.
- `com.aurevia.prescription.rx.RxRepository` — `insertSubmission` (idempotent ledger
  insert), `retrySubmission`, `submissionAttempts` and the RLS-bounded reads.
- `com.aurevia.prescription.rx.RxActions` and `RxContract` — bounded prescriber/pharmacy
  roles, state transitions and the view/event wire shape.

The existing suites `it/prescription/RxTransmissionFlowTest` and `prescription/rx/RxUnitTest` cover
the same production behaviour; this task adds a focused, clause-only proof and changes neither.

## Gap

None for the stable-submission-id rule: it is built and proven by active tests through the HTTP entry
point. This task added proof only; it did not change production code, an existing test, the OpenAPI
contract or a migration.

Two adjacent, non-blocking observations are recorded for completeness. First, the nine prescription
routes are listed in `contracts/openapi.yaml` as baselined routes absent from the
generated OpenAPI contract; publishing them and regenerating the staff client is a separate
cross-cutting contract task (WEB-05), outside this clause's test-only scope. Second, no unit test
asserts the UUID shape of `RxInputs.newSubmissionId` in isolation; its canonical form is proven here
through the HTTP response and the persisted ledger row.
