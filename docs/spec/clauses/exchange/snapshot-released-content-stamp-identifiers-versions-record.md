# XCH-01 clause 3 — API test evidence

## Clause

Snapshot the released content, stamp identifiers and versions, and record disclosure.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.SnapshotReleasedContentStampIdentifiersVersionsRecordTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/SnapshotReleasedContentStampIdentifiersVersionsRecordTest.java`).
All six tests are active (none are disabled) and use synthetic data with a fixed clock,
mocked repository and audit appender, a `TransactionTemplate`-callback fixture and
`TenantContext.set`/`clear`, matching the fixtures of the neighbouring
`com.aurevia.smartclinic.it.exchangeorder.OrderServiceTest`. They drive the real
`OrderService.create` command that the `POST /api/v1/orders` entry point delegates to.

- `releaseStampsVersionOneFreshIdentifiersSnapshotAndDisclosure` — positive proof. A
  fully authorized release returns version 1, four distinct non-null identifiers, the
  fixed release instant and the exact snapshot fingerprint of the canonical released
  content. The repository is verified to have written the order, the immutable snapshot
  with the exact content and version 1, one disclosure carrying the same fingerprint,
  recipient, purpose, duration and expiry of `NOW + 30 minutes`, and the bounded
  destination work item. One audit `SUCCESS` row is written.
- `everyReleaseOfTheSameContentStampsFreshIdentifiers` — positive stamping proof. Two
  releases of the same command produce the same deterministic snapshot fingerprint but
  four fresh identifiers each time, so identifiers are stamped per release rather than
  reused.
- `wrongRoleRefusesTheReleaseAndWritesNoSnapshotOrDisclosure` — negative proof (wrong
  role). A subject with active membership but only the `reception` role is refused with
  HTTP 403 `FORBIDDEN`. No order, snapshot, disclosure or work item is written and a
  `DENIED` audit row is recorded.
- `noActiveMembershipRefusesTheReleaseAndWritesNoSnapshotOrDisclosure` — negative proof
  (no active membership). A subject without active membership is refused with HTTP 403
  `FORBIDDEN`; nothing is written and the denial is audited.
- `unauthorizedGrantRefusesTheReleaseAndWritesNoSnapshotOrDisclosure` — negative proof
  (unauthorized grant). A release whose duration exceeds the approved grant maximum is
  refused with HTTP 403 `DISCLOSURE_NOT_AUTHORIZED`; nothing is written and the denial
  is audited.
- `expiredGrantRefusesTheReleaseAndWritesNoSnapshotOrDisclosure` — negative proof
  (expired grant). A grant that has already expired is refused with HTTP 403
  `DISCLOSURE_NOT_AUTHORIZED`; nothing is written and the denial is audited.

Production classes exercised through the release command and its HTTP entry point:

- `com.aurevia.smartclinic.exchange.order.OrderController` — thin adapter; `POST /api/v1/orders`
  delegates to the service and returns HTTP 201 with the stamped order.
- `com.aurevia.smartclinic.exchange.order.OrderService` — `create` builds the canonical
  released content and its deterministic fingerprint, stamps version 1 and four opaque
  identifiers, and appends the order, snapshot, disclosure and work item in one
  tenant-guarded transaction; `deny` and `checkGrant` refuse unauthorized releases and
  audit the denial.
- `com.aurevia.smartclinic.exchange.order.OrderRepository` — `insertOrder`,
  `appendSnapshot`, `appendDisclosure` and `insertWorkItem` persist the four records;
  `authorization` and `patientIdentity` supply the validated inputs.
- `com.aurevia.smartclinic.exchange.order.OrderRequest`, `OrderView`, `AuthorizationView`
  and `OrderInputs` — command, stamped view, approved grant and boundary validation,
  including the fingerprint content projection.
- `com.aurevia.smartclinic.audit.AuditAppender` — transactional `SUCCESS`/`DENIED` ledger.
- `db/migrations/V19__exchange_orders.sql` — the `exchange_order_snapshot` and
  `exchange_disclosure` tables with forced row-level security, the version-1 snapshot
  constraint and the one-disclosure-per-revision unique constraint.

The request reaches the command through `POST /api/v1/orders` in `OrderController`, and
`com.aurevia.smartclinic.it.exchange.order.OrderListIntegrationTest` already proves the
durability of released orders against real PostgreSQL. This task adds clause-focused
proof only; it did not change production code, an existing test, the OpenAPI contract or
a migration.

Scope note (not covered by this clause-3 proof): cross-tenant refusal of a foreign grant
is not proven here. `checkGrant` does not compare `grant.organizationId`, so that refusal
relies solely on forced row-level security and needs a real-PostgreSQL test. It belongs to
the XCH-01 authorization boundary, not to snapshot, stamping and disclosure recording.

## Gap

None.

The XCH-01 clause-3 rule is built and proven by active tests. This task added proof only;
it did not change production code, an existing test, the OpenAPI contract or a migration.
