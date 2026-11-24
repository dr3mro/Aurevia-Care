# PAT-01 clause 1 — list appointments with facility time zone and status, allow confirmation where enabled, and record the timestamp

## Clause

List appointments with facility time zone and status, allow confirmation where enabled, and record the timestamp.

## Status

Proven by tests

## Evidence

All links are relative to this file; the repository root is four levels up. Only
repository files are cited. All fixtures are synthetic and the only network
seam is a mocked Transport. Evidence is tool evidence (an executed Jest,
typecheck and lint gate) plus simulated reviewer verdicts; no workflow-run
output, partner confirmation, signed clinical decision or real-patient
observation is stored here and none is claimed.

**New clause test.** [list-appointments-facility-time-zone-status-allow.test.ts](../../../../apps/patient-mobile/src/spec/patient-access/list-appointments-facility-time-zone-status-allow.test.ts),
`describe('PAT-01 clause 1 — facility zone, status, gated confirmation and server instant')`:

- `listsFacilityZoneAndStatusAndRecordsTheServerInstant` — two tenant-scoped
  rows are projected with the server's facility IANA zone and authoritative
  status, ordered by UTC start; the pending affordance is derived; the confirm
  command posts `patientId` plus the exact read `expectedVersion` and returns
  the server's `confirmedAt`/`version`; the recorded instant is then rendered
  in the same facility zone without mutating the original row.
- `refusesConfirmationWhenSiteFlagOrStatusDisallowsIt` — a false site flag, a
  false per-appointment server flag, or any non-confirmable status yields
  `unavailable`, and attempting to confirm that state returns `NOT_ELIGIBLE`.
- `refusesAStaleVersionWithAReloadOutcome` — the typed `conflict` outcome for
  HTTP 409 and 412 becomes only `{ kind: 'stale' }`, a reload signal.
- `refusesConfirmationWithoutAuthentication` — an unauthenticated confirm
  returns `UNAUTHENTICATED` at the domain boundary and the client returns
  `{ kind: 'unauthenticated' }`, disclosing no instant or eligibility.
- `keepsTheFirstRecordedInstantWhenAnAlreadyConfirmedRowIsReplayed` — a retry
  on a reloaded confirmed row is `idempotent` and never moves the first
  server-recorded instant.

Production units exercised by this test: [appointments.ts](../../../../apps/patient-mobile/src/features/appointments/appointments.ts)
(`appointmentView`, `confirmedAppointmentView`, `sortByScheduled`),
[confirmation.ts](../../../../apps/patient-mobile/src/features/appointments/confirmation.ts)
(`deriveConfirmation`, `confirm`),
[confirmClient.ts](../../../../apps/patient-mobile/src/features/appointments/confirmClient.ts)
(`confirmAppointment`), [tenancy.ts](../../../../apps/patient-mobile/src/features/appointments/tenancy.ts)
(`tenantKey`, `lookup`) and the shared `CONFIRMABLE_STATUSES` definition in
[types.ts](../../../../apps/patient-mobile/src/features/appointments/types.ts).

**Entry-point-to-test trace (installed page and API).** The clause is reachable
from the signed-in app, not only from pure functions. [patientPages.tsx](../../../../apps/patient-mobile/src/app/patientPages.tsx)
loads the patient's own list through `loadAppointments` and wires the
confirm button at lines 240-242 to the `confirm` callback at lines 187-223:
a synchronous in-flight claim, a versioned `confirmAppointment` post, the
server `confirmedAt` applied through `confirmedAppointmentView`, a reload on
`stale`, a session expiry on `unauthenticated`, and no change to the row on
other failures. [providers.ts](../../../../apps/patient-mobile/src/app/providers.ts)
validates the `GET /api/v1/patient/appointments` response, takes
`facilityTimeZone` from the server, derives the affordance and projects each
row. That installed page is covered by [patientConfirm.test.tsx](../../../../apps/patient-mobile/src/app/patientConfirm.test.tsx)
(`describe('patient appointment confirmation through the installed page')`):
`sends the version read from the row and renders the server-confirmed instant`
(asserts the versioned POST and the rendered `13:05` server instant),
`sends only one request for repeated presses while the first is pending`,
`reloads the list after a stale version instead of showing a confirmation`,
`allows a retry after a server failure and keeps the row unchanged`,
`reconciles a lost response when the server reports the row is no longer confirmable`
and `ends the session when confirmation is rejected as unauthenticated`.
[patientConfirmGuard.test.tsx](../../../../apps/patient-mobile/src/app/patientConfirmGuard.test.tsx)
adds `drops a second confirm that arrives before the first command settles`.

The server side is [PatientAppointmentController.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/PatientAppointmentController.java)
(`POST /api/v1/patient/appointments/{appointmentId}/confirm`, line 77) and its
HTTP-level test [PatientAppointmentControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/messaging/appointments/PatientAppointmentControllerHttpTest.java)
(`drivesTheWholePatientAppointmentWorkflowOverHttp` asserts the listed
`facilityTimeZone`, the advanced version and the recorded `confirmedAt`;
`staleAndConcealedCommandsAreRejected` asserts refusal of a non-confirmable,
missing or another patient's appointment).

Verified locally: `bash apps/patient-mobile/check.sh` → exit 0,
`tsc --noEmit` and `eslint .` with no findings, 83 suites / 1162 tests passing,
100% statements, branches, functions and lines on all files including the
appointments feature, and `python3 -I scripts/check-docs.py` → exit 0. A fault
probe that temporarily changed one facility-zone assertion failed as expected
(`Expected: "Europe/London"`, `Received: "Asia/Riyadh"`), then was restored.

## Gap

None for this clause. The facility-zone and status list, the site-gated confirm
affordance and the server-recorded instant are built and proven by active tests
through the installed page and the API HTTP entry point. This task added proof
only; it changed no production code, existing test, contract or migration.

Adjacent debts are out of this clause's scope and are not claimed as proven
here: the API's appointment ports remain synthetic and patient self-service
routes are absent from the published OpenAPI contract. The wider PAT-01
whole-requirement gaps are recorded in the
[product clause note](../product/verified-adult-patients-see-appointments-released-reports.md).
No real-patient, clinical or partner evidence is claimed.
