# RX-02 clause 4 — track transmission events separately

## Clause

Track submitted, delivery confirmed, pharmacy accepted/rejected, clarification, renewal and cancellation events separately.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.prescription.clauses.TrackSubmittedDeliveryConfirmedPharmacyAcceptedRejectedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/prescription/clauses/TrackSubmittedDeliveryConfirmedPharmacyAcceptedRejectedTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants
sharing local identifiers. It drives the real REST entry point and reads the
committed append-only ledger through `GET /api/v1/prescriptions/{id}`; it does
not assert against a domain enum alone. Two synthetic prescriptions are used in
the positive test because acceptance and rejection are mutually exclusive
outcomes in the state machine.

Each of the seven clause event types is asserted in an exact ordered
`/events` list:

| Clause event | Wire `eventType` | Asserting test and assertion |
|---|---|---|
| submitted | `submitted` | `tracksSubmittedDeliveryConfirmedAcceptedRejectedClarificationRenewalAndCancellationAsSeparateEvents` — index 1 of both the seven-event and four-event trails, bound to the stable `submissionId` |
| delivery confirmed | `delivery_confirmed` | same test — index 2 of both trails; `confirmationBeforeSubmissionIsRefused` proves it cannot be appended early |
| pharmacy accepted | `pharmacy_accepted` | same test — index 4 of the seven-event trail; `dispensingEvidenceIsRecordedAsAFactDistinctFromAcceptance` — index 3 and exactly one row, with no dispensing evidence at that point |
| pharmacy rejected | `pharmacy_rejected` | same test — index 3 of the second prescription's four-event trail and no `pharmacy_accepted` row there |
| clarification | `clarification` | same test — index 3 of the seven-event trail, before acceptance, proving it is not merged into the acceptance fact |
| renewal | `renewal` | same test — index 5 of the seven-event trail; the state stays `accepted` while the version advances and the fact links to the original `submissionId` |
| cancellation | `cancelled` | same test — index 6 of the seven-event trail, a live `accepted` prescription ending in its own fact; `cancellingATerminalPrescriptionIsRefused` proves it cannot overwrite a rejection |

Additional positive coverage:

- `dispensingEvidenceIsRecordedAsAFactDistinctFromAcceptance` — drives
  `accepted → dispensing` on one prescription and asserts the ordered trail
  `[attested, submitted, delivery_confirmed, pharmacy_accepted,
  dispensing_evidence]`, one event of each acceptance and dispensing type, and
  the dispensing event's stable `submissionId` and `outcome`. Acceptance alone
  created no dispensing evidence.

Negative cases, each asserting the HTTP status, the stable error `code` and an
unchanged event count or state:

- `clinicianCannotRecordAPharmacyResponse` — a clinician's pharmacy response is
  refused with 403 `FORBIDDEN`; prescribing and pharmacy facts are separated.
- `pharmacyStaffCannotSubmitOrAttest` — a pharmacy actor cannot attest or
  transmit; 403 `FORBIDDEN`.
- `peerTenantCannotReadOrDriveAnotherTenantsTrail` — a peer tenant's read and
  transition against another tenant's id are concealed as 404 `NOT_FOUND` by
  forced row level security.
- `staleVersionCannotRecordAnEvent` — a one-behind `If-Match` is refused with
  412 `STALE_VERSION`.
- `confirmationBeforeSubmissionIsRefused`,
  `dispensingBeforeAcceptanceIsRefused`, `renewingADraftIsRefused`,
  `cancellingATerminalPrescriptionIsRefused` and
  `pharmacyResponseFromSubmittedStateIsRefused` — forbidden source states are
  refused with 409 `INVALID_TRANSITION` and append no event.
- `wrongSubmissionIdIsRefused` — a confirmation naming a different submission
  is refused with 422 `SUBMISSION_MISMATCH`.
- `missingIfMatchIsRefused` — a command without `If-Match` is refused at the
  HTTP boundary with 428 `PRECONDITION_REQUIRED` before any event is appended.

Production classes exercised through `POST /api/v1/prescriptions` and its
`/{id}/attest`, `/{id}/transmit`, `/{id}/confirm-delivery`,
`/{id}/pharmacy-response`, `/{id}/dispensing`, `/{id}/renew` and `/{id}/cancel`
routes plus `GET /api/v1/prescriptions/{id}`:

- `com.aurevia.smartclinic.prescription.rx.RxController` — thin adapter parsing
  `If-Match` and delegating each command to the service.
- `com.aurevia.smartclinic.prescription.rx.RxService` — `transmit`,
  `confirmDelivery`, `pharmacyResponse`, `recordDispensing`, `renew` and
  `cancel` append one matching event each in the same tenant-guarded
  transaction as the state change; `authorize`, `requireVersion`,
  `requireState`, `requireSubmission` and `requirePrescriber` enforce the
  refusals.
- `com.aurevia.smartclinic.prescription.rx.RxContract` — `EventType` declares
  the seven clause facts as distinct values plus `DISPENSING_EVIDENCE`, and
  `State.canTransitionTo` gates the legal moves.
- `com.aurevia.smartclinic.prescription.rx.RxActions` — bounded clinician and
  pharmacy role matrix used by `RxService.authorize`.
- `com.aurevia.smartclinic.prescription.rx.RxRepository` — `appendEvent` writes
  each immutable fact and `events(id)` reads the ordered trail;
  `insertSubmission` creates the one logical submission row asserted by the
  test.
- `db/migrations/V44__rx02_prescription_transmission.sql` and
  `db/migrations/V59__rx02_ambiguous_transition.sql` — the `rx02_event`
  append-only ledger with its `event_type` check, forced tenant RLS, immutable
  event triggers and the database state-transition guard.

The first targeted run failed to compile because the test called an
`eventTypes(org, subject, id)` helper before that overload existed; adding the
helper made the run green. This was a test-authoring error, not a clause gap;
no production code was changed.

## Gap

None. All seven clause event types are implemented as separate append-only
facts and proven end to end through the HTTP boundary. The
`/api/v1/prescriptions` action routes are not yet published in
`contracts/openapi.yaml` (they are listed in
`contracts/openapi.yaml`), which is pre-existing, separately
tracked contract debt outside this test-only story; the test drives the real
controller routes directly.
