# RX-02 clause 7 — verify partner status before retrying

## Clause

On ambiguous transmission, verify partner status before retrying.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.prescription.clauses.AmbiguousTransmissionVerifyPartnerStatusBeforeRetryingTest`
(`apps/api/src/test/java/com/aurevia/prescription/clauses/AmbiguousTransmissionVerifyPartnerStatusBeforeRetryingTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic organizations, subjects and one
synthetic patient:

- `verifyingAbsentBeforeRetryResubmitsWithTheSameSubmissionId` — an ambiguous
  send is reconciled with `absent`, then the retry returns 200 with state
  `submitted`, the same stable submission id, exactly one `prescription_submission` row,
  two `submitted` facts and a `partner_status_verified` fact whose outcome is
  `absent`; the retry is recorded as `attempt=2`.
- `retryBeforeVerificationIsRefusedWithoutSendingAgain` — a retry on an
  unverified ambiguous submission is refused (409, `RETRY_NOT_ALLOWED`) and the
  state, version and single send fact are unchanged.
- `verifyPresentReturnsToSubmittedAndForbidsRetry` — a partner that already holds
  the submission returns the prescription to `submitted`; the later retry is
  refused (409, `INVALID_TRANSITION`) with no second send.
- `verifyUnknownKeepsAmbiguousAndStillForbidsRetry` — an `unknown` partner answer
  leaves the state `ambiguous` with retry disabled; the retry is refused (409,
  `RETRY_NOT_ALLOWED`).
- `aDifferentClinicianCannotVerifyTheSubmission` — another active clinician of
  the same tenant cannot verify (403, `FORBIDDEN`) and nothing is written.
- `aClinicianFromAnotherTenantCannotVerifyTheSubmission` — the row is concealed
  from a foreign tenant (404, `NOT_FOUND`).
- `aStaleVersionCannotVerifyTheSubmission` — a stale `If-Match` is refused
  (412, `STALE_VERSION`).
- `aMismatchedSubmissionIdCannotVerifyTheSubmission` — a well-formed but foreign
  submission id is refused (422, `SUBMISSION_MISMATCH`).
- `anInvalidPartnerStatusCannotVerifyTheSubmission` — a status outside the
  three-valued vocabulary is rejected at the input boundary (422,
  `INVALID_STATUS`) before any write.

Each negative test re-reads the prescription and the fact ledger through the
organization-scoped connection to prove that no second send, event or state
change occurred.

Production classes exercised through
`POST /api/v1/prescriptions/{id}/verify-partner-status` and
`POST /api/v1/prescriptions/{id}/retry`:
`com.aurevia.prescription.rx.RxController` (thin HTTP adapter),
`com.aurevia.prescription.rx.RxService` (three-valued reconciliation
sets `retry_allowed` only for `absent`; the retry gate is exercised here, while
the attempt-limit and retry-side submission-id branches are pre-existing and
covered by the corroborating suites named below),
`com.aurevia.prescription.rx.RxActions`
(clinician-only verify and retry), `com.aurevia.prescription.rx.RxInputs`
(partner-status and outcome validation),
`com.aurevia.prescription.rx.RxContract` (`AMBIGUOUS` state,
`VerifyRequest`, `RetryRequest`, `PARTNER_STATUS_VERIFIED` event),
`com.aurevia.prescription.rx.RxRepository` (`reconcile`,
`retrySubmission`, `submissionAttempts`) and the tenant RLS/FORCE RLS plus the
guarded state machine in `db/migrations/V44__prescription_transmission_transmission.sql`
and `db/migrations/V59__prescription_ambiguous_transition.sql`.

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.it.prescription.RxTransmissionFlowTest`
(`ambiguousRetryIsBlockedUntilPartnerStatusIsVerifiedAbsent`) and
`com.aurevia.acceptance.specialty.PharmacyAmbiguousTransmissionAcceptanceTest`
(`ambiguousTransmissionIsReconciledWithoutASecondSubmission`,
`aPresentPartnerStatusReturnsToSubmittedWithoutARetry`).

Verified locally: `AUREVIA_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml
-Pno-security -Dtest=AmbiguousTransmissionVerifyPartnerStatusBeforeRetryingTest test`
— `tests="9" errors="0" skipped="0" failures="0"` in the Surefire XML report.

"Proven by tests" is limited to the runtime clause behaviour above; it does not
claim that the two routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour: the rule is implemented and the tests above pass
against it.

Out of scope and pre-existing: the committed OpenAPI contract has no
`/api/v1/prescriptions/{id}/verify-partner-status` or
`/api/v1/prescriptions/{id}/retry` operation; both routes are baselined in
`contracts/openapi.yaml` (lines 300 and 302). Publishing them
would change `contracts/openapi.yaml`, regenerate the staff-web API client and
update the contract parity tests, and would remove the two baseline entries.
That is one separate contract work item tracked as two tasks in the backlog,
`FIX-OPENAPI-02` and `FIX-OPENAPI-04`. No production file, migration or existing
test was changed for this clause-evidence story.
