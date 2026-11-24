# XCH-01 clause 3 — snapshot, stamp and record a disclosure

## Clause

Snapshot the released content, stamp identifiers and versions, and record disclosure.

## Status

Proven by tests

## Evidence

The clause test is [SnapshotReleasedContentStampIdentifiersVersionsRecordTest.java](../../../../apps/api/src/test/java/com/aurevia/exchange/clauses/SnapshotReleasedContentStampIdentifiersVersionsRecordTest.java). It uses the same synthetic Mockito fixtures and tenant context as the neighbouring `exchange` unit tests, with a fixed clock, synthetic UUID constants and a mocked `OrderRepository` / `AuditAppender` / `TransactionTemplate`. It is enabled (no `@Disabled`) and green: 6 tests, 0 failures, 0 errors, 0 skipped.

- `releaseSnapshotsTheExactContentWithVersionOneAndStampedIdentifiers` (rule met) — the release is version 1 at the fixed instant; the order, snapshot, disclosure and work-item stamps are four distinct opaque UUIDs, none of them a client-supplied identifier; the canonical content is reconstructed independently in the `create()` field order using a literal U+001F separator (not the production `OrderInputs.SEPARATOR`) and compared with an offline-precomputed UUID v3 fingerprint (not one produced by the code under test); `appendSnapshot` receives version 1 with that exact content and fingerprint, `appendDisclosure` receives the fingerprint and the bounded expiry `NOW + duration * 60`, and `insertWorkItem` carries the same fingerprint.
- `fingerprintIsDeterministicForTheSameReleasedContentAndChangesWhenContentChanges` (rule met) — two identical releases share a fingerprint but get fresh order identifiers, and changing the clinical question changes the fingerprint.
- `refusesReleaseForAnActorWithoutTheExchangeRole` (negative) — no active membership or a non-ordering role raises `403 FORBIDDEN`, no grant is read and none of the four ledger writes runs; a `DENIED` audit entry is asserted.
- `recordsNoDisclosureWhenTheAuthorizationCannotCoverTheRelease` (negative) — a duration above the grant ceiling raises `403 DISCLOSURE_NOT_AUTHORIZED` with no writes and a `DENIED` audit entry.
- `recordsNoDisclosureWhenTheGrantExpiresBeforeTheDisclosureWould` (negative) — a grant expiring before the requested disclosure window raises the same refusal with no writes and a `DENIED` audit entry.
- `rejectsMissingInputBeforeAnySnapshotIsTaken` (negative) — an invalid identifier raises `422 INVALID_INPUT` before any repository write or audit append.

Production classes exercised through their normal entry point (`OrderService.create`):

- [OrderService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/order/OrderService.java) — the command boundary that re-derives the actor, checks the grant against payload, recipient, purpose and duration, then commits the order, immutable snapshot, disclosure ledger entry and work item in one tenant-guarded transaction; `deny`, `checkGrant` and `disclosureExpiry` carry the refusal and bounded-window rules.
- [OrderInputs.java](../../../../apps/api/src/main/java/com/aurevia/exchange/order/OrderInputs.java) and [OrderView.java](../../../../apps/api/src/main/java/com/aurevia/exchange/order/OrderView.java) — the trust-boundary validation, the canonical-content separator and the stamped released order.
- [OrderRepository.java](../../../../apps/api/src/main/java/com/aurevia/exchange/order/OrderRepository.java) — the RLS-bounded store whose `appendSnapshot`, `appendDisclosure` and `insertWorkItem` writes are verified; the durable schema is [V19__exchange_orders.sql](../../../../db/migrations/V19__exchange_orders.sql), which enables RLS and FORCE RLS on every table and makes snapshots and disclosures immutable.
- [OrderController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/order/OrderController.java) — the thin HTTP adapter for `POST /api/v1/orders`. End-to-end acceptance over real PostgreSQL, including the stamped fingerprint and snapshot immutability, is [OrderExchangeIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangeorder/OrderExchangeIntegrationTest.java).

## Gap

None
