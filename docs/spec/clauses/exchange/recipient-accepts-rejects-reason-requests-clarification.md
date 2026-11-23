# XCH-03 clause 1 — recipient decision

## Clause

The recipient accepts, rejects with reason, or requests clarification.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.RecipientAcceptsRejectsReasonRequestsClarificationTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/RecipientAcceptsRejectsReasonRequestsClarificationTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants
sharing the same local order codes:

- `acceptsReleasedOrderAndRetainsPurpose` — an authorized recipient accepts a
  released order with a verified local patient association, a permitted purpose
  and a retention policy. The response is `200`, the state is `accepted`, the
  version advances to `2`, the retained copy carries the purpose and policy, and
  an `accept` event records the `requested → accepted` transition in the
  append-only response history.
- `rejectsWithDurableReason` — `POST /api/v1/xch03/orders/{id}/reject` with a
  reason moves the order to `rejected` and records the same reason both on the
  order projection and in the `reject` history event.
- `requestsClarificationWithQuestion` — `POST /api/v1/xch03/orders/{id}/clarify`
  with a question moves the order to `clarification_requested` and records the
  question both on the order projection and in the `clarify` history event.
- `wrongRoleCannotReject` — a read-only `reception` actor is refused with `403`
  and the order stays requested with an unchanged ledger.
- `peerTenantCannotReject` — a peer tenant's recipient actor is refused with
  `404` on the other tenant's order even though both tenants use the same local
  order code, and neither order's state nor ledger changes.
- `staleVersionIsRefused` — after a successful decision advances the version,
  reusing the old `If-Match` is refused with `412` and the order keeps its
  advanced state and ledger.
- `missingIfMatchIsRefused` — a decision without an `If-Match` precondition is
  refused with `428` before any state change.
- `rejectAfterClarifyIsRefused` — an order already in `clarification_requested`
  cannot be rejected from the `requested` branch; the command is refused with
  `409` and the clarification and ledger stay intact.
- `missingReasonIsRefused` — a reject with no reason is refused with `422` and
  leaves the order requested with an unchanged ledger.

Production classes exercised through the HTTP entry points
`POST /api/v1/xch03/orders/{id}/{accept,reject,clarify}`, `GET /{id}` and
`GET /{id}/history`:

- `com.aurevia.smartclinic.exchange.response.ExchangeResponseController` — thin
  adapter; parses `If-Match` and delegates the accept, reject and clarify commands.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseService` — `accept`,
  `reject` and `clarify` run inside one tenant-guarded transaction: fresh
  authorization, row lock, version check, `REQUESTED` state guard, retained copy
  and append-only event and audit write.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseActionPolicy` — the
  three decision actions are limited to recipient roles; `reception` and
  `privacy_auditor` are read-only.
- `com.aurevia.smartclinic.exchange.response.ExchangeState` —
  `clarification_requested` is not the `requested` branch, so reject after
  clarify is an illegal transition.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseCommands` and
  `ExchangeResponseInputs` — command payloads and trust-boundary validation of
  the reason, patient id, purpose and retention policy.
- `com.aurevia.smartclinic.api.IfMatch` — missing precondition `428`, malformed
  `422` and stale version decided by the service's compare-and-set.
- `db/migrations/V21__xch03_order_response.sql` — forced RLS on the order,
  revision, retained copy, event and notification tables; ledger actor derived
  from the authenticated subject by trigger.

Verified locally with
`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RecipientAcceptsRejectsReasonRequestsClarificationTest test`
— see the story notes for the exact command and final lines.

## Gap

None. The clause is implemented in the cited production classes and proven end
to end by the test class above; no production, migration, contract or client
change was needed. The published `contracts/openapi.yaml` does not yet list the
`/api/v1/xch03/orders/{}` action paths (they are recorded in
`contracts/openapi/missing-routes.txt`), but that contract publication is
pre-existing, separately tracked debt outside this test-only story; the tests
drive the real controller routes directly.
