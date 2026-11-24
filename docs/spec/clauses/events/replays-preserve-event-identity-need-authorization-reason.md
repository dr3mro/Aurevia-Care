# ARC-05 clause 6 — replay identity, authorization and reason

## Clause

Replays preserve event identity and need authorization and a reason.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.events.clauses.ReplaysPreserveEventIdentityNeedAuthorizationReasonTest`
(`apps/api/src/test/java/com/aurevia/events/clauses/ReplaysPreserveEventIdentityNeedAuthorizationReasonTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and
synthetic worker and administrator fixtures. It proves the clause through the
real `POST /api/v1/delivery/replays` entry point, not just the domain service:

- `replayKeepsOriginalEventIdReasonAndAuthorizer` — a worker publishes an event,
  an administrator replays it with a reason; the response is 200 with
  `originalEventId` equal to the published `eventId`, the stored reason and
  `authorizedBy` equal to `admin-a`, and the owning `organizationId`. The
  committed `public.delivery_replay` row read back under forced RLS carries the
  same original id, reason and authorizer; the original outbox row and its
  idempotency-key row count are unchanged; one `REPLAY`/`SUCCESS` audit fact is
  recorded; and a peer tenant's RLS read of the replay fact returns no row.
- `aSecondReplayStillPointsAtTheOriginalEventId` — a second replay of the same
  event mints a distinct `replayId` but still names the original `eventId`, with
  two replay facts and two audit facts in the owning tenant.
- `workerCannotReplayAnEvent` — a worker subject is refused with 403 `FORBIDDEN`
  and no replay fact is added in either tenant.
- `replayWithoutAReasonIsRejected` — a blank reason is refused with 422
  `INVALID_REASON` before any replay fact is stored.
- `aTooLongReasonIsRejected` — an over-length reason is refused with 422
  `INVALID_REASON`.
- `aMalformedEventIdIsRejected` — a non-UUID event id is refused with 422
  `INVALID_EVENTID`.
- `peerTenantCannotReplayAnotherTenantsEvent` — an administrator of the peer
  tenant using the owning tenant's event id is refused with 404 `NOT_FOUND`,
  concealing the event's existence; neither tenant gains a replay fact.
- `replayOfUnknownEventIsRejected` — an unknown event id is refused with 404
  `NOT_FOUND` and no replay fact is stored.
- `replayWithoutAuthenticationIsRejected` — a request with no subject header is
  refused with 401 `UNAUTHENTICATED` and no replay fact is stored.

Production classes and objects exercised through
`com.aurevia.events.DeliveryController` (`POST /api/v1/delivery/events`,
`POST /api/v1/delivery/replays`):

- `com.aurevia.events.DurableEventService` — `publish` and `replay`,
  including context derivation, input validation, fresh role and membership
  checks, the tenant-scoped existence probe, replay fact creation and audit.
- `com.aurevia.events.DeliveryActions` — `REPLAY` is limited to
  administrative roles.
- `com.aurevia.events.DeliveryInputs` — UUID and bounded-text
  validation at the trust boundary.
- `com.aurevia.events.DurableEventContract` — `PublishRequest`,
  `ReplayRequest` and `ReplayRecord` with its immutable `originalEventId`.
- `com.aurevia.events.JdbcDeliveryStore` — `putReplay` writing the
  authenticated authorizer and original event id.
- `db/migrations/V55__durable_event_outbox.sql` — `public.delivery_replay` with
  its immutable foreign key to `delivery_outbox(organization_id, id)`, RLS and
  FORCE RLS with the tenant policy, and the immutability triggers.

Verified locally: `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=ReplaysPreserveEventIdentityNeedAuthorizationReasonTest test`
— Surefire reported `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` (final rerun, 7.458 s); see
`.agent/pr-notes/SPEC-ARC-05-C06-20261123-023804.md` for exact final lines.

## Gap

None.
