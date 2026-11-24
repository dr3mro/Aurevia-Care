# XCH-02 clause 3 — API test evidence

## Clause

A transmission acknowledgment cannot close a clinical task.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.exchange.clauses.TransmissionAcknowledgmentCannotCloseClinicalTaskTest`
(path: `apps/api/src/test/java/com/aurevia/exchange/clauses/TransmissionAcknowledgmentCannotCloseClinicalTaskTest.java`).
All six tests are active (none disabled) and pass against real PostgreSQL with synthetic fixtures only.
Each mutating test seeds its own task id in the dedicated `orevia_xch02_ack_it` database.

- `aDeliveryReceiptLeavesClinicalReviewUnreviewedAndUnbound` — positive proof. A lab collector posts
  `send` and `deliver` through `POST /api/v1/exchange-tasks/{id}/{command}`; the response and a direct
  `orevia_app` read show transmission `delivered`, workflow `requested`, clinical review
  `unreviewed`, all three clinical evidence columns null, and no `clinical_review` event in the ledger.
- `resolvingAfterOnlyADeliveryReceiptIsRefusedAndWritesNothing` — negative proof. After a delivery
  receipt, `resolve` by a clinician at the correct version returns 409 `INVALID_TRANSITION` and at a
  stale version returns 412 `STALE_VERSION`; the clinical channel and version are unchanged.
- `aForgedUpdateCannotDeliverAndResolveInOneWrite` — negative proof at the storage boundary. A direct
  `UPDATE` that sets both transmission `delivered` and clinical `resolved` (with valid review evidence
  so only the one-channel guard can object) is rejected with SQLSTATE 23514 and the message
  "exactly one state channel". A single-channel leap to `resolved` is also rejected by the clinical
  transition guard. The row is unchanged after both attempts.
- `labCollectorCannotAcknowledgeOrResolveAClinicalTask` — negative proof of the human-only rule.
  `acknowledge` and `resolve` by a lab collector return 403 `FORBIDDEN`; the clinical channel remains
  unreviewed with null evidence.
- `aClinicianCanStillCloseTheClinicalTaskAfterAProperAcknowledgment` — positive control. An authorized
  clinician can acknowledge, request action and resolve, binding actor, frozen clock instant and
  revision to the clinical channel. This distinguishes "a delivery receipt cannot close the task"
  from "no command can close the task".
- `aDeliveryReceiptFromAnotherTenantCannotReachThisTask` — negative proof of tenant isolation. A
  valid ORG_A lab actor cannot deliver to an ORG_B task (404 `NOT_FOUND`), and the peer task stays
  pending and unreviewed.

Production classes and objects exercised through the normal HTTP entry point
`POST /api/v1/exchange-tasks/{id}/{command}` (`com.aurevia.exchange.task.ExchangeTaskController`):

- `com.aurevia.exchange.task.ExchangeStateMachine` — the `TRANSMISSION` branch of `next`
  copies clinical review state and evidence unchanged while advancing only transmission; the
  `CLINICAL_REVIEW` branch raises 409 `INVALID_TRANSITION` for `unreviewed -> resolved`.
- `com.aurevia.exchange.task.ExchangeCommand` and `ExchangeDimension` — each command maps
  to exactly one state channel, so no command can deliver and resolve in one write.
- `com.aurevia.exchange.task.ExchangeActionPolicy` — `allows` is clinician-only for the
  clinical review action, so a lab collector can record a delivery receipt but cannot close the task.
- `com.aurevia.exchange.task.ExchangeTaskService` — `execute` authorizes, loads under the
  tenant guard, checks `If-Match` (412 `STALE_VERSION` when stale) and appends one channel event only.
- `com.aurevia.api.IfMatch` — parses the version precondition used by every transition.
- `db/migrations/V20__exchange_task_states.sql` — the review-evidence CHECK, the RLS and FORCE RLS
  policies, `xch02_guard_task_update` (`v_changed <> 1` and the clinical transition branch), and the
  trigger-derived actor are the database authority behind the HTTP behaviour.

## Gap

None.
