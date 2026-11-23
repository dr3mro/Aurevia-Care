# RX-02 clause 2 — route through an approved prescribing channel or produce a locally approved printable prescription

## Clause

Route through an approved prescribing channel or produce a locally approved printable prescription.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.prescription.clauses.RouteThroughApprovedPrescribingChannelProduceLocallyTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/prescription/clauses/RouteThroughApprovedPrescribingChannelProduceLocallyTest.java`).
It is a synthetic, database-free **service-boundary** proof in the same `clauses` style as
[TransferHandoffRequiresActiveAssigneeTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest.java):
mocked `RxRepository` and `AuditAppender`, a `TransactionTemplate` that runs the real guarded
callback inline, a real `RxActions` matrix, `TenantContext.set(...)` with a synthetic subject and
organization, and a synthetic amoxicillin prescription. Every case enters through the command the
production controller calls (`RxService.transmit` or `RxService.printable`) against the **real**
`RxService` — the service itself is not mocked. No HTTP server or PostgreSQL instance is used; the
HTTP-plus-database path is the integrator's gate.

- `routesAnAttestedPrescriptionThroughAnApprovedElectronicChannel` — an `ATTESTED` row owned by the
  acting clinician at version 2, with market entitlement enabled and both channels approved, is
  routed through `E_PRESCRIBE`. The returned prescription is `SUBMITTED`, records `E_PRESCRIBE` as
  the actual channel and carries a non-null stable submission id equal to the one inserted into the
  submission ledger; the repository is verified to record `State.SUBMITTED`, append a `SUBMITTED`
  event and write a `SUCCESS` audit row (rule met, electronic disjunct).
- `issuesALocallyApprovedPrintablePrescription` — a `PRINTED` row on `PRINTABLE_LOCAL` with an
  `ATTESTED` event produces a `PrintablePrescription` carrying the recorded channel verbatim, the
  prescriber's attestation note and its instant. No submission is inserted and no electronic state
  transition is written (rule met, local-print disjunct).
- `refusesAnUnapprovedChannel` — an `E_PRESCRIBE` request when only `PRINTABLE_LOCAL` is approved
  fails with 422 `UNAPPROVED_CHANNEL` and writes no submission or transmission.
- `refusesAWrongPrescriber` — a row owned by another clinician fails with 403 `FORBIDDEN`, so only
  the prescribing clinician can route.
- `refusesACallerWithoutAnActiveMembership` — no active membership fails closed with 403
  `FORBIDDEN` and a `DENIED` audit row.
- `refusesACallerWithoutTheTransmitRole` — an active role that lacks the transmit permission fails
  closed with 403 `FORBIDDEN` and a `DENIED` audit row.
- `refusesAStaleVersion` — a version mismatch fails with 412 `STALE_VERSION` before any send.
- `refusesADraftThatWasNeverAttested` — a `DRAFT` row fails with 409 `INVALID_TRANSITION`.
- `refusesPrintWithoutAttestation` — a locally printed row with no `ATTESTED` event is refused with
  409 `ATTESTATION_REQUIRED`.

Production classes and files surveyed and exercised by these entry points:

- [RxService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxService.java):
  `transmit` (lines 136–162) validates the channel and outcome, re-derives authorization,
  entitlement and channel approval in one tenant-guarded transaction, then branches on
  `RxChannel.isElectronic()` — electronic routes insert one stable submission, record `SUBMITTED`
  or `AMBIGUOUS` and append a `SUBMITTED` event; local routes record `PRINTED` with no submission.
  `printable` (403–414) re-derives entitlement and local-channel approval before producing the
  document. `authorize` (433–444), `requireEntitlement` (490–499), `requireApprovedChannel`
  (501–508), `parseChannel` (510–516) and the `locked*`/`require*` guards (446–535) supply the
  refusal paths asserted above.
- [RxChannel.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxChannel.java):
  `E_PRESCRIBE` (electronic) and `PRINTABLE_LOCAL` (local) wire keys and their families.
- [LocalPrescriptionPrint.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/LocalPrescriptionPrint.java):
  `issue(View)` requires state `PRINTED`, the `PRINTABLE_LOCAL` channel and an `ATTESTED` event,
  producing an immutable [PrintablePrescription.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/PrintablePrescription.java)
  that copies the recorded channel and carries no submission or pharmacy outcome.
- [RxContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxContract.java),
  [RxInputs.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxInputs.java),
  [RxRepository.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxRepository.java)
  and [RxActions.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxActions.java):
  commands, state and event vocabularies, input guards, the single SQL boundary and the
  clinician-only transmit permission.
- [RxController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxController.java):
  `POST /api/v1/prescriptions/{id}/transmit` (line 106) and `GET /api/v1/prescriptions/{id}/printable`
  (line 79) are the HTTP entry points; the controller injects `RxService` and parses `If-Match`.
- [V44__rx02_prescription_transmission.sql](../../../../db/migrations/V44__rx02_prescription_transmission.sql):
  creates `rx02_prescription`, `rx02_submission` and `rx02_event` with tenant keys, one-submission
  constraints, append-only triggers and `ENABLE` plus `FORCE ROW LEVEL SECURITY` with a
  `tenant_isolation` policy on all three tables (lines 115–123).
  [V59__rx02_ambiguous_transition.sql](../../../../db/migrations/V59__rx02_ambiguous_transition.sql)
  widens the update guard so an ambiguous first send out of `attested` is legal.
- Contract survey: `contracts/openapi.yaml` has no prescription operation. The routes above are
  baselined in [missing-routes.txt](../../../../contracts/openapi/missing-routes.txt) (lines 93–94
  and 293–302). That is separate contract-documentation debt recorded under `## Gap`, not a defect
  in the behavior proven here.

Verified locally with `mvn -B -f apps/api/pom.xml -Pno-security -Dtest=RouteThroughApprovedPrescribingChannelProduceLocallyTest test`
— `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None for this clause's behaviour: both disjuncts are implemented and proven by the tests above.

Non-blocking adjacent debt (not changed by this test-only task): the two prescription entry points
`POST /api/v1/prescriptions/{}/transmit` (missing-routes.txt line 301) and
`GET /api/v1/prescriptions/{}/printable` (line 94), together with the other prescription routes,
are baselined as missing from `contracts/openapi.yaml`. Documenting them and regenerating the staff
web client needs one separate WEB-05 contract task; no production code, existing test, contract or
migration was edited here.
