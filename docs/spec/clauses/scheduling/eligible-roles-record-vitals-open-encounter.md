# CLN-02 clause 4 — API test evidence

## Clause

Only eligible roles can record vitals or open the encounter.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.scheduling.clauses.EligibleRolesRecordVitalsOpenEncounterTest`
(path: `apps/api/src/test/java/com/aurevia/scheduling/clauses/EligibleRolesRecordVitalsOpenEncounterTest.java`).
All six tests are active (none disabled) and pass with synthetic data only. Fixtures mirror the
neighbouring `authz.clauses` service tests: mocked `WorklistRepository`, `AppointmentService`,
`SchedulingRepository` and `AuditAppender`, an inline `TransactionTemplate`, a fixed `Clock` and a
`TenantContext` set per test and cleared afterwards.

- `clinicianOpensEncounterAfterIdentityWasVerified` — positive proof. An active `clinician` with
  verified identity reaches `CheckInService.openEncounter`, the appointment becomes
  `IN_PROGRESS`, `AppointmentService.start` is called and an `ENCOUNTER_OPEN` `SUCCESS` audit row
  is appended.
- `receptionCannotOpenEncounterEvenAfterIdentityWasVerified` — negative proof (wrong role). An
  active `reception` member is refused with HTTP 403 `FORBIDDEN` ("missing worklist permission")
  even when the identity is verified; `AppointmentService.start` is never called and a
  `DENIED` audit row is appended. The denial therefore precedes any state transition.
- `clinicalAssistantCannotOpenEncounterThoughItMayRecordVitals` — negative proof (ineligible
  clinical role). A `clinical_assistant` may record vitals but is refused `ENCOUNTER_OPEN` with
  403 and no state-machine call.
- `eligibleClinicalRolesMayRecordVitals` — positive proof for the vitals half. The authoritative
  scheduling role matrix allows `VITALS_RECORD` for `clinical_assistant` and `clinician`.
- `ineligibleRolesCannotRecordVitals` — negative proof. `reception`, `tenant_admin`,
  `privacy_auditor` and an unknown role are all refused `VITALS_RECORD`.
- `missingRoleFailsClosedForBothActions` — negative proof (missing input). A null role fails
  closed for both `VITALS_RECORD` and `ENCOUNTER_OPEN`.

Production classes exercised through their normal entry points:

- `com.aurevia.scheduling.checkin.WorklistPolicy` — the authoritative CLN-02 role
  matrix. `allows(VITALS_RECORD, role)` admits only `clinical_assistant` and `clinician`;
  `allows(ENCOUNTER_OPEN, role)` admits only `clinician`. Clerical roles, tenant administration,
  privacy audit, unknown and null roles fail closed.
- `com.aurevia.scheduling.checkin.CheckInService` — `openEncounter` re-derives active
  membership and roles from `SchedulingRepository` inside a tenant-guarded transaction and
  denies `ENCOUNTER_OPEN` before any transition; `authorize` writes a `DENIED` audit row on refusal
  and the happy path writes `SUCCESS`.
- `com.aurevia.scheduling.AppointmentService` and `AppointmentState` — the shared
  state machine reached only on an allowed encounter open (`CHECKED_IN` → `IN_PROGRESS`).

Neighbouring implementations observed in the source survey (not changed by this task):
`com.aurevia.chart.record.ChartService` records an actual `VITAL` chart entry under
`ChartActionPolicy.CHART_ENTRY_CREATE` (CLN-03, clinical roles only); `CommandCatalog` binds the
clerical CLN-02 actions to the `WorklistPolicy` owner; `ClinicController` in
`com.aurevia.scheduling.checkin` is the HTTP entry point that delegates to
`CheckInService`; the CLN-02 tables and role-grant folds live in the Flyway migrations under
`db/migrations` and the protected-command contract is generated in `contracts/openapi`.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the contract or a migration.
