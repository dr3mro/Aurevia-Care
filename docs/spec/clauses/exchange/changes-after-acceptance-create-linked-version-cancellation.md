# XCH-03 clause 2 — changes after acceptance create a linked version or cancellation request

## Clause

Changes after acceptance create a linked version or cancellation request, and require recipient response.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.ChangesAfterAcceptanceCreateLinkedVersionCancellationTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/ChangesAfterAcceptanceCreateLinkedVersionCancellationTest.java`),
an HTTP-level integration test on a dedicated PostgreSQL database with two synthetic
tenants that share local order codes. Each test seeds its own order so append-only
ledgers and optimistic versions cannot leak between cases. It proves the clause
through the real REST entry point, not just the domain enum:

- `linkedChangeCreatesRevisionTwoAndServesItOnlyAfterRecipientAcceptance` — accept,
  propose a change, then assert state `change_pending`, revision 2 linked by
  `previousRevision = 1`, revision 1's checksum unchanged, no second retained copy
  and retrieval still serving the accepted checksum. Only the recipient's
  `change-response accept` moves to `accepted`, advances the version, adds a
  second retained copy and serves revision 2.
- `aRejectedChangeNeverBecomesTheRetrievableRevision` — the recipient may reject
  the linked change: the order reaches `rejected`, revision 1 stays current, no
  second retained copy is created and retrieval still serves the accepted checksum.
- `cancellationIsOnlyConfirmedByTheRecipient` — a cancellation request reaches
  `cancellation_pending` and only the recipient's `cancellation-response confirm`
  reaches `cancelled`; the read-back history records both events and their states.
- `aDeclinedCancellationReturnsToAccepted` — the negative recipient response
  returns the order to `accepted` and records `cancellation_decline`.
- `aRecipientCannotProposeAChange` — the recipient role is refused with 403
  `FORBIDDEN` on the sender-only change command and the order stays `accepted`.
- `aPeerTenantCannotRespondToAnotherTenantsPendingChange` — another tenant's actor
  cannot respond to the pending change (404 `NOT_FOUND`); the owning tenant can
  still respond, proving the peer did not mutate the order.
- `aStaleVersionCannotProposeAChange` — `If-Match: 99` is refused with 412
  `STALE_VERSION` and no revision is inserted.
- `aChangeCannotBeProposedBeforeAcceptance` — a change on a `requested` order is
  refused with 409 `INVALID_TRANSITION`; the order stays `requested`.
- `aChangeRequiresAChangeSummary` — an empty change summary is refused with 422
  `REQUIRED` and the order stays `accepted`.
- `aCancellationRequestRequiresAReason` — a blank cancellation reason is refused
  with 422 `REQUIRED` and no cancellation event is recorded.

Production classes exercised through `com.aurevia.smartclinic.exchange.response.ExchangeResponseController`
(`POST /api/v1/xch03/orders/{id}/propose-change`, `/change-response`,
`/request-cancellation`, `/cancellation-response`, `GET /{id}`, `/{id}/history`
and `/{id}/retrieve`):

- `com.aurevia.smartclinic.exchange.response.ExchangeResponseService` —
  `proposeChange` (linked immutable revision, `change_pending`), `changeResponse`
  (recipient accept/reject, retained copy of the accepted change),
  `requestCancellation` (`cancellation_pending`) and `cancellationResponse`
  (recipient confirm/decline), plus the guarded `run` command (role, tenant,
  version, state and input checks with structured failures).
- `com.aurevia.smartclinic.exchange.response.ExchangeState` — the pending states
  that force an explicit recipient response.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseActionPolicy` —
  separates sender change/cancellation commands from recipient responses.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseCommands` and
  `ExchangeResponseInputs` — immutable projections and trust-boundary validation.
- `com.aurevia.smartclinic.api.IfMatch` and `com.aurevia.smartclinic.api.ApiException`
  — precondition parsing and structured 403/404/409/412/422 failures.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseRepository` and the
  `xch03_order`, `xch03_revision`, `xch03_retained_copy` and `xch03_event` tables
  from `db/migrations/V21__xch03_order_response.sql` (RLS/FORCE RLS, tenant-scoped
  self-link and append-only ledgers).

Discovery also found the same behaviors asserted at unit and command level in
`com.aurevia.smartclinic.it.exchangetask.ExchangeResponseServiceTest` and through
the HTTP boundary in
`com.aurevia.smartclinic.it.exchangetask.ExchangeResponseExchangeTest`; this
story proves clause 2 in isolation with its own fixtures and database.

## Gap

None.
