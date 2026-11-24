# CLN-02 clause 1 — API test evidence

## Clause

Reception verifies identity and checks in.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.scheduling.clauses.ReceptionVerifiesIdentityChecksTest`
(path: `apps/api/src/test/java/com/aurevia/scheduling/clauses/ReceptionVerifiesIdentityChecksTest.java`).
All five tests are active (none are disabled) and use synthetic data only.

- `receptionVerifiesIdentityThenChecksInAndCreatesAnAuditedWorklistItem` — positive proof.
  An active `reception` member records two-identifier evidence through `CheckInService.verify`,
  then checks the same appointment in through `CheckInService.checkIn`. The test asserts the
  stored `Verification` (appointment, patient, facility, identifier categories, verifier,
  time), the `insertVerification` persistence call with the `SUCCESS` `IDENTITY_VERIFY` audit
  row, and the checked-in `WorklistItem` with its configured priority and the `SUCCESS`
  `VERIFIED_CHECK_IN` audit row.
- `checkInRefusesAnUnverifiedPatient` — negative proof. Without a verification record,
  check-in fails with HTTP 409 `VERIFICATION_REQUIRED`; no state transition is requested and
  no worklist entry is inserted.
- `verifyRefusesARoleWithoutIdentityPermission` — negative proof of role. A `clinical_assistant`
  with active membership lacks the `IDENTITY_VERIFY` action and is refused with HTTP 403
  `FORBIDDEN`; the denial is audited and no evidence is stored.
- `verifyRefusesAFacilityOutsideAssignedScope` — negative proof of facility scope. A
  receptionist whose assignment excludes the appointment's facility is refused with HTTP 403
  `FORBIDDEN`; the denial is audited and no evidence is stored.
- `checkInRefusesStaleAppointmentVersion` — negative proof of the optimistic-lock guard. A
  stale `If-Match` version yields HTTP 412 `STALE_VERSION` and no worklist entry is inserted.

This class is clause-focused service-level evidence. The end-to-end HTTP proof that both
routes persist to PostgreSQL is the existing
`com.aurevia.it.schedulingcheckin.CheckInWorklistIntegrationTest`, which posts to
`POST /api/v1/clinic/verifications` and `POST /api/v1/clinic/appointments/{id}/check-in` against
the real controller and store. The thin controller delegation is covered separately by
`it.schedulingcheckin.ClinicControllerTest`.

Production classes exercised through the normal command entry point
(`CheckInService.verify` and `CheckInService.checkIn`):

- `com.aurevia.scheduling.checkin.CheckInService` — owns identity evidence, the
  verification-before-check-in invariant, facility scope, version precondition and the
  transactional `SUCCESS`/`DENIED` audit pair.
- `com.aurevia.scheduling.checkin.WorklistPolicy` — grants `IDENTITY_VERIFY` and
  `CHECK_IN` to `reception` and withholds `IDENTITY_VERIFY` from `clinical_assistant`.
- `com.aurevia.scheduling.checkin.Verification` — immutable evidence record that
  retains identifier categories, never their values.
- `com.aurevia.scheduling.checkin.WorklistItem` — the checked-in arrival entry.
- `com.aurevia.scheduling.checkin.WorklistRepository` — stores verification and
  worklist rows and answers the assigned-facility scope check.
- `com.aurevia.scheduling.AppointmentService` and `Appointment` — the shared owner
  of the `booked → checked_in` state machine and version.
- `com.aurevia.scheduling.checkin.ClinicController` — the thin HTTP adapter that
  exposes both commands as `POST /api/v1/clinic/verifications` and
  `POST /api/v1/clinic/appointments/{id}/check-in`; its delegation is covered by
  `it.schedulingcheckin.ClinicControllerTest` and its real HTTP persistence by
  `it.schedulingcheckin.CheckInWorklistIntegrationTest`.

Neighbouring implementations observed in the source survey (not changed by this task): the
`WorklistRepository` JDBC store under `scheduling/checkin`, the CLN-02 routes in
`ClinicController`, the existing unit proof in `it.schedulingcheckin.CheckInServiceTest` and
`it.schedulingcheckin.ClinicControllerTest`, the real-PostgreSQL HTTP proof in
`it.schedulingcheckin.CheckInWorklistIntegrationTest`, and the scheduling worklist tables in the
Flyway migrations under `db/migrations`.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the contract or a migration.
