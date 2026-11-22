# CLN-02 clause 5 — Link unscheduled encounters to intake

## Clause

Link unscheduled encounters to intake.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.scheduling.clauses.LinkUnscheduledEncountersIntakeTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/scheduling/clauses/LinkUnscheduledEncountersIntakeTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants that
share the same facility and patient-local codes. It proves only this clause:

- `unscheduledArrivalLinksToIntakeAndAppearsOnOwnFacilityWorklist` — a receptionist
  posts a walk-in (facility code, verified patient, reason) with no appointment;
  the API returns 201 with an entry whose `appointmentId` is null and
  `intakeReason` is set, the row is persisted as a worklist entry with no
  appointment, it appears on that facility's worklist, and a successful
  `INTAKE_LINK` audit row is committed.
- `clinicianCannotLinkUnscheduledArrivalToIntake` — an active clinician, who holds
  no intake-link permission, is refused with 403 and a `FORBIDDEN` code, no
  worklist row is created, and a denied `INTAKE_LINK` audit row is committed.
- `intakeOfAnotherTenantsPatientIsRefused` — a receptionist who does hold the
  permission cannot link a patient owned by another tenant into this tenant's
  intake (404 `NOT_FOUND`), no row is created, and the foreign patient never
  appears on this tenant's worklist.

Entry point: `POST /api/v1/clinic/worklist/intake` and `GET /api/v1/clinic/worklist`.
Production classes exercised through that entry point:

- `com.aurevia.smartclinic.scheduling.checkin.ClinicController` — thin controller
  receiving the service by injection and returning 201 with the new entry.
- `com.aurevia.smartclinic.scheduling.checkin.CheckInService.linkIntake` — validates
  the command, re-checks active membership, the `INTAKE_LINK` permission and
  facility scope inside the tenant-guarded transaction, checks the patient exists,
  resolves the facility and inserts the worklist row with a null appointment.
- `com.aurevia.smartclinic.scheduling.checkin.CheckInCommands.Intake` — the typed
  request body at the web boundary.
- `com.aurevia.smartclinic.scheduling.checkin.WorklistPolicy` — grants `INTAKE_LINK`
  to `tenant_admin`, `reception` and `clinical_assistant`, but not to `clinician`
  or `privacy_auditor`.
- `com.aurevia.smartclinic.scheduling.checkin.WorkflowInputs` — guards the facility
  code, patient UUID and required reason before any SQL runs.
- `com.aurevia.smartclinic.scheduling.checkin.WorklistRepository` — the RLS-bounded
  store that inserts the unscheduled row and reads the worklist.
- `com.aurevia.smartclinic.scheduling.checkin.WorklistItem` — the returned projection,
  distinguishing a walk-in (intake reason, no appointment) from a booking.
- Row level security and FORCE RLS for `worklist_entry` and the audit ledger come
  from the Flyway migrations under `db/migrations`.

Discovery: the intake route is currently listed in
`contracts/openapi/missing-routes.txt` rather than documented in
`contracts/openapi.yaml`. That is a separate contract task and is out of scope
for this clause test; it does not affect the clause's API behaviour.

Verified locally with `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=LinkUnscheduledEncountersIntakeTest test`.

## Gap

None.
