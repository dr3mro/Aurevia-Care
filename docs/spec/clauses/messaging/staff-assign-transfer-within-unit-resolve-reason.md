# MSG-02 clause 4 — staff assign, transfer within the unit or resolve with reason and audit

## Clause

Staff can assign, transfer within the unit or resolve it, with reason and audit.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.StaffAssignTransferWithinUnitResolveReasonTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/StaffAssignTransferWithinUnitResolveReasonTest.java`).
It is a synthetic, database-free clause test in the same `clauses` style as
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: a real
`ConversationActions` matrix, mocked `ConversationRepository`, `AuditAppender`
and `PatientConnectionVerifier`, a transaction template that runs the real
guarded callback synchronously, a synthetic tenant context cleared after each
case, and UUID/subject fixtures. Every case enters through the same commands
the controller calls, so a refusal is proven to leave the conversation
projection and the append-only work-item event ledger untouched.

- `assignTransferAndResolveRecordReasonedEventsAndAudit` — an authorized
  `lab_verifier` assigns a lab work item, transfers it to another eligible staff
  member in the same unit, then resolves it. Each command records one
  append-only work-item event (`assigned`, `transferred`, `resolved`) with the
  trimmed reason, the authenticated actor, and the from/to wire states; the
  assignment and state writes are bound to the version the caller read, the
  returned conversation is `RESOLVED`, and one `SUCCESS` audit row is appended
  for each of `WORK_ASSIGN`, `WORK_TRANSFER` and `WORK_RESOLVE` (rule met).
- `assignIsRefusedForAPatientRole` — a caller holding only the `patient` role is
  refused with 403 `FORBIDDEN` before the row is loaded, a `DENIED` audit row is
  written for `WORK_ASSIGN`, and no assignment, event or successful audit row is
  produced (negative: wrong role).
- `transferIsRefusedForATargetInAnotherUnit` — a target whose active role is
  `clinician` cannot receive an item in the `lab` unit; the command is refused
  with 422 `INVALID_ASSIGNEE` and no write occurs (negative: cross-unit target).
- `assignIsRefusedOnStaleVersion` — an assignment against version 1 of a
  conversation already at version 2 is refused with 412 `STALE_VERSION` and no
  write occurs (negative: stale version / concurrent update).
- `resolveIsRefusedOnAClosedThread` — resolution of a `CLOSED` conversation is
  refused with 409 `CLOSED_THREAD` and no write occurs (negative: forbidden
  state; closed threads are read-only).
- `resolveIsRefusedForBlankReason` — a blank reason is rejected with 422
  `INVALID_REASON` by `ConversationInputs.reason` before any repository or audit
  call (negative: missing required input).

Production classes and files surveyed and exercised by the entry points:

- `com.aurevia.smartclinic.messaging.conversations.ConversationService.assign`,
  `transfer`, private `reassign` and `resolve`, private `transitionWithReason` —
  own the rule: they validate inputs, re-derive the caller's authorization in a
  tenant-guarded transaction, lock the row, require the read version, require a
  writable state, require an active eligible target in the same unit, then
  commit the projection change, the reasoned append-only event and the audit
  row together. `authorize` writes a `DENIED` audit row for a caller without an
  active membership or a role permitted for the action.
- `com.aurevia.smartclinic.messaging.conversations.ConversationActions` — the
  action and role matrix: `WORK_ASSIGN`, `WORK_TRANSFER` and `WORK_RESOLVE` are
  staff-only actions, and `allowsUnit` confines each staff role to its unit so a
  cross-unit assignment or transfer fails closed.
- `com.aurevia.smartclinic.messaging.conversations.ConversationInputs.reason` —
  trims and bounds the required reason before any SQL runs.
- `com.aurevia.smartclinic.messaging.conversations.ConversationContract` —
  `State`, `UnitKey`, `WorkEventType`, `Conversation`, `WorkItemEvent` and the
  `AssignRequest`, `TransferRequest` and `ResolveRequest` commands.
- `com.aurevia.smartclinic.messaging.conversations.ConversationRepository` —
  `assign`, `updateState`, `appendEvent`, `loadForUpdate`, `find`,
  `hasActiveMembership` and `activeRoles` are the SQL boundary the assertions
  observe.
- `com.aurevia.smartclinic.audit.AuditAppender` — appends the `SUCCESS` or
  `DENIED` audit row inside the same transaction.
- `com.aurevia.smartclinic.messaging.conversations.ConversationController` —
  `POST /api/v1/conversations/{id}/assign`, `/transfer` and `/resolve` are the
  HTTP entry points; the controller injects `ConversationService` and parses the
  `If-Match` version, while authorization and lifecycle invariants stay in the
  domain service.
- `db/migrations/V33__conversation_lifecycle.sql` — creates `public.conversation`
  and `public.work_item_event` with tenant keys, FORCE ROW LEVEL SECURITY,
  append-only triggers and a version-advance guard, so the reasoned event and
  the assignment survive a restart and cannot be rewritten.

Contract survey: the three work-item routes are listed in
`contracts/openapi/missing-routes.txt` (assign on line 164, resolve on line 168
and transfer on line 169) as `POST /api/v1/conversations/{}/assign`, `/resolve`
and `/transfer`; they are baselined there and are not described as operations in
`contracts/openapi.yaml`.
That is separate contract-documentation debt and does not affect whether the
behaviour is implemented and proven. No contract file was changed by this task.

Broader coverage already present (not repeated here): the service-level branches
live in `it/messagingconversations/ConversationServiceTest`, and the HTTP plus
PostgreSQL path, including the work-item event ledger and RLS, lives in
`it/messagingconversations/ConversationFlowTest`.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=StaffAssignTransferWithinUnitResolveReasonTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
