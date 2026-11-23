# XCH-03 clause 4 — API test evidence

## Clause

Revocation blocks future sender-hosted retrieval and records downstream notification.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.RevocationBlocksFutureSenderHostedRetrievalRecordsTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/RevocationBlocksFutureSenderHostedRetrievalRecordsTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, uses the
`synthetic-auth` profile and a dedicated database
`smartclinic_xch03_revoke_clause_it` created from the production Flyway
migrations, and seeds two synthetic tenants with their own subjects, patient
record and orders. All four tests are active (none are disabled) and use
synthetic data only.

Entry-point-to-test trace:
`POST /api/v1/xch03/orders/{id}/accept` →
`GET /api/v1/xch03/orders/{id}/retrieve` →
`POST /api/v1/xch03/orders/{id}/revoke` →
`GET /api/v1/xch03/orders/{id}/retrieve` →
`GET /api/v1/xch03/orders/{id}`.

Test methods (one positive rule proof, three negative refusals):

- `revocationBlocksFutureSenderHostedRetrievalAndRecordsDownstreamNotification` —
  positive proof of both halves of the clause. A `lab_verifier` accepts the
  released order (HTTP 200, version 2) and the first `GET .../retrieve` returns
  HTTP 200 with the released SHA-256 checksum. A `clinician` then revokes with
  `If-Match: 2` and a downstream notification carrying event id, target and
  channel; the response state is `revoked`. The very next retrieval is refused
  with HTTP 410 `RETRIEVAL_REVOKED`, proving that no cached or out-of-date view
  can bypass revocation. The order view reports one notification with the
  submitted `eventId`, `target`, `channel` and outcome `NOTIFIED`; a direct
  database assertion finds one durable `xch03_notification` row for that event
  id and one `EXCHANGE_REVOKE` audit row for the order.
- `aRecipientRoleCannotRevokeAndRetrievalStillSucceeds` — negative proof
  (wrong role). `lab_verifier` has recipient and read rights but
  `REVOKE_ROLES` contains only `clinician` and `tenant_admin`. With the current
  version supplied, the revoke is refused with HTTP 403 `FORBIDDEN`; the order
  stays `accepted`, no notification is written and retrieval still returns the
  released checksum.
- `staleVersionRevokeIsRefusedAndRetrievalStillSucceeds` — negative proof
  (stale update). A `clinician` revokes with `If-Match: 1` after acceptance
  advanced the order to version 2. The compare-and-set is refused with HTTP 412
  `STALE_VERSION`; the order stays `accepted`, no notification is written and
  retrieval still succeeds.
- `revokeForAnotherTenantIsConcealedAndItsReleaseStaysRetrievable` — negative
  proof (tenant isolation). A valid `clinician` of tenant A attempts to revoke
  tenant B's accepted order while acting in tenant A. Authorization passes, but
  forced row-level security conceals the order and the command returns HTTP 404
  `NOT_FOUND`. Neither tenant records a notification, and tenant B's release is
  still retrievable by its own verifier.

Production classes exercised through the real HTTP entry point:

- `com.aurevia.smartclinic.exchange.response.ExchangeResponseController` — thin
  adapter for `/accept`, `/revoke`, `/retrieve` and the order `GET`; it parses
  `If-Match` into a machine-readable precondition before calling the service.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseService` —
  `revoke` validates the notification fields, inserts the notification inside
  the guarded transaction and writes the terminal `REVOKED` state; `retrieve`
  re-checks that state on every call and raises the 410; `run` enforces the
  version compare-and-set and appends the audit event; `authorize` re-derives
  active membership and roles on each request.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseActionPolicy` —
  grants `EXCHANGE_REVOKE` only to `clinician` and `tenant_admin` and separates
  it from recipient actions (wrong-role negative).
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseInputs` —
  trust-boundary validation of the revoke reason, retention policy, target,
  channel and event id.
- `com.aurevia.smartclinic.exchange.response.ExchangeState` — the terminal
  `REVOKED` state and its wire form asserted in the response and history.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseCommands` — the
  `RevokeRequest` payload and `Notification` / `OrderView` projections asserted
  through the response body.

Database evidence: `db/migrations/V21__xch03_order_response.sql` creates
`xch03_notification` with `event_id` as the tenant-scoped deduplication key,
enables and forces row-level security on every XCH-03 table through
`xch03_app_org()`, and owns the actor/immutability triggers. The `410` is raised
by the service against the current order row, not by an in-memory cache.

The `/api/v1/xch03/orders/{}/revoke` and `/api/v1/xch03/orders/{}/retrieve`
routes (and the other XCH-03 order routes) are currently baselined as missing
from `contracts/openapi.yaml` in `contracts/openapi.yaml`
(lines 349 and 121). That is a separate contract-completeness gap and does not
change this clause's runtime proof; no contract file was edited by this task.

## Gap

None.

The XCH-03 clause-4 rule is built and proven by active tests through the real
HTTP entry point and the production migration. This task added proof only: no
production code, existing test, OpenAPI contract or migration was changed.
