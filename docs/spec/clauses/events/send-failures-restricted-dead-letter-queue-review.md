# ARC-05 clause 5 — restricted dead-letter queue and review

## Clause

Send failures to a restricted dead-letter queue and review workflow.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.events.clauses.SendFailuresRestrictedDeadLetterQueueReviewTest`
(`apps/api/src/test/java/com/aurevia/events/clauses/SendFailuresRestrictedDeadLetterQueueReviewTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile. It uses an isolated
database, applies only the production Flyway migrations from `db/migrations`, seeds two
organizations with worker, operator, integration-admin, tenant-admin and privacy-auditor
members, and drives the real HTTP entry points with synthetic identifiers and digests.
Server instants come from the application clock, so no assertion depends on a wall-clock
string.

- `retryBudgetExhaustionRestrictsEventToReviewedDeadLetterQueue` (rule met) — drives four
  failed-send and bounded-retry cycles through `/events/{id}/sent`, `/events/{id}/failed`
  and `/events/{id}/retry` with quoted `If-Match` versions. The first three retries requeue
  with a recorded next-dispatch instant; the fourth dead-letters with reason
  `retry budget exhausted` and `attempt = 3`. The test asserts the dead-letter branch is the
  only populated side of the result, the event is `dead_lettered`, the entry starts
  unreviewed, an administrator can read it from `/dead-letters`, the queue entry and the
  `dead_letter` action are audited, and an authorized review with a reason persists the
  reviewer, resolution and review instant and resets the event to `pending` with a fresh
  retry budget.
- `resolvedReviewKeepsEventInTheRestrictedQueue` (rule met) — a `tenant_admin` may resolve an
  entry without requeuing; the event stays `dead_lettered` and the review record keeps the
  author, resolution and reason.
- `workerCannotReadOrReviewRestrictedQueue` (negative) — a worker with otherwise valid
  delivery rights is refused `403 FORBIDDEN` on both `/dead-letters` and
  `/dead-letters/review`, and the stored entry stays unreviewed.
- `otherTenantCannotSeeOrReviewDeadLetter` (negative) — a tenant administrator of another
  organization sees an empty queue and receives `404 NOT_FOUND` when reviewing the concealed
  event id; the owner's entry stays unreviewed.
- `alreadyReviewedEntryCannotBeReviewedAgain` (negative) — a second review, even by another
  administrator with a valid resolution and reason, is refused `409 ALREADY_REVIEWED` and the
  first reviewer, resolution and reason remain authoritative.
- `deadLetterRefusesNonFailedEvent` (negative) — moving a still-pending event into the queue
  is refused `409 NOT_FAILED`, the event stays `pending` and no dead-letter entry exists.
- `staleVersionCannotRetryIntoQueue` (negative) — a retry with an outdated `If-Match` is
  refused `412 STALE_VERSION`, the event stays `failed` and no dead-letter entry exists.
- `unknownResolutionOrBlankReasonIsRefused` (negative) — an unknown resolution is refused
  `422 INVALID_RESOLUTION` and a blank reason is refused `422 INVALID_REASON`, leaving the
  entry unreviewed.

Production classes and files exercised by these request paths:

- `com.aurevia.events.DeliveryController` — the thin HTTP adapter for the
  delivery routes under `/api/v1/delivery`: retry, dead-letter, restricted queue read,
  reasoned review and audited trail read.
- `com.aurevia.events.DurableEventService` — the bounded retry and dead-letter
  decision (`retry`, `deadLetter`), the reviewed resolution (`reviewDeadLetter`,
  `deadLetters`) and the append-only audit facts; a reviewer is recorded with the active
  subject and the application clock, once only.
- `com.aurevia.events.DeliveryActions` — the bounded action matrix reserving
  `REVIEW` for `integration_admin` and `tenant_admin`, `DEAD_LETTER` for delivery roles and
  `AUDIT_READ` for `tenant_admin` and `privacy_auditor`.
- `com.aurevia.events.DurableEventContract` — the `RetryResult`,
  `DeadLetterEntry`, `ReviewRequest` and `Resolution` records carrying only non-sensitive
  delivery metadata.
- `com.aurevia.events.JdbcDeliveryStore` and `DeliveryStore` — the tenant-scoped
  persistence boundary behind the service; the integration test reads dead-letter and event
  rows through it to prove each refusal left state unchanged.
- `com.aurevia.api.IfMatch`, `ApiException` and `ApiErrorHandler` — the version
  precondition and the machine-readable status and error code contract asserted by the
  negative tests.
- `db/migrations/V55__durable_event_outbox.sql` — the `delivery_dead_letter` ledger with
  forced tenant row-level security, immutable failure facts, a review-set-once guard,
  `SELECT`/`INSERT`/`UPDATE` grants only and revoked `DELETE`/`TRUNCATE`. This is the
  restricted queue; no external message broker is part of this contract.

The neighbouring `JdbcDeliveryStoreIntegrationTest.deadLettersAFailedEventAndListsItInTheRestrictedQueue`
covers the operator-driven happy path; the tests above complement it with retry-budget
exhaustion, both authorized admin roles and the full refusal matrix.

## Gap

None.
