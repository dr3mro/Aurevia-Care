# ARC-04 clause 3 — API test evidence

## Clause

Commit status, acceptance event and audit together.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.web.clauses.CommitStatusAcceptanceEventAuditTogetherTest`
(path: `apps/api/src/test/java/com/aurevia/web/clauses/CommitStatusAcceptanceEventAuditTogetherTest.java`).
All eight tests are active (none skipped or disabled) and use synthetic identifiers, members and
digests on a dedicated PostgreSQL database migrated from `db/migrations` with forced row-level
security. They drive the real HTTP controller with the synthetic-auth profile and `X-Synthetic-Subject`,
`X-Active-Organization`, `X-Correlation-Id` and `If-Match` headers, following the fixture pattern and
style of
`com.aurevia.exchange.clauses.RecipientAcceptsRejectsReasonRequestsClarificationTest`.
Both organizations seed the same local order codes, so tenant separation is exercised by forced RLS.

- `acceptCommitsStatusEventAndAuditTogether` — positive proof. `POST /api/v1/xch03/orders/{id}/accept`
  with `If-Match: 1` returns 200 with state `accepted` and version 2. Direct tenant-bound JDBC reads
  confirm the order projection is `accepted`/2, exactly one `xch03_event` row has action `accept` from
  `requested` to `accepted`, and exactly one `public.audit_event` row has action `EXCHANGE_ACCEPT`,
  `resource_id` equal to the order id, `outcome` `SUCCESS` and the request's unique `correlation_id`.
  The status change, append-only event and audit fact are therefore one commit.
- `auditFailureRollsBackStatusAndAcceptanceEvent` — all-or-nothing proof. A test-only plpgsql
  `BEFORE INSERT` trigger on `public.audit_event`, installed and always dropped in `finally`, raises a
  plain exception only for this test's unique `X-Correlation-Id` marker. The accept reaches the state
  update and event append, then the audit insert fails with 500; direct JDBC checks confirm the order
  remains `requested`/1, no `accept` event exists and no audit row carries the marker. This proves the
  three writes share one transaction rather than three independent commits.
- `auditFactRollsBackWhenOrderCommitFails` — reverse-direction all-or-nothing proof. A second
  test-only deferred constraint trigger on `xch03_order`, keyed to this test's order and always dropped
  in `finally`, raises at the transaction's commit, which is after the audit fact has been appended.
  The 500 response, unchanged `requested`/1 order, zero accept events and zero audit facts for the
  request's correlation id prove that the audit fact is not committed in a transaction of its own;
  an implementation using a separate audit transaction would leave an orphan fact and fail this test.
- `wrongRoleCannotAccept` — negative proof (wrong role). A `privacy_auditor` with active membership is
  refused with 403 `FORBIDDEN`; no accept event and no successful accept audit fact are written and the
  order stays `requested`/1.
- `peerTenantCannotAccept` — negative proof (another tenant). An `ORG_B` verifier posting to the
  `ORG_A` order is refused with 404 `NOT_FOUND`; the concealed order is unchanged and no accept event
  or successful audit fact is written.
- `staleIfMatchIsRefused` — negative proof (stale version). After a genuine accept advances the row to
  version 2, reusing version 1 is refused with 412 `STALE_VERSION`; no second accept event and no audit
  fact for the stale request's correlation id are written.
- `missingLocalPatientIdIsRefused` — negative proof (missing input). A body without `localPatientId`
  is refused with 422 `INVALID_INPUT` before a transaction opens; no event, no successful audit fact
  and no state change are written.
- `acceptAfterRejectionIsRefused` — negative proof (forbidden state). After a reject advances the row to
  version 2, accepting is refused with 409 `INVALID_TRANSITION`; no accept event and no audit fact for
  the accept request's correlation id are written.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.exchange.response.ExchangeResponseController` — thin adapter;
  `POST /api/v1/xch03/orders/{id}/accept` parses `If-Match` and delegates to the service. This is the
  real route; the literal `/api/v1/orders/{id}/accept` in the specification is a `CommandCatalog`
  authorization mapping, not a controller route.
- `com.aurevia.exchange.response.ExchangeResponseService` — `accept` → `run` performs
  authorization, order locking, version check, state validation, `repository.update`,
  `repository.appendEvent` and `audit.append(..., "EXCHANGE_ACCEPT", "SUCCESS")` inside one
  tenant-guarded `TransactionTemplate`.
- `com.aurevia.exchange.response.ExchangeResponseRepository` — RLS-bounded order lookup,
  conditional update and append-only event writes; a zero-row update is a stale-version failure.
- `com.aurevia.audit.AuditAppender` — appends the fact inside the caller's transaction and
  stamps `correlation_id` from the tenant context, never from the request body.
- `com.aurevia.web.TenantContextInterceptor` — derives actor, tenant and validated
  correlation id from request headers before the controller runs.
- `com.aurevia.api.ApiErrorHandler` — renders the 403, 404, 409, 412 and 422 machine-readable
  error bodies asserted by the negative tests.
- `db/migrations/V21__xch03_order_response.sql` — `xch03_order` and the append-only `xch03_event` ledger,
  with forced RLS and actor-derived authorship.
- `db/migrations/V1__tenant_schema.sql` and `db/migrations/V3__audit_chain.sql` — `audit_event` with its
  `correlation_id`, outcome and immutable hash chain, with forced RLS.
- `contracts/openapi.yaml` — the published `/api/v1/xch03/orders/{id}/accept` operation and its synthetic
  response projection (read-only evidence for this task).

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change production
code, an existing test, the OpenAPI contract, a migration or a dependency. The specification's
`Idempotency-Key` requirement is a separate ARC-04 concern owned by another clause and is not claimed
as proven here.
