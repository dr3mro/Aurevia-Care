# XCH-01 clause 2 — sending organization authorizes a specific disclosure

## Clause

The sending organization authorizes the specific payload, recipient, purpose and duration.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.SendingOrganizationAuthorizesSpecificPayloadRecipientPurposeTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/SendingOrganizationAuthorizesSpecificPayloadRecipientPurposeTest.java`)
drives the real `OrderService.create` decision with a fixed clock and mocked
repository, audit appender and transaction template (the callback runs inline).
Every fixture is synthetic:

- `authorizationCoveringPayloadRecipientPurposeAndDurationPermitsDisclosure` —
  a grant covering all four dimensions permits one release; the captured snapshot
  content carries the authorized order code, recipient, purpose and duration, and
  the disclosure ledger row is stamped with the same shared instant (`NOW + 30m`).
- `disclosureIsRefusedWhenOrderCodeIsNotTheAuthorizedPayload` — a different order
  code is refused (403 `DISCLOSURE_NOT_AUTHORIZED`) with no identity lookup or writes.
- `disclosureIsRefusedForAnUnauthorizedRecipient` — a different valid destination
  organization is refused with no writes.
- `disclosureIsRefusedForAnUnauthorizedPurpose` — a purpose outside the grant is
  refused with no writes.
- `disclosureIsRefusedWhenDurationExceedsTheAuthorizedMaximum` — 61 minutes against
  a 60-minute maximum is refused with no writes; the grant has no expiry so this
  isolates the maximum-duration rule from the validity-window rule.
- `disclosureIsRefusedWhenItWouldOutliveTheAuthorizedWindow` — a 30-minute release
  under a grant expiring in 5 minutes is refused, because the disclosure must not
  remain valid after the permission that authorized it.

Each refusal asserts the 403 `DISCLOSURE_NOT_AUTHORIZED` outcome, the single
`DENIED` audit event and that no patient lookup, order, snapshot, disclosure or
work item was written.

Production code exercised by the entry point:

- `com.aurevia.smartclinic.exchange.order.OrderService.create` calls `checkGrant`
  inside the tenant-guarded transaction; the private `checkGrant` compares the
  request's order code, destination organization, purpose and duration against
  the stored grant and audits `DENIED` on a mismatch. `create` also snapshots the
  released content, stamps identifiers and a fingerprint, appends the disclosure
  and projects the bounded destination work item.
- `com.aurevia.smartclinic.exchange.order.OrderService.authorize` records the
  sending organization's approval (`AuthorizationView`) after an admin-role check.
- `com.aurevia.smartclinic.exchange.order.OrderRepository.authorization` reads the
  grant from `public.exchange_authorization` under row-level security.
- `com.aurevia.smartclinic.exchange.order.OrderController` exposes the HTTP entry
  points `POST /api/v1/orders` (release) and `POST /api/v1/exchange/authorizations`
  (grant). This unit test drives the service decision directly; it does not drive
  the controller over HTTP.
- `db/migrations/V19__exchange_orders.sql` stores the authorization and bounds the
  order and work-item rows to it with composite foreign keys and forced RLS, so a
  release cannot reference a grant outside the sender's tenant.

Corroborating evidence already in the repository (not added by this task):
`com.aurevia.smartclinic.it.exchangeorder.OrderExchangeIntegrationTest.deniesPayloadRecipientPurposeAndDurationOutsideTheAuthorization`
proves the same four refusals over HTTP against real PostgreSQL, and
`com.aurevia.smartclinic.it.exchange.order.OrderAuthorizationWindowTest` plus the
grant-lifecycle tests in `com.aurevia.smartclinic.it.exchangeorder.OrderServiceTest`
cover the missing/inactive/not-yet-effective/expired grant branches. Clause 2 was
already implemented and covered; this class adds a focused, named proof of it.

The rule is enforced only by `OrderService.checkGrant`. The `exchange/hl7` path
restates the clause in documentation and only checks optional-field presence in
`Hl7Mapper.mapOrder`; it is not an authorization authority. `documents.QuarantineService`
uses a separate `DocumentAuthorization` for document quarantine and is not this
clause.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=SendingOrganizationAuthorizesSpecificPayloadRecipientPurposeTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
