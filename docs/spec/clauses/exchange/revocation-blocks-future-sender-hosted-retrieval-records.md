# XCH-03 clause 4 — API test evidence

## Clause

Revocation blocks future sender-hosted retrieval and records downstream notification.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.RevocationBlocksFutureSenderHostedRetrievalRecordsTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/RevocationBlocksFutureSenderHostedRetrievalRecordsTest.java`).
All three tests are active (none is disabled) and use synthetic identifiers, roles and UUIDs only.
They drive the real service with the Mockito fixtures of
`it/exchange/response/ExchangeResponseIntegrityTest`: a mocked `ExchangeResponseRepository` and
`AuditAppender`, a `TransactionTemplate` whose `doAnswer` executes the callback, and
`TenantContext.set`/`clear` around each test.

- `revokedReleaseBlocksLaterRetrievalAndRecordsOneDownstreamNotification` — positive proof. An order in
  `REQUESTED` at version 1 is revoked with a fully filled `RevokeRequest`. The guarded `update` receives
  the state `REVOKED`, the returned projection is `REVOKED`, exactly one `insertNotification` call records
  the downstream event id, target, channel and outcome `NOTIFIED` under the caller's organization and
  subject, and a later `retrieve` raises `ApiException` 410 `RETRIEVAL_REVOKED`. A released
  `currentRevision` is stubbed so that a missing revocation guard would return the revision and fail the
  test; consecutive `findForUpdate` stubs plus a call-count verification prove that retrieval re-reads
  the locked order after revocation instead of using a cached view.
- `recipientRoleCannotRevokeSoNoNotificationIsRecorded` — negative proof (wrong role). The `lab_verifier`
  role is a recipient role and is absent from `REVOKE_ROLES`; revocation is refused with 403 `FORBIDDEN`
  before the locked read and `insertNotification` is never called.
- `staleVersionCannotRevokeSoNoNotificationIsRecorded` — negative proof (stale version). A caller version
  of 2 against a stored version of 1 is refused with 412 `STALE_VERSION` and `insertNotification` is
  never called, so a stale client cannot emit a downstream notification.

Production classes exercised:

- `com.aurevia.smartclinic.exchange.response.ExchangeResponseService` — `revoke` validates the command,
  inserts the notification and returns a `REVOKED` decision; `retrieve` re-checks the state on every call
  and answers 410 `RETRIEVAL_REVOKED`; `run` enforces authorization and the version compare-and-set in
  one tenant-guarded transaction.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseActionPolicy` — `EXCHANGE_REVOKE` is limited
  to `REVOKE_ROLES` (`clinician`, `tenant_admin`) while `EXCHANGE_RETRIEVE` uses `READ_ROLES`.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseRepository` — `insertNotification`,
  `findForUpdate`, `find`, `update` and `currentRevision` are the mocked boundaries through which the
  state transition, notification and denial are observed.
- `com.aurevia.smartclinic.exchange.response.ExchangeState`, and `RevokeRequest`, `ExchangeOrder` and
  `Revision` in `ExchangeResponseCommands` — the terminal state and the command/value records used by
  the fixtures.
- `db/migrations/V21__xch03_order_response.sql` — the `xch03_notification` append-only ledger with
  `ENABLE` and `FORCE ROW LEVEL SECURITY`; it gives the record its durable immutability. This test
  verifies the service writes it exactly once; the schema constraint is exercised by the database
  integration suite, not by a mock.

Corroborating existing tests, neither added nor changed by this task:

- `com.aurevia.smartclinic.it.exchangetask.ExchangeResponseServiceTest.revocationRecordsDownstreamNotificationAndBlocksRetrieval`
  (lines 364-378) — unit-level revoke, notification verification and REVOKED retrieval 410.
- `com.aurevia.smartclinic.it.exchangetask.ExchangeResponseExchangeTest.revocationBlocksRetrievalAndRecordsDownstreamNotification`
  (lines 436-466) — HTTP-level accept, retrieve 200, revoke 200, retrieve 410 `RETRIEVAL_REVOKED`, one
  `NOTIFIED` notification in the view and one `EXCHANGE_REVOKE` audit row.

This task adds a per-clause manifest with distinct assertions (returned `REVOKED` state, exception code,
exactly one notification, and the consecutive `findForUpdate` re-read) rather than a duplicate proof.

## Gap

The revocation guarantee itself is built and proven by active tests; there is no production gap.

One contract debt remains outside this test-only scope. The two sender lifecycle routes,
`POST /api/v1/xch03/orders/{id}/revoke` and `GET /api/v1/xch03/orders/{id}/retrieve`, are listed in
`contracts/openapi/missing-routes.txt` (lines 121 and 349) and are absent from `contracts/openapi.yaml`.
What is missing: OpenAPI operations for both routes, each with an operation tag, an `Idempotency-Key`
header on the POST, and route-parity registration. Which files would change: `contracts/openapi.yaml`,
`contracts/openapi/missing-routes.txt` (remove those two entries), and the regenerated staff client types
in `apps/staff-web/src/api/generated/openapi.d.ts`. How many tasks: one separate contract/route-parity
story outside this scope. This task does not edit the contract.
