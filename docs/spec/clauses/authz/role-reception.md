# IAM-01 section 2 — role matrix: Reception (API test evidence)

## Clause

Role Reception: allowed workflow: Register, schedule, check in and manage patient contact data; scope constraint: Assigned facilities and demographic/scheduling fields.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.authz.clauses.RoleReceptionTest` (path: `apps/api/src/test/java/com/aurevia/smartclinic/authz/clauses/RoleReceptionTest.java`). All 11 tests are active; the targeted Surefire report records `tests="11" errors="0" skipped="0" failures="0"`. Fixtures are synthetic: fixed clock and UUIDs, mocked repository boundaries with an inline transaction template, and a MockMvc standalone controller with the synthetic-auth tenant interceptor.

Allowed workflow, one test per verb:

- `registerAsReceptionIsAllowed` — `ActionPolicy` allows `PATIENT_REGISTER` for `reception`, `EnrollmentActions` allows `INVITE_ISSUE`, and `IdentityActionPolicy` allows `ACCOUNT_ENROLL`; the patient role is refused registration.
- `scheduleAsReceptionIsAllowed` — `ActionPolicy` allows book, hold, confirm, reschedule, cancel and no-show for `reception`.
- `checkInAsReceptionIsAllowed` — the real `CheckInService.checkIn` admits an active reception member with verified identity, creates the worklist entry and appends a `VERIFIED_CHECK_IN` `SUCCESS` audit row; `WorklistPolicy` allows identity verification and worklist read for reception.
- `manageContactDataAsReceptionIsAllowed` — a real HTTP `POST /api/v1/patient/access/accounts/contact` as a reception subject reaches `PatientAccessController` and `PatientAccessService`, returns HTTP 200 and records a `CONTACT_CHANGED` `ownership_unchanged` access fact.

Refused verbs of other roles:

- `receptionCannotStartOrCompleteEncounter` — `ActionPolicy` refuses `APPOINTMENT_START` and `APPOINTMENT_COMPLETE` for reception while allowing them for clinician.
- `receptionCannotRecordVitalsOrOpenEncounter` — `WorklistPolicy` refuses `VITALS_RECORD` and `ENCOUNTER_OPEN`; the real `CheckInService.openEncounter` answers HTTP 403 `FORBIDDEN` ("missing worklist permission"), never calls `AppointmentService.start` and writes a `DENIED` audit row before the state machine is reached.
- `receptionCannotInviteStaffOrApproveConfig` — `ActionPolicy` refuses `STAFF_INVITE` and `CONFIG_APPROVE`, and `IdentityActionPolicy` refuses `LINK_UNLINK` and `LINK_REVERIFY` for reception while the owning roles are allowed.
- `patientSubjectCannotUseReceptionRoute` — the real HTTP contact-change route answers HTTP 403 `FORBIDDEN` for a patient subject; `EnrollmentActions` refuses contact edit and invitation issue to a patient while allowing patient self-service actions.

Scope narrowing:

- `anotherFacilityIsRefused` — `CheckInService.worklist` denies a sibling facility of the same organization with HTTP 403 ("outside the assigned scope") before any worklist read, with a `DENIED` audit row.
- `anotherOrganizationIsRefused` — the real `CommandAuthorization` command gate admits `registerPatient` for a member of the home organization and denies the same command with HTTP 403 `FORBIDDEN` (recorded reason `MEMBERSHIP_INACTIVE`) for the same subject selecting another organization.
- `handoffOfUnassignedItemIsRefused` — `CheckInService.handoff` refuses an item with no active assignee with HTTP 409 `NO_ACTIVE_ASSIGNEE` and performs no write.

Production classes exercised through their normal service or HTTP entry points: `com.aurevia.smartclinic.authz.ActionPolicy`, `com.aurevia.smartclinic.scheduling.checkin.WorklistPolicy`, `com.aurevia.smartclinic.patient.identity.IdentityActionPolicy`, `com.aurevia.smartclinic.messaging.enrollment.EnrollmentActions`, `com.aurevia.smartclinic.scheduling.checkin.CheckInService`, `com.aurevia.smartclinic.messaging.enrollment.PatientAccessService`, `com.aurevia.smartclinic.messaging.enrollment.PatientAccessController`, `com.aurevia.smartclinic.api.authorization.CommandAuthorization` and `CommandCatalog`.

Neighbouring active evidence, surveyed and not changed by this task: `authz.clauses.FacilityScopeEnforcementTest` and `authz.clauses.FacilityScopeFurtherNarrowAccessTest` (facility scope on all check-in commands), `messaging.enrollment.PatientAccessControllerTest` (patient-access HTTP adapter), `scheduling.clauses.EligibleRolesRecordVitalsOpenEncounterTest` (clinical-role refusal), and `messaging.enrollment.AuthorizePatientAccessThroughConnectionStaffAccessTest` (patient-link authorization). The older `docs/spec/clauses/authz/facility-scope-further-narrow-access.md` status is stale: its tests are active and facility scope is enforced today.

## Gap

The "demographic/scheduling fields" half of the scope constraint is **not built**. `com.aurevia.smartclinic.patient.PatientService.register` accepts `contacts`, `identifiers` and `consents` as raw JSON text with no role-based field allowlist and no guard that refuses clinical fields for a reception caller; `com.aurevia.smartclinic.messaging.enrollment.PatientAccessService.changeStaffContact` changes only a contact digest, so there is no field set to restrict. The enforced scope today is assigned facilities plus active membership in the selected organization, not a field-level boundary.

The would-prove test for the missing behaviour is recorded here rather than added as a skipped Java test: AGENTS.md forbids a skipped test and `scripts/check-constraints.py` rejects an added `@Disabled` line, so a skipped test would fail the worker and quality gates. The complete test to enable when field scope exists is:

```java
@Test
void fieldOutsideDemographicSchedulingIsRefused() {
    // Reception registers with a clinical field in the demographics payload.
    ApiException failure = assertThrows(ApiException.class, () -> patientService.register(
            "Synthetic Name", "MRN-100", "ISSUER", "1990-01-01", null, "en",
            "{\"phone\":\"000\",\"diagnosis\":\"E11\"}", "[]", "[]"));
    assertEquals(403, failure.status());
    assertEquals("FORBIDDEN", failure.code());
}
```

To build it: add a validated demographic/scheduling field schema at the patient-registration and contact-change trust boundaries, reject fields outside that schema for reception with a stable error code and a `DENIED` audit row, and add the test above plus the corresponding rejection test on the contact-change route. Files that would change: `apps/api/src/main/java/com/aurevia/smartclinic/patient/PatientService.java`, `apps/api/src/main/java/com/aurevia/smartclinic/messaging/enrollment/PatientAccessService.java`, their owning tests and the OpenAPI request schemas. Estimate: two implementation tasks plus one test-enablement task; a `task-requests` entry is filed in the worker notes for the first of these.

No other part of this row is in the gap. This task added proof only: no production code, existing test, contract, migration or tracker was changed.
