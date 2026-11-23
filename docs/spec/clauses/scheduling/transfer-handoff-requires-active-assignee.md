# CLN-02 clause 3 — transfer handoff requires an active assignee

## Clause

Transfer handoff requires an active assignee.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.scheduling.clauses.TransferHandoffRequiresActiveAssigneeTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest.java`).
It is a synthetic, database-free clause test in the same `clauses` style as
`authz/clauses/FacilityScopeEnforcementTest`: mocked `WorklistRepository` and
`SchedulingRepository`, a transaction template that runs the real guarded
callback, `Clock.fixed` and a synthetic tenant context. Every case enters
through `CheckInService.handoff`, the command called by the clinic controller.

- `handoffTransfersAnAssignedItemToAnotherActiveMemberAndRecordsTheEvent` — a
  worklist item whose current assignee still has active membership is transferred
  to another active member; the assignee and assignment timestamp change, the
  version increments and one append-only `HandoffEvent` records the source,
  target, reason, actor and instant (rule met).
- `handoffIsRefusedWhenTheItemHasNoAssignee` — an unassigned item is refused with
  409 `NO_ACTIVE_ASSIGNEE`, and neither the item nor the handoff ledger is
  written (negative: forbidden state).
- `handoffIsRefusedWhenTheCurrentAssigneeIsNoLongerActive` — a deactivated
  source assignee is refused with 409 `ASSIGNEE_INACTIVE` and no write occurs
  (negative: revoked membership fails closed on the next action, IAM-02).
- `handoffIsRefusedWhenTheReceivingMemberIsNotActive` — an inactive receiving
  member is refused with 422 `INVALID_ASSIGNEE` and no write occurs (negative:
  missing active target).

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.scheduling.checkin.CheckInService.handoff` — owns the
  rule: it validates the command, re-derives the actor's authorization and
  facility scope, locks the item, rejects a missing or deactivated source
  assignee, requires an active target, rejects a self-handoff, then commits the
  item update, the handoff event and the audit row in one transaction.
- `com.aurevia.smartclinic.scheduling.checkin.CheckInCommands.Handoff`,
  `WorklistItem`, `HandoffEvent`, `WorklistRepository` (`findItemForUpdate`,
  `updateItem`, `insertHandoff`), `WorkflowInputs` and `WorklistPolicy.HANDOFF`
  — the command, projection, ledger append, input guards and role/action matrix.
- `com.aurevia.smartclinic.scheduling.SchedulingRepository.hasActiveMembership`
  — the live membership source for both the source and target checks.
- `com.aurevia.smartclinic.scheduling.checkin.ClinicController` —
  `POST /api/v1/clinic/worklist/{id}/handoff` is the HTTP entry point; it
  injects `CheckInService` and parses the `If-Match` version.
- `db/migrations/V12__checkin_worklist.sql` — creates `public.worklist_entry`
  and `public.handoff_event` with tenant keys, FORCE ROW LEVEL SECURITY,
  append-only grants and the `worklist_entry_assignment_guard` trigger that
  re-checks `has_active_membership()` at write time, so deactivation also fails
  closed in the database.
- Contract survey: the handoff route is listed in
  `contracts/openapi.yaml` as
  `POST /api/v1/clinic/worklist/{}/handoff`; it is baselined there and is not
  described as an operation in `contracts/openapi.yaml`. That is separate
  contract-documentation debt and does not affect whether the behaviour is
  implemented and proven.

Broader coverage already present (not repeated here): the service-level branches
live in `it/schedulingcheckin/CheckInServiceTest` and the HTTP-plus-PostgreSQL
path, including the handoff ledger and RLS, lives in
`it/schedulingcheckin/CheckInWorklistIntegrationTest`.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=TransferHandoffRequiresActiveAssigneeTest test`
— `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
