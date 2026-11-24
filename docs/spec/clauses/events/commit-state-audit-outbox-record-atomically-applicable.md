# ARC-05 clause 1 — API test evidence

## Clause

Commit state, audit and outbox record atomically where applicable.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.events.clauses.CommitStateAuditOutboxRecordAtomicallyApplicableTest`
(path: `apps/api/src/test/java/com/aurevia/events/clauses/CommitStateAuditOutboxRecordAtomicallyApplicableTest.java`).
All six tests are active (none disabled) and use synthetic identifiers and digests on a dedicated
PostgreSQL database migrated from `db/migrations` with forced row-level security. They drive the real
HTTP entry point with the synthetic-auth profile, reusing the fixtures and style of
`com.aurevia.it.JdbcDeliveryStoreIntegrationTest`.

- `publishCommitsOutboxAuditAndDedupKeyTogether` — positive proof. `POST /api/v1/delivery/events`
  returns 201. Direct JDBC checks under `app.current_org` find exactly one `delivery_outbox` row for
  that event id and idempotency key, with state `pending`, and exactly one `delivery_audit` fact for
  `resource_id` = event id with purpose `DELIVERY`, action `publish` and outcome `success`. The key
  resolves back to the same event through `DeliveryStore.byIdempotencyKey`, so the outbox row, the
  append-only fact and the deduplication key are one commit.
- `workerWithoutPublishRoleCannotWriteOutboxOrAudit` — negative proof (wrong role). `operator-a` has
  active membership but PUBLISH is restricted to the `worker` and `integration_admin` roles. The POST
  is refused with 403 `FORBIDDEN`; no outbox row and no successful publish audit fact are written for
  the attempted key and aggregate. The refusal itself is audited as a `denied` fact, which is the
  expected append-only behaviour for a denied command.
- `peerTenantCannotSeeOrTransitionThePublishedEvent` — negative proof (another tenant). Under
  `ORG_B`, forced RLS conceals both the outbox row and the audit fact of the published `ORG_A` event;
  `POST /api/v1/delivery/events/{id}/sent` from a valid `ORG_B` worker is refused with 404. The
  `ORG_A` event remains `pending`, so the cross-tenant attempt wrote nothing to it.
- `missingIdempotencyKeyIsRejectedWithoutWriting` — negative proof (missing input). A blank key, an
  absent key and a malformed digest are each refused with 422
  `INVALID_IDEMPOTENCYKEY` / `INVALID_DIGEST` before any domain or store call; no outbox row and no
  audit fact exist for the attempted aggregate. Invalid input returns 422, not 400, because
  `DeliveryInputs` raises `ApiException(422, ...)` and `ApiErrorHandler` renders the status verbatim.
- `sameKeyWithDifferentDigestConflictsWithoutASecondWrite` — negative proof (reused key, different
  payload). A second publish with the same tenant/sender/type key but a different digest is refused
  with 409 `IDEMPOTENCY_CONFLICT`; the original event id and its single outbox row are unchanged, and
  the audit ledger holds one `success` and one `conflict` publish fact rather than a second effect.
- `publishRollsBackOutboxWhenAuditWriteFails` — negative proof of all-or-nothing commit. A regular
  plpgsql trigger on `public.delivery_audit`, installed and dropped by the test on its dedicated
  database, raises a check violation only when the audit fact carries a unique synthetic
  `X-Correlation-Id` marker. The publish reaches the store, the outbox insert succeeds inside the
  transaction and the audit insert then fails; direct JDBC checks confirm zero outbox rows, zero
  audit facts and no resolvable idempotency key afterwards. This is deterministic failure
  injection in a test-only database, not a production change or a race.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.events.DeliveryController` — thin adapter; `POST /api/v1/delivery/events`
  delegates to the service and maps the result to HTTP 201.
- `com.aurevia.events.DurableEventService` — `publish(String ...)` validates the command,
  derives the tenant context, authorizes the action, resolves the idempotency key, and calls
  `DeliveryStore.publishAtomically` with the outbox event and its audit fact.
- `com.aurevia.events.DeliveryStore` and
  `com.aurevia.events.JdbcDeliveryStore` — `publishAtomically` inserts the outbox row
  with `ON CONFLICT DO NOTHING RETURNING` and appends the audit fact inside one tenant-guarded
  `TransactionTemplate`; `byIdempotencyKey` and `appendAudit` provide the deduplication and
  append-only reads. Nested `appendAudit` joins the outer transaction under the default
  `PROPAGATION_REQUIRED` of the shared template.
- `com.aurevia.events.DeliveryInputs`, `DeliveryActions`,
  `DurableEventContract` — trust-boundary validation, the PUBLISH role matrix and the
  `PublishRequest`, `OutboxEvent` and `DeliveryAuditEvent` domain records.
- `com.aurevia.api.ApiErrorHandler` — renders the 403, 404, 409 and 422 machine-readable
  error bodies asserted by the negative tests.
- `db/migrations/V55__durable_event_outbox.sql` — the `delivery_outbox` and `delivery_audit` tables,
  the `(organization_id, sender, event_type, idempotency_key)` uniqueness that makes the key part of
  the commit, forced RLS on every delivery table, actor-derived authorship and immutable fact
  triggers.
- `contracts/events/README.md` — the synthetic contract description of the publish route and its
  idempotency and failure semantics (read-only evidence for this task).

This proof is scoped to the publish commit, which is what "where applicable" covers here: the
outbox state is the committed state, and its audit fact and key are written in the same transaction.
Later delivery state transitions (`markSent`, `markDelivered`, `markFailed`, `retry`, `deadLetter`
and `reviewDeadLetter`) append their audit facts in a separate transaction from their outbox
update; this document does not claim those transitions are atomic.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the OpenAPI contract, a migration or a dependency. The delivery
routes remain listed in `contracts/openapi.yaml`, a pre-existing contract gap owned by
a separate OpenAPI task and outside this story's scope.
