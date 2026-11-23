# ARC-05 clause 4 — API test evidence

## Clause

Retry transient errors with bounded backoff.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.events.clauses.RetryTransientErrorsBoundedBackoffTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/events/clauses/RetryTransientErrorsBoundedBackoffTest.java`).
All seven tests are active (none disabled) and pass as deterministic in-memory unit
tests with a fixed clock (`2026-11-19T12:00:00Z`) and synthetic identifiers only.
No clinical payload, real identifier or credential appears in any fixture.

Verification command and final line (tool evidence):
`mvn -B -f apps/api/pom.xml -Pno-security -Dtest=RetryTransientErrorsBoundedBackoffTest test`
→ `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`.

Positive proof of the rule:

- `retrySchedulesBoundedBackoffAfterTransientFailure` — publish, mark failed, then
  retry. The event returns to `PENDING` with attempt `1`, a version increment, a
  `SUCCESS` audit and `nextAttemptAt = NOW + backoffSeconds(1)`. The case also pins
  the bounded backoff itself: one second at attempt zero, doubling growth and a
  hard 60-second cap across the bounded retry budget's reachable attempt range
  (both sides of the `Math.min` cap).

Negative proofs — retry refuses every event it must not touch:

- `retryDeadLettersWhenTheAttemptBudgetIsExhausted` — a seeded attempt-3 `FAILED`
  event is dead-lettered instead of retried: the result carries only the dead-letter
  entry, the outbox row becomes `DEAD_LETTERED`, exactly one restricted-queue row
  exists and the next attempt would exceed `MAX_ATTEMPTS`.
- `retryRefusesAnEventThatIsNotFailed` — a `PENDING` event is refused with
  `409 NOT_FAILED` and a `CONFLICT` audit.
- `retryRefusesAStaleVersion` — an outdated `If-Match` is refused with
  `412 STALE_VERSION` and a `STALE` audit.
- `retryRefusesAnActorWithoutTheDeliveryRole` — `clinician` and `auditor` actors
  are refused with `403 FORBIDDEN` and a `DENIED` audit; a revoked (`inactive`)
  membership is likewise refused with `403 FORBIDDEN` on the next request.
- `retryRefusesACallerWithoutTenantContext` — a caller with no tenant context is
  refused with `401 UNAUTHENTICATED` before any state is read.
- `retryConcealsAnEventFromAnotherTenant` — a peer-tenant worker retrying the event
  gets `404 NOT_FOUND`; the peer tenant receives no outbox row and only a
  concealment audit, and the owning tenant's event is unchanged.

Production classes exercised through the normal service entry point:

- `com.aurevia.smartclinic.events.DurableEventService` — `retry` schedules the
  requeue with `clock.instant().plusSeconds(backoffSeconds(nextAttempt))`, dead-letters
  when `nextAttempt > MAX_ATTEMPTS`, and enforces version, state, role and tenant
  preconditions before any write. `backoffSeconds` caps exponential growth at 60 s.
- `com.aurevia.smartclinic.events.DeliveryActions` — `allows` maps `RETRY_SCHEDULED`
  to the delivery role set (worker, integration_admin, operator); other roles are denied.
- `com.aurevia.smartclinic.events.DurableEventContract` — `RetryResult`, `OutboxEvent`,
  `DeadLetterEntry`, `DeliveryState` and `Outcome` carry the result and audit facts.

HTTP entry point (existing end-to-end evidence, not re-tested here):
`POST /api/v1/delivery/events/{id}/retry`, exercised by
`com.aurevia.smartclinic.it.JdbcDeliveryStoreIntegrationTest.deadLettersAFailedEventAndListsItInTheRestrictedQueue`
(`apps/api/src/test/java/com/aurevia/smartclinic/it/JdbcDeliveryStoreIntegrationTest.java:263-294`),
which asserts a bounded retry records `nextAttemptAt` and returns `200`, and that a
failed event can be placed in and listed from the restricted dead-letter queue.
Attempt-budget exhaustion through retry itself is proven by the in-memory test
`retryDeadLettersWhenTheAttemptBudgetIsExhausted` above.

## Gap

None.
