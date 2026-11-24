# CLN-02 clause 2 — organization/facility worklist

## Clause

Staff see an organization/facility worklist ordered by scheduled time and configurable priority, with assignment and timestamps.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.scheduling.clauses.StaffSeeOrganizationFacilityWorklistOrderedScheduledTest`
(path: `apps/api/src/test/java/com/aurevia/scheduling/clauses/StaffSeeOrganizationFacilityWorklistOrderedScheduledTest.java`).
All three tests are active (none disabled), use synthetic data only, and drive
`GET /api/v1/clinic/worklist` over HTTP against a real PostgreSQL database seeded
with the production Flyway migrations.

- `worklistIsOrderedByScheduledTimeThenPriorityWithAssignmentAndTimestamps` — positive
  proof. Three synthetic worklist rows share the `MAIN` facility: two start at 09:00
  with stored priorities 7 and 2, one starts at 11:00 with priority 2. The response
  is asserted to be exactly `[priority-7 09:00, priority-2 09:00, priority-2 11:00]`,
  proving the scheduled-time sort and the priority tie-break. The 09:00 priority-2 row
  carries `assigneeSubject=nurse-a` and a non-null `assignedAt`, and every row
  exposes its `startsAt`, proving the assignment and timestamp fields are present.
- `worklistDeniesAStaffRoleWithoutWorklistPermission` — negative proof. A
  `privacy_auditor` with active membership in the same organization is refused with
  HTTP 403 `FORBIDDEN` because the role lacks the `WORKLIST_READ` action.
- `worklistDoesNotExposeAnotherTenantsEntries` — negative proof. A reception member
  of a second tenant reading the same `MAIN` facility code receives HTTP 200 with a
  worklist that does not contain the first tenant's entry id; tenant isolation is
  enforced by forced row-level security, not by the facility code alone.

Production classes exercised through the normal HTTP entry point
(`GET /api/v1/clinic/worklist`):

- `com.aurevia.scheduling.checkin.ClinicController` — thin adapter that
  delegates the worklist read; no service construction inside the controller.
- `com.aurevia.scheduling.checkin.CheckInService` — `worklist(...)` validates
  the facility code and limit, re-derives membership and the `WORKLIST_READ` role
  permission on each request, applies the facility-scope guard, appends a `WORKLIST_READ`
  audit event and returns an immutable copy of the ordered rows.
- `com.aurevia.scheduling.checkin.WorklistRepository` — `listWorklist(...)`
  with `ORDER BY COALESCE(a.starts_at, w.created_at) ASC, w.priority DESC, w.created_at ASC, w.id ASC`,
  the row mapper for `WorklistItem` (scheduled time, stored priority, assignee and
  assignment timestamp), all under the transaction-local tenant GUC.
- `com.aurevia.scheduling.checkin.WorklistPolicy` — grants `WORKLIST_READ`
  to `tenant_admin`, `reception`, `clinical_assistant` and `clinician`, and denies it
  to `privacy_auditor` (negative test).
- `com.aurevia.scheduling.checkin.WorklistItem` — the returned projection
  carrying `startsAt`, `priority`, `assigneeSubject`, `assignedAt` and `version`.
- `db/migrations/V12__checkin_worklist.sql` — creates `worklist_entry` with the tenant
  key in its primary key and foreign keys, ENABLE/FORCE row-level security with a
  tenant policy, an assignment guard requiring active membership, immutable
  ownership columns and version-advancing update guard; `V93__membership_facility_scope.sql`
  backs the facility-scope guard.

Neighbouring implementations observed in the source survey (not changed by this task):
`com.aurevia.scheduling.AppointmentService` owns the V8 appointment state
machine used to create checked-in worklist rows; the existing
`it/schedulingcheckin/CheckInWorklistIntegrationTest.worklistOrdersByScheduledTimeAndConfiguredPriority`
and `configuredPriorityIsAppliedToWorklistItems` prove that stored priority is resolved
from the approved `clinic.worklist.priority.<visitType>` configuration setting at
check-in; `assignmentHandoffAndRevocation` proves assignment and timestamp writes;
`authz/clauses/FacilityScopeFurtherNarrowAccessTest` covers facility scope narrowing.
No contract under `contracts/` was changed; the worklist route is served by the
existing `ClinicController` mapping.

## Gap

None.

The clause is built and this task adds focused read-side proof only: it did not change
production code, an existing test, the OpenAPI contract or a migration. Priority
resolution from site configuration is proven by the neighbouring integration test
named above; the ordering, assignment and timestamp contract of the worklist read is
proven by the active tests in this clause's test class.
