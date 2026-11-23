# XCH-02 clause 2 — acknowledgment by an authorized human with timestamp and revision

## Clause

Acknowledgment is made by an authorized human with timestamp and revision.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest.java`).
It is a synthetic, database-free clause test in the `clauses` style of
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: mocked
`ExchangeTaskRepository` and `AuditAppender`, the real `ExchangeActionPolicy`, a
`TransactionTemplate` over a mocked manager running the real guarded callback,
`Clock.fixed(2026-11-22T10:00:00Z, UTC)` and a synthetic tenant context cleared
after each case. Every case enters through `ExchangeTaskService.execute`, the
command invoked by the exchange controller.

- `acknowledgeByAuthorizedClinicianBindsClockAndRevision` — an active clinician
  acknowledges a current version-one task: the captured guarded write carries
  `reviewedAt = NOW`, `reviewedRevision = 1` (the exact version reviewed), version 2
  and state `ACKNOWLEDGED`; one `EXCHANGE_ACKNOWLEDGE` event records
  `unreviewed -> acknowledged` with revision 1 and one SUCCESS audit is appended.
  The actor is deliberately null in this snapshot: the service never accepts or
  invents an actor (rule met at the service boundary).
- `acknowledgeIsRefusedForNonClinicianRole` — an active `lab_collector` is refused
  with 403 `FORBIDDEN` and a `EXCHANGE_REVIEW` DENIED audit, and the row is never
  loaded or written (negative: wrong role; clinical review is clinician-only).
- `acknowledgeIsRefusedWhenMembershipIsInactive` — a revoked subject is refused
  with 403 `FORBIDDEN` and a DENIED audit before roles are consulted (negative:
  revoked human fails closed on the next action).
- `acknowledgeIsRefusedOnStaleVersion` — a version-two precondition against a
  version-one task is refused with 412 `STALE_VERSION` before any write or audit
  (negative: cannot acknowledge a revision the caller did not read).
- `acknowledgeIsRefusedWhenTheRowChangedBetweenReadAndWrite` — a zero-row
  compare-and-set is refused with 412 `STALE_VERSION` and leaves no event or audit
  (negative: a concurrent update cannot produce a forged acknowledgment).
- `acknowledgeIsRefusedFromAlreadyReviewedState` — re-acknowledging an
  `ACKNOWLEDGED` task is refused with 409 `INVALID_TRANSITION` before any write
  (negative: forbidden state).

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.exchange.task.ExchangeTaskService.execute` — owns the
  rule: it authorizes before loading, injects `clock.instant()` as review time, binds
  `reviewedRevision` to the caller's version precondition, enforces the compare-and-set,
  and commits the state, one event and one audit row atomically.
- `exchange.task.ExchangeStateMachine` (CLINICAL_REVIEW branch) and
  `ClinicalReviewState` — the legal `unreviewed -> acknowledged` transition and the
  timestamp/revision evidence on the next snapshot.
- `exchange.task.ExchangeActionPolicy` — clinical review is clinician-only, so a
  collector cannot acknowledge even though it may record a delivery.
- `exchange.task.ExchangeCommand.ACKNOWLEDGE`, `ExchangeDimension.CLINICAL_REVIEW`,
  `TransitionRequest` (a reason-only body: no actor, timestamp or revision can be
  supplied by a caller) and `ExchangeTask` (the evidence-bearing snapshot).
- `exchange.task.ExchangeTaskController` —
  `POST /api/v1/exchange-tasks/{id}/{command}` is the thin HTTP entry point; it
  injects the service and parses `If-Match` into the version precondition.
- `db/migrations/V20__exchange_task_states.sql` — surveyed database authority:
  `xch02_actor_id()` resolves the actor from the authenticated `app.actor` with live
  membership; `xch02_guard_task_update()` requires a non-null revision and timestamp
  for a clinical change and sets `clinical_review_actor_id`; both `exchange_task`
  and `exchange_task_transition` have ROW LEVEL SECURITY with FORCE ROW LEVEL SECURITY.

The human-identity part of the clause is **not** claimed by this database-free test:
the service snapshot intentionally carries `reviewedBy = null` and the V20 trigger
derives the real actor. That binding is already proven end to end by the existing,
out-of-scope integration test
`com.aurevia.smartclinic.it.exchangetask.ExchangeTaskIsolationTest#humanAcknowledgmentBindsContextActorClockAndRevision`
(`apps/api/src/test/java/com/aurevia/smartclinic/it/exchangetask/ExchangeTaskIsolationTest.java:334-358`),
which asserts `reviewedBy` equals the context actor, `reviewedAt` equals the fixed
clock and `reviewedRevision == 1` after an HTTP POST persisted through PostgreSQL.
Service-level proof of the same acknowledgment also exists in
`it/exchangetask/ExchangeTaskServiceTest.acknowledgeBindsClockAndRevisionAndNeverTrustsTheCallerForActor`.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
