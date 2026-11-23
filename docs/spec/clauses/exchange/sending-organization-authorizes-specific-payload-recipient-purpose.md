# XCH-01 clause 2 — API test evidence

## Clause

The sending organization authorizes the specific payload, recipient, purpose and duration.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.SendingOrganizationAuthorizesSpecificPayloadRecipientPurposeTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/SendingOrganizationAuthorizesSpecificPayloadRecipientPurposeTest.java`).
All eleven tests are active (none disabled) and pass against a real PostgreSQL with
the Flyway V19 row policies in force, using synthetic organizations A, B and C only.
Every negative test asserts the HTTP status, the stable error code, and that neither
an order nor a disclosure row was written; the positive test asserts exactly one of
each and that the disclosure records the approved recipient, purpose and duration.

Entry point: `POST /api/v1/orders`.

- `matchingPayloadRecipientPurposeAndDurationArePermitted` — positive proof. Order
  code `CBC`, recipient ORG_B, purpose `lab review` and a 30-minute release match an
  approved grant, so the request returns HTTP 201 and echoes the order code,
  destination and authorization id; exactly one order and one disclosure are written
  and the disclosure carries the approved recipient, purpose and duration.
- `orderCodeOutsideTheGrantIsRefused` — payload boundary. Order code `CMP` against a
  `CBC` grant returns HTTP 403 `DISCLOSURE_NOT_AUTHORIZED`.
- `recipientOutsideTheGrantIsRefused` — recipient boundary. An approved `CBC` grant
  for ORG_B does not authorize a release to the unrelated ORG_C.
- `unapprovedPurposeIsRefused` — purpose boundary. A `marketing` purpose is refused
  under a `lab review` grant.
- `durationAboveTheGrantedMaximumIsRefused` — duration ceiling. 61 minutes under a
  60-minute maximum is refused.
- `disclosureOutlivingTheGrantWindowIsRefused` — duration window. A 30-minute
  disclosure under a grant expiring in ten minutes is refused, so sharing cannot
  outlive the sender's permission.
- `expiredGrantIsRefused` — validity window. A grant whose `expires_at` is past is
  refused (the `!grant.expiresAt().isAfter(now)` side of the window check).
- `inactiveGrantIsRefused` — revocation. A grant with `active = false` is refused
  (the `!grant.active()` side).
- `grantNotYetEffectiveIsRefused` — validity window. A grant whose
  `effective_from` is in the future is refused (the `grant.effectiveFrom().isAfter(now)`
  side).
- `grantOwnedByAnotherTenantIsRefused` — tenant boundary. A grant that exists in
  ORG_C is invisible when the request is read as ORG_A, so the RLS-bounded lookup
  yields no grant and the release is refused (the `grant == null` side).
- `clinicianRoleIsRequiredToDisclose` — role boundary. An active `reception` member
  is refused with the distinct code HTTP 403 `FORBIDDEN`, keeping the membership and
  role gate separate from the authorization gate.

Production classes exercised through the HTTP entry point:

- `com.aurevia.smartclinic.exchange.order.OrderController` — thin adapter exposing
  `POST /api/v1/orders`; it receives the service by injection and holds no rules.
- `com.aurevia.smartclinic.exchange.order.OrderService` — owns the invariant. Its
  `create` command re-derives the tenant context, calls `deny` for active membership
  and the `clinician` role, resolves the sender's grant through `repository.authorization`,
  and calls `checkGrant` (lines 403-423) to compare the order code, destination,
  purpose, maximum duration, active flag and effective/expiry window against the
  requested disclosure. A mismatch writes no order, snapshot, disclosure or work item.
  One shared instant drives both the window check and the disclosure expiry stamp, so
  a live clock cannot widen the permission.
- `com.aurevia.smartclinic.exchange.order.OrderRepository` — the RLS-bounded lookup
  that makes a foreign tenant's grant invisible, and the insert/append operations
  that never run on a refusal.
- `db/migrations/V19__exchange_orders.sql` — supplies `exchange_authorization`,
  `exchange_order`, `exchange_order_snapshot`, `exchange_disclosure` and
  `exchange_work_item` with `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY`,
  the single-tenant policy on sender-owned tables and the immutable snapshot and
  disclosure triggers.

Neighbouring implementations observed in the source survey (not changed by this task):
`com.aurevia.smartclinic.it.exchangeorder.OrderExchangeIntegrationTest` exercises the
same HTTP entry point for the wider XCH-01 flow, and the unit suites
`it.exchangeorder.OrderServiceTest` and `it.exchange.order.OrderAuthorizationWindowTest`
prove the same `checkGrant` branches deterministically. The operator-facing rule is
recorded in `docs/reference/partner-api-guide.md`, section 5.2.

## Gap

None.

The clause is built and proven by active HTTP-level tests. This task added proof only:
it did not change production code, an existing test, the contract or a migration.
The OpenAPI route-parity baseline `contracts/openapi/missing-routes.txt` still lists
both `POST /api/v1/orders` (line 251) and `POST /api/v1/exchange/authorizations`
(line 206) as undocumented routes; that is a pre-existing contract gap outside this
task's scope and is recorded in the worker notes rather than fixed here.
