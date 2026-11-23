# RX-02 clause 5 — Distinguish dispensing evidence from acceptance

## Clause

Distinguish dispensing evidence from acceptance.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.prescription.clauses.DistinguishDispensingEvidenceAcceptanceTest`
([DistinguishDispensingEvidenceAcceptanceTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/prescription/clauses/DistinguishDispensingEvidenceAcceptanceTest.java)).
It is a synthetic, database-free clause test in the same `clauses` style as
`TransferHandoffRequiresActiveAssigneeTest`: mocked `RxRepository` and
`AuditAppender`, an inline `TransactionTemplate` that runs the real guarded
callback, a directly constructed `RxService` and a synthetic tenant context.
Every case enters through `RxService.recordDispensing`, the command invoked by
the dispensing controller route.

- `pharmacyStaffRecordsDispensingEvidenceDistinctFromAcceptance` — from an
  `ACCEPTED` prescription with a matching submission id, a pharmacy staff actor
  moves the state to `DISPENSED`, increments the version, appends exactly one
  `DISPENSING_EVIDENCE` event (never a second `PHARMACY_ACCEPTED` event) and
  writes a `SUCCESS` audit. The pre-existing `PHARMACY_ACCEPTED` fact remains in
  the trail as a separate event, so acceptance and dispensing evidence coexist
  as distinct immutable facts (rule met).
- `clinicianCannotRecordDispensingEvidence` — a clinician actor is refused with
  403 `FORBIDDEN`; no state or event write occurs and a `DENIED` audit is
  recorded (negative: wrong role, `RxActions.DISPENSE` is pharmacy-only).
- `refusesDispensingBeforeAcceptance` — a `DELIVERY_CONFIRMED` prescription is
  refused with 409 `INVALID_TRANSITION` and no write occurs (negative: forbidden
  state; delivery is not acceptance).
- `refusesDispensingForAnotherSubmission` — a mismatched submission id is
  refused with 422 `SUBMISSION_MISMATCH` and no write occurs (negative: wrong
  stable submission).
- `refusesDispensingOnStaleVersion` — a stale version is refused with 412
  `STALE_VERSION` and no write occurs (negative: stale update).
- `refusesWithoutActiveMembership` — an actor without active membership is
  refused with 403 `FORBIDDEN` and a `DENIED` audit (negative: revoked access
  fails closed on the next protected action).

Production classes and files surveyed and exercised by the entry point:

- [RxService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxService.java)
  `recordDispensing` owns the rule: it validates the command, re-derives the
  actor's authorization and role, locks the row, checks the version and the
  `ACCEPTED` state, requires the matching stable submission id, then commits the
  `DISPENSED` state change, the `DISPENSING_EVIDENCE` event and the audit row in
  one tenant-guarded transaction. Its private `authorize`, `locked`,
  `requireVersion`, `requireState`, `requireSubmission` and `append` helpers are
  each taken by a named test above.
- [RxContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxContract.java)
  models `State.ACCEPTED` and `State.DISPENSED` as distinct states (only
  `ACCEPTED -> DISPENSED` is a legal forward move) and `EventType.PHARMACY_ACCEPTED`
  and `EventType.DISPENSING_EVIDENCE` as distinct append-only facts;
  `DispensingRequest` is the dispensing command. Its Javadoc states that
  acceptance is never dispensing.
- [RxActions.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxActions.java)
  restricts `RX_DISPENSE` to the `pharmacy_staff` role.
- [RxController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxController.java)
  `POST /api/v1/prescriptions/{id}/dispensing` is the HTTP entry point; it
  injects the service and parses the `If-Match` version.
- [V44__rx02_prescription_transmission.sql](../../../../db/migrations/V44__rx02_prescription_transmission.sql)
  enforces the separation in storage: `dispensed` is an allowed state,
  `dispensing_evidence` is an allowed event type, the `accepted -> dispensed`
  update guard is the only dispensing transition, and the event ledger is
  append-only under ENABLE and FORCE ROW LEVEL SECURITY.
- Entry-point-to-test trace: the HTTP and real PostgreSQL proof that the rule
  holds end to end lives in
  [RxTransmissionFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/it/prescription/RxTransmissionFlowTest.java).
  `electronicWorkflowTracksEveryFactSeparately` asserts "acceptance must not
  create dispensing evidence" after a pharmacy acceptance, then posts the
  dispensing route and asserts the ordered trail holds `pharmacy_accepted` and
  `dispensing_evidence` as separate events with `state=dispensed` and the
  incremented version. `unsafeStateTransitionsAndSubmissionMismatchesAreRejected`
  asserts the same HTTP endpoint refuses dispensing too early with 409
  `INVALID_TRANSITION` and with a foreign submission id as 422
  `SUBMISSION_MISMATCH`. `tenantIsolationConcealsIdenticalLocalIdentifiers`
  covers the wrong-tenant boundary for the workflow. The route-level security
  matrix in
  [RxHttpSecurityTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/it/prescription/RxHttpSecurityTest.java)
  parametrizes the dispensing endpoint through `missingSubjectReturnsUnauthorized`,
  `malformedOrganizationReturnsUnprocessable` and
  `unauthorizedRoleReturnsForbidden`, and
  `allowedSubjectReachesSuccessStatusOnEveryRoute` reaches the same endpoint
  with the pharmacy role. The new mock service test above isolates the domain
  rule and its guards; these HTTP tests prove the controller, tenant boundary
  and database trail, so together they justify `Proven by tests`.

Contract survey: the dispensing route is baselined in
[missing-routes.txt](../../../../contracts/openapi.yaml) but is
not described as an operation in
[openapi.yaml](../../../../contracts/openapi.yaml). That is pre-existing
contract-documentation debt, recorded here and in the task notes; it does not
affect whether the behaviour is implemented and proven, and this task does not
edit the contract.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security test -Dtest=DistinguishDispensingEvidenceAcceptanceTest`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
