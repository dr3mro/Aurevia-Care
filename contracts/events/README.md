# X-02 — Durable delivery contract (ARC-05)

Synthetic contract for the durable outbox, consumer deduplication, bounded retry,
replay and dead-letter review in `apps/api/src/main/java/com/aurevia/events`.
All identifiers and digests below are synthetic; no patient data or credential
appears in a fixture or an audit fact.

## Routes

| Route | Purpose |
|---|---|
| `POST /api/v1/delivery/events` | Commit state, audit and outbox idempotency key together; requires a tenant/sender/type scoped key |
| `POST /api/v1/delivery/events/{id}/sent` | Advance `pending → sent` with an `If-Match` version |
| `POST /api/v1/delivery/events/{id}/delivered` | Advance `sent → delivered` with an `If-Match` version |
| `POST /api/v1/delivery/events/{id}/failed` | Record a transient failure with a reason |
| `POST /api/v1/delivery/events/{id}/retry` | Requeue with bounded backoff or dead-letter when the budget is spent |
| `POST /api/v1/delivery/events/{id}/dead-letter` | Move a failed event to the restricted queue |
| `POST /api/v1/delivery/dead-letters/review` | Authorized resolution with reason and reviewer identity |
| `POST /api/v1/delivery/consumer-events` | Deduplicate and record the processing outcome of an external event |
| `POST /api/v1/delivery/replays` | Replay with the original event identity, authorization and reason |
| `POST /api/v1/delivery/external-links` | Link only after order and patient identifiers are confirmed |
| `GET /api/v1/delivery/dead-letters` | Restricted queue read; read is audited |
| `GET /api/v1/delivery/audit` | Append-only delivery audit; read is audited |

Failures use the shared machine-readable error contract: `401` unauthenticated,
`403` denied, `404` concealed, `409` conflict or illegal transition, `412` stale
`If-Match`, `422` invalid input and `428` missing precondition.

## Guarantee summary

- Event identifiers are server-generated and immutable; each state change
  increments a per-aggregate version.
- A repeated idempotency key with the same digest returns the original event; a
  different digest is a `409`, never a second downstream effect.
- External events deduplicate inside `(namespace, sender, event_type, external_event_id)`.
  Lower versions are held, not written over a later revision. Every outcome is
  recorded separately from the message itself.
- Retries use `min(1 << attempt, 60)` seconds of backoff and a maximum of three
  attempts before the restricted dead-letter queue. Review is a separate
  authorized, reasoned and audited action.
- Replays keep the original event identity and require an authorized role and a
  reason; external data is linked only after order and patient confirmation.

## Synthetic fixtures

- [`fixtures/partner-events.json`](fixtures/partner-events.json) — duplicate,
  correction, out-of-order and partial-outage sequences with their expected
  processing outcomes.

The synthetic store used by the unit tests is an in-memory adapter. The
authoritative schema is the reserved `V55` Flyway migration, which adds forced
tenant row-level security, immutable fact ledgers and actor-derived authorship.
Database and integration checks run in the integrator gate, not the worker fast
tier.
