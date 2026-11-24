# ARC-05 clause 2 — workers use immutable event IDs and per-aggregate version

## Clause

Workers use immutable event IDs and per-aggregate version.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.events.clauses.WorkersUseImmutableEventIdsAggregateVersionTest`
(`apps/api/src/test/java/com/aurevia/events/clauses/WorkersUseImmutableEventIdsAggregateVersionTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants.
Every command goes through the real `POST /api/v1/delivery` routes and the stored
snapshot is read back through the same RLS-bounded store.

- `publishedEventKeepsImmutableIdAndAdvancesVersionOnEveryTransition` — a worker
  publishes an event (201, version 1, server-generated id, Location header),
  an operator marks it sent (2), delivered (3), failed (4) and schedules a retry
  (5, pending); the `eventId` is identical in every response and in the stored
  snapshot, and the version advances by exactly one on every state change.
- `transitionWithStaleIfMatchIsRejectedWithoutChangingState` — after the event
  reaches version 2, a delivery with `If-Match: 1` is refused (412
  `STALE_VERSION`, audit outcome `stale`) and the stored state and version are
  unchanged.
- `publishOrTransitionWithoutDeliveryPermissionIsForbidden` — an active
  `clinician` and a worker with a revoked membership are refused on publish
  (403 `FORBIDDEN`) leaving no stored event, and the clinician is refused on a
  transition, leaving the version and state unchanged.
- `anotherTenantCannotReadOrTransitionTheEvent` — a worker of the other tenant
  using the first tenant's event id is concealed (404 `NOT_FOUND`); no row is
  visible across the tenant boundary and the owner's event is unchanged.
- `malformedIfMatchIsRejectedBeforeAnyWrite` — an unparseable `If-Match` is
  refused (422 `INVALID_IF_MATCH`) before any state change.

Production classes exercised by the entry point:
`com.aurevia.events.DeliveryController` (the thin HTTP adapter that binds
`If-Match` and maps the created event to its Location header),
`com.aurevia.events.DurableEventService` (`publish` stamps
`DeliveryInputs.newId()` with version 1; `snapshot` preserves `eventId` and sets
`aggregateVersion() + 1` on every transition, retry, dead-letter and review;
`requireVersion` raises 412 `STALE_VERSION`; `authorize` raises 403 `FORBIDDEN`;
`requireEvent` raises 404 `NOT_FOUND` to conceal another tenant's event),
`com.aurevia.events.DeliveryActions` (the bounded role matrix),
`com.aurevia.events.DeliveryInputs` and
`com.aurevia.api.IfMatch` (boundary validation),
`com.aurevia.events.JdbcDeliveryStore` (the RLS-bounded store) and
`com.aurevia.tenant.TenantContext`, with tenant RLS/FORCE RLS supplied
by the Flyway migrations under `db/migrations`.

The immutable-identity and version rule is also covered at domain level by
`com.aurevia.events.DurableEventServiceTest`; that suite is not
repeated here (this story proves the clause through the real HTTP entry point).

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=WorkersUseImmutableEventIdsAggregateVersionTest test`
— `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
