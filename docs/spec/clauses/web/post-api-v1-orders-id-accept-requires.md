# ARC-04 clause 1 — API test evidence

## Clause

`POST /api/v1/orders/{id}/accept` requires authenticated destination context, an `Idempotency-Key` and an `If-Match` version.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.web.clauses.PostApiV1OrdersIdAcceptRequiresTest`
(path: `apps/api/src/test/java/com/aurevia/web/clauses/PostApiV1OrdersIdAcceptRequiresTest.java`).
All five tests are active (none are disabled) and use synthetic data on a dedicated PostgreSQL
database (`aurevia_arc04_accept_requires_it`) recreated from the authoritative Flyway migrations
with forced row-level security. They drive the real Spring HTTP wiring under the `synthetic-auth`
profile with the same two-tenant fixture and style as the neighbouring clause suites. Two tenants
share identical local order codes, so forced RLS is the only separator, and each test owns a distinct
order because the ledgers are append-only.

**Scope of the proof.** These tests prove the behaviour of the route that is actually implemented,
`POST /api/v1/xch03/orders/{id}/accept`. They prove the implemented route's authenticated
destination context, role policy, tenant concealment, version precondition, transition guard, and
that the state change, event, retained copy and audit row co-committed on success. No failure is
injected mid-transaction, so all-or-nothing rollback is not proved here. They do **not** prove that the literal route
`POST /api/v1/orders/{id}/accept` exists, and they do **not** prove a mandatory `Idempotency-Key`:
the accept controller binds no such header and every test succeeds without one. This is why the
status is `Partly proven` rather than `Proven by tests`.

- `acceptWithVerifiedAssociationCommitsStateEventAndAudit` — positive proof. An authenticated
  recipient with an active `lab_verifier` role accepts a released order under `If-Match: 1`. The
  response is `200`, state `accepted`, version `2`, with the verified association and retention
  policy. The projection shows one `accept` event from `requested` to `accepted` attributed to the
  caller's identity and one retained copy with the permitted purpose, retention policy, legal-hold
  flag and actor. A direct read of `public.audit_event` finds exactly one `SUCCESS` row for action
  `EXCHANGE_ACCEPT` on that order, confirming the four records co-committed on success.
- `nonRecipientRoleCannotAccept` — negative proof of role policy. An active `privacy_auditor` that
  may read the order is refused `403 FORBIDDEN`; no event, no retained copy and no `SUCCESS` audit
  row are written. A `DENIED` audit row is recorded instead.
- `peerTenantCannotAcceptAnotherTenantsOrder` — negative proof of tenant isolation. A verifier in
  the peer tenant, using the same local order code, is refused `404 NOT_FOUND` by forced RLS; the
  owner's order remains `requested` with no new event, retained copy or audit row.
- `staleIfMatchIsRefused` — negative proof of the version precondition. A genuine clarify advances
  the order to version `2`; a subsequent accept carrying version `1` is refused `412 STALE_VERSION`
  and appends no event. The stale case is a real optimistic-lock race, not a synthetic bad header.
- `acceptAfterRejectionIsRefused` — negative proof of the state machine. After a genuine reject
  advances the order to version `2`, an accept carrying the current version is refused `409
  INVALID_TRANSITION`; the order stays `rejected` with no second event and no retained copy.

The positive test also asserts that the response carries the order id, the accepted state and the new
version. These five tests do not re-prove every clause precondition: a missing `If-Match` (`428`) and
missing subject/organization headers (`401`) are handled by the shared precondition and security
filter chain and are exercised generically elsewhere, not accept-specifically here. The `422`
invalid-business-input path on accept is proved on this route by
`com.aurevia.exchange.clauses.ApplyLawPolicyRecipientRetentionTest`, not re-proven here.

Entry-point-to-assertion trace:
`POST /api/v1/xch03/orders/{id}/accept` → `ExchangeResponseController.accept`
(`apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseController.java:88-92`,
which parses `If-Match` and binds no retry key) → `ExchangeResponseService.accept` and `run`
(`apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseService.java:73-87,350-391`)
→ `authorize` (`393-404`) and `requireState` (`406-409`) → the tenant-guarded transaction that
writes the state, the append-only event, the retained copy and the audit record (`368-378`). The
read-back assertions go through `GET /api/v1/xch03/orders/{id}` on the same controller.

Production classes exercised through those routes and their transactions:

- `com.aurevia.exchange.response.ExchangeResponseController` — thin adapter binding the
  accept route and parsing the `If-Match` precondition.
- `com.aurevia.exchange.response.ExchangeResponseService` — authorization, concealment,
  version compare-and-set, state transition and atomic commit of state, event and audit.
- `com.aurevia.exchange.response.ExchangeResponseActionPolicy` — bounded
  `EXCHANGE_ACCEPT` role set; unknown roles fail closed.
- `com.aurevia.web.TenantContextInterceptor` — derives the actor and destination tenant
  from the authenticated principal and the active-organization selection; the request body cannot
  supply them.
- `com.aurevia.api.IfMatch` — validates the version precondition (`428` absent, `422`
  malformed).
- `com.aurevia.audit.AuditAppender` — appends the append-only audit row inside the same
  transaction.
- `db/migrations/V21__exchange_response_order_response.sql` — supplies the order, revision, event and retained
  ledgers with tenant policies and forced RLS.

The neighbouring suite
`apps/api/src/test/java/com/aurevia/exchange/clauses/RecipientAcceptsRejectsReasonRequestsClarificationTest.java`
already drives the same route through `200`, `403`, `404`, `412` and `409`. This task adds a focused
clause-only proof under the scoped path and changes neither that suite nor any production code.

## Gap

Four things prevent this clause from being fully proven, in one cross-boundary implementation task
followed by its own proof task:

1. **No `Idempotency-Key` binding or replay rule.** `ExchangeResponseController.accept`
   (`apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseController.java:89-92`)
   binds only the path variable, body and `If-Match` header. A retry key never reaches the service,
   so neither "same key and payload replays the original outcome" nor "same key with a different
   payload is `409`" can be exercised today. Those two cases are therefore recorded here, not as
   disabled tests: a test that sends a key the controller ignores would pass before the rule
   existed, and project policy rejects added skipped tests.
2. **The literal route is absent.** No controller maps `POST /api/v1/orders/{id}/accept`. It exists
   only as a shape-only worked example in `contracts/contract/openapi.yaml:14` and
   `contracts/contract/README.md:92-104`, as a string in
   `apps/api/src/main/java/com/aurevia/api/authorization/CommandCatalog.java:173-174`,
   and as a baselined entry in `contracts/openapi/generated/openapi.yaml:380` with `IdempotencyKey`
   and `IfMatch` parameters. The published contract `contracts/openapi.yaml` declares only the
   `xch03` route (`:9720`), carrying an optional `RetryKey` parameter (the `Idempotency-Key`
   header, `:10104-10108`) but no `IfMatch` parameter and no `412` response — contract drift to
   resolve alongside the implementation.
3. **The success body has no timestamp.** The clause requires the order id, accepted state, new
   version and timestamp in the response. `ExchangeResponseCommands.ExchangeOrder`
   (`apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseCommands.java:80-84`)
   exposes the id, state and version but no timestamp field, so that part of the response contract is
   unbuilt; these tests assert what the view actually returns.
4. **A packaging distinction:** the proof lives under `web.clauses` because the task scope fixes
   that path, while the production behaviour belongs to the `exchange.response` domain and the repo
   convention is `<domain>.clauses`. This is a documentation/ownership mismatch, not a behavioural
   gap; a follow-up may relocate the suite to `exchange.clauses` once a task owns that path.

To close the gap: bind and validate the key at the accept entry point, add a durable tenant-, caller-
and command-scoped ledger with a request digest and a reused-key-with-different-payload `409`,
register the literal route through the same service, publish both routes and the `412` response in
the OpenAPI contract and regenerate the client, then turn the two replay cases into active HTTP-over-
PostgreSQL tests with full JaCoCo coverage. Files that would change include the controller, service,
repository, a new Flyway migration, `contracts/openapi.yaml`, the generated client and this test
package. No such task exists in the current backlog; it is requested in the worker notes.
