# XCH-03 clause 2 — evidence note

## Clause

Changes after acceptance create a linked version or cancellation request, and require recipient response.

## Status

Proven by tests

## Evidence

The rule is implemented end to end and exercised through the real HTTP controller against a dedicated real PostgreSQL database with synthetic fixtures only. Both alternatives of the clause are proven: a post-acceptance change creates an immutable linked revision that remains pending until a recipient responds, and a post-acceptance cancellation creates a pending request that remains awaiting a recipient decision.

- Requirement and clause text: [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md), section 4, row XCH-03.
- Domain behavior: [ExchangeResponseService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseService.java) — `proposeChange` and `requestCancellation` require the `accepted` state and create the `change_pending` and `cancellation_pending` states; `changeResponse` and `cancellationResponse` resolve them only on an explicit recipient decision. The pending change is inserted only into the revision ledger, so retrieval keeps serving the previously accepted revision until that response.
- State model: [ExchangeState.java](../../../../apps/api/src/main/java/com/aurevia/exchange/response/ExchangeState.java) defines `change_pending` and `cancellation_pending`; its documentation records that a change or cancellation after acceptance always passes through a pending state needing an explicit recipient response.
- Authorization: [ExchangeResponseActionPolicy.java](../../../../apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseActionPolicy.java) separates the sender-only actions `EXCHANGE_PROPOSE_CHANGE` and `EXCHANGE_REQUEST_CANCEL` from the recipient-only `EXCHANGE_RESPOND`, so raising a change is not the same permission as answering one.
- HTTP entry points: [ExchangeResponseController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseController.java) serves `POST /api/v1/xch03/orders/{id}/propose-change`, `/change-response`, `/request-cancellation` and `/cancellation-response`.
- Persistence and invariants: [V21__exchange_response_order_response.sql](../../../../db/migrations/V21__exchange_response_order_response.sql) creates `exchange_response_order`, `exchange_response_revision` (with the same-order `previous_revision` self-link), `exchange_response_retained_copy`, `exchange_response_event` and `exchange_response_notification` under forced row-level security, append-only ledger triggers and a version-advance guard.
- Clause proof: [ChangesAfterAcceptanceCreateLinkedVersionCancellationTest.java](../../../../apps/api/src/test/java/com/aurevia/exchange/clauses/ChangesAfterAcceptanceCreateLinkedVersionCancellationTest.java) — `changesAfterAcceptanceCreateALinkedRevisionAndCancellationRequestAwaitingRecipientResponse` asserts the linked revision and its predecessor, that retrieval keeps the accepted checksum while the change is pending, the `propose_change` and `request_cancellation` events, and the `cancelled` outcome after the recipient confirms. The negatives `aRecipientRoleCannotProposeAChangeOrRequestCancellation`, `aStaleIfMatchCannotProposeAChange`, `aChangeBeforeAcceptanceIsRefused` and `aSenderRoleCannotRespondToAPendingChange` assert that the rule refuses what it must refuse, each with an error code and no unauthorized ledger write.
- Verification command: `AUREVIA_PG_ADMIN=postgres mvn -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=ChangesAfterAcceptanceCreateLinkedVersionCancellationTest test` — 5 tests, 0 failures, 0 errors, 0 skipped; entry-point-to-assertion trace is `POST /api/v1/xch03/orders/{id}/propose-change` → `ExchangeResponseController.proposeChange` → `ExchangeResponseService.proposeChange` → the linked-revision and pending-state assertions above.

## Gap

None
