# MSG-06 clause 5 — supervisor snapshot

## Clause

Surface backlog, unassigned work, failed notifications and overdue responses to unit supervisors.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.messaging.clauses.SurfaceBacklogUnassignedWorkFailedNotificationsOverdueTest`
(path: `apps/api/src/test/java/com/aurevia/messaging/clauses/SurfaceBacklogUnassignedWorkFailedNotificationsOverdueTest.java`).
All four tests are active (none disabled), use synthetic data only, and drive
`GET /api/v1/care-units/{unitId}/supervisor-snapshot` over HTTP against a real
PostgreSQL scratch database created and migrated with the production Flyway
migrations. Two tenants share one local unit identifier; the owning tenant holds
the work facts and the effective 30-minute service profile.

- `supervisorSnapshotSurfacesBacklogUnassignedFailedNotificationsAndOverdueForOwnUnit`
  — positive proof. Six synthetic work facts are written through the durable
  `ServiceProfileStore.addWorkItem` and the assigned supervisor reads the snapshot
  over HTTP. The response is asserted to be exactly `backlog=4`, `unassigned=2`,
  `failedNotifications=1`, `overdueResponses=1`, with the owning `organizationId`,
  the requested `unitId` and a `generatedAt` inside the request window. The fact
  mix exercises every document branch: three awaiting-staff and one awaiting-patient
  item are open (backlog) while a resolved and a closed item are excluded; a null
  and a blank assignee are unassigned while two named assignees are not; the one
  failed notification sits on a resolved item and is still surfaced; only the
  awaiting-staff item with no staff response and an elapsed deadline is overdue,
  while an already-responded item, an awaiting-patient item and a not-yet-due item
  are not. Deadline anchors are years away from the real clock so the split cannot
  flip with test execution time.
- `patientWithoutSupervisorRoleIsDeniedTheSnapshot` — negative proof. An active
  patient member of the owning tenant receives HTTP 403 `FORBIDDEN` with message
  `missing SUPERVISOR_VIEW permission` and no counter fields, proving the refusal
  comes from the missing supervisor action rather than from a missing membership.
- `unassignedSupervisorCannotReadAnothersUnitSnapshot` — negative proof. A
  `unit_supervisor` with no `service_profile_unit_assignment` for the unit receives
  HTTP 404 `NOT_FOUND` (`unit not found`) and no counter fields.
- `supervisorFromAnotherTenantCannotReadThisUnitsSnapshot` — negative proof. A
  supervisor of the second tenant, where the same local unit identifier exists,
  receives HTTP 404 `NOT_FOUND`; a direct store read under that tenant's context
  returns no work items for the shared unit and none of the owning tenant's work
  row ids. With the same local unit id in both tenants, the concealment and the
  empty read are consistent with the tenant-forced RLS design and show the unit
  identifier alone does not grant access.

Production classes exercised through the normal HTTP entry point
(`GET /api/v1/care-units/{unitId}/supervisor-snapshot`):

- `com.aurevia.messaging.service.ServiceProfileController` — thin
  adapter; `supervisor(...)` delegates to the injected service.
- `com.aurevia.messaging.service.ServiceProfileService` —
  `supervisorSnapshot(...)` derives the actor and roles from `TenantContext`,
  checks `SUPERVISOR_VIEW`, applies the unit-access guard, resolves the effective
  response target and computes and audits the snapshot.
- `com.aurevia.messaging.service.ServiceProfileActions` — bounded
  action matrix granting `SUPERVISOR_VIEW` only to `unit_supervisor` and
  `tenant_admin` (negative: patient 403).
- `com.aurevia.messaging.service.SupervisorSnapshotCalculator` —
  derives the four counters from one immutable snapshot with a single `now`.
- `com.aurevia.messaging.service.ServiceProfileContract` — the
  `WorkItemFact`, `WorkState` and `SupervisorSnapshot` records carried over HTTP.
- `com.aurevia.messaging.service.JdbcServiceProfileStore` —
  `workItems(...)` and `addWorkItem(...)` against the tenant-guarded work-item
  table.
- `db/migrations/V92__service_profiles.sql` — creates `service_profile_work_item`
  and `service_profile_unit_assignment` with ENABLE + FORCE row-level security
  and a tenant policy; no migration was changed by this task.

The exact inclusive-deadline equality boundary is a calculator-level concern and
is also proved directly by
`com.aurevia.messaging.service.SupervisorSnapshotCalculatorTest.overdueUsesAnInclusiveDeadlineAndIgnoresAlreadyRespondedWork`;
this clause test proves the same rule reaches an authorized supervisor over HTTP.

## Gap

None.

No in-scope behavioural gap: the clause is fully built and proven by the active
tests above; no production, migration, contract or existing-test change was
needed.
