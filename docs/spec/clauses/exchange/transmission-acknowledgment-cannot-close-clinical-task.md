# XCH-02 clause 3 — a transmission acknowledgment cannot close a clinical task

## Clause

A transmission acknowledgment cannot close a clinical task.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.TransmissionAcknowledgmentCannotCloseClinicalTaskTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/TransmissionAcknowledgmentCannotCloseClinicalTaskTest.java`).
It is a synthetic, database-free clause test in the same `clauses` style as
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: mocked
`ExchangeTaskRepository` and `AuditAppender`, a real `ExchangeActionPolicy`, a
`TransactionTemplate` over a mocked `PlatformTransactionManager` that runs the
real guarded callback, `Clock.fixed` and a synthetic tenant context set and
cleared around each case. Every case enters through `ExchangeTaskService.execute`,
the command called by `ExchangeTaskController`.

- `deliveryDoesNotChangeClinicalReviewOrBindHumanEvidence` — a `DELIVER` receipt
  from `sent` advances the transmission channel to `delivered` and the version by
  one; the workflow and clinical channels and all review evidence (actor,
  timestamp, reviewed revision) stay untouched, one transmission-only event is
  appended with a null `reviewedRevision`, no clinical event is written and one
  success audit is recorded (rule met).
- `deliveryCannotCloseATaskWhenActorIsOnlyALabCollector` — that role may record
  the delivery receipt, but `RESOLVE` and `ACKNOWLEDGE` are refused with 403
  `FORBIDDEN`, write neither state nor ledger and emit denied audits for
  `EXCHANGE_REVIEW` (negative: wrong role).
- `deliveryIsRefusedOnStaleVersion` — a delivery receipt carrying an older
  version is refused with 412 `STALE_VERSION` and writes nothing (negative:
  optimistic concurrency).
- `deliveryIsRefusedFromPending` — `DELIVER` before `SEND` is refused with 409
  `INVALID_TRANSITION` and writes nothing (negative: forbidden transmission state).
- `resolveFromUnreviewedIsRefused` — `RESOLVE` on an unreviewed clinical task is
  refused with 409 `INVALID_TRANSITION` and writes nothing, so a delivery receipt
  cannot substitute for the human review chain (negative: forbidden review state).
- `deliveryOfAnotherTenantsTaskIsConcealed` — a task outside the active
  organization is concealed with 404 `NOT_FOUND` and no write. With a mocked
  repository the empty `loadForUpdate` result models the row hidden by forced
  row-level security; this unit test proves the service's not-found denial, not
  the SQL filter itself.
- `deliveryIsRefusedWhenTheCompareAndSetLosesTheRace` — a deterministic mocked
  zero-row compare-and-set is refused with 412 `STALE_VERSION`, with no event and
  no success audit (negative: lost update).
- `deliveryIsRefusedWhenTheActorHasNoActiveMembership` — a subject whose
  membership was revoked cannot record even a delivery receipt on the next
  action: 403 `FORBIDDEN`, no write, denied `EXCHANGE_TRANSMISSION` audit.

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.exchange.task.ExchangeTaskService.execute` — owns the
  rule at the service boundary: it derives the policy action from the command's
  single dimension, re-reads membership and roles, locks the task, checks the
  version, calls the state machine and writes exactly one channel event and one
  audit row in one transaction.
- `com.aurevia.smartclinic.exchange.task.ExchangeStateMachine` — the
  `TRANSMISSION` branch copies `clinicalReviewState`, `reason`, `reviewedBy`,
  `reviewedAt` and `reviewedRevision` unchanged from the current snapshot, which
  is the domain-level form of the rule; the `CLINICAL_REVIEW` branch alone binds
  clock, revision and an actor derived from the transaction.
- `com.aurevia.smartclinic.exchange.task.ExchangeActionPolicy` — a lab collector
  may record transmission facts, but only a clinician may change clinical review;
  `ExchangeCommand` maps `SEND`/`DELIVER`/`FAIL` to the `TRANSMISSION` dimension
  and `ACKNOWLEDGE`/`REQUEST_ACTION`/`RESOLVE` to `CLINICAL_REVIEW`, and
  `ExchangeDimension` supplies the authorization key.
- `com.aurevia.smartclinic.exchange.task.ExchangeTask` and
  `ExchangeTaskRepository.updateState`/`appendEvent` — immutable three-channel
  snapshot, compare-and-set with no review-actor parameter, and an event ledger
  whose channel is exactly the command's dimension.
- `db/migrations/V20__exchange_task_states.sql:30-41` requires review evidence
  exactly when the clinical channel is no longer unreviewed, and the
  `xch02_guard_task_update` trigger at `:183-253` requires an update to change
  exactly one state channel, advances the version by one and preserves prior
  review evidence unchanged on a transmission write. A stray `UPDATE` cannot
  combine a delivery with a clinical resolution.
- Contract survey: `GET /api/v1/exchange-tasks/{}`,
  `POST /api/v1/exchange-tasks` and `POST /api/v1/exchange-tasks/{}/{}` (the
  command route used here) are baselined in
  `contracts/openapi/missing-routes.txt:44,204-205` as undocumented routes. That
  is separate contract-documentation debt and does not affect whether the
  behavior is implemented and proven.

Broader evidence already present (not repeated here): the HTTP entry-point and
command parsing are exercised by `it/exchangetask/ExchangeTaskControllerTest`,
the real-PostgreSQL path and cross-tenant concealment by
`it/exchangetask/ExchangeTaskIsolationTest`, and the pure transition matrix by
`it/exchangetask/ExchangeStateMachineTest`.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=TransmissionAcknowledgmentCannotCloseClinicalTaskTest test`
— `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` (Surefire report). The rule
was already implemented, so this proof-only test was designed to pass on first
run; no production change was made or needed.

## Gap

None.
