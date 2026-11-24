# ARC-03 clause 5 — evidence and gap analysis

## Clause

Use idempotency keys scoped to tenant, caller and command with a request digest.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.web.clauses.UseIdempotencyKeysScopedTenantCallerCommandTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/web/clauses/UseIdempotencyKeysScopedTenantCallerCommandTest.java`).
All six tests are active (none disabled) and use synthetic identifiers, a fixed
clock and 64-hex digests. They drive the real HTTP entry point through the
Spring MVC pipeline with the synthetic-auth profile, the same wire-up, fixtures
and style as the neighbouring
`com.aurevia.smartclinic.events.clauses.ConfirmOrderPatientIdentifiersBeforeLinkingExternalTest`.
Every refusal case asserts the machine-readable status and that no second
success audit was created and the dedup mapping still points to the first event;
the service throws before the atomic insert, so no second outbox row is written.

- `sameKeyAndDigestReplayTheFirstOutcomeWithoutASecondOutboxRow` — positive proof.
  Two identical publishes of `POST /api/v1/delivery/events` (same tenant, sender,
  event type, key and digest) return the same event id; there is one success
  audit, one duplicate audit and no second outbox row.
- `reusedKeyWithDifferentDigestConflictsWithoutASecondWrite` — different-payload
  proof. Reusing the key with another digest is refused with HTTP 409
  `IDEMPOTENCY_CONFLICT`; the original event remains the only one bound to the
  key and only one success audit exists.
- `theKeyIsScopedToItsTenantSoAnotherTenantCannotReplayIt` — tenant-scope proof.
  A worker in a peer tenant reusing the identical key and payload gets a fresh
  event, not a replay; each tenant's outbox holds only its own row.
- `aDifferentCallerAndCommandWithTheSameKeyDoNotReplay` — caller- and
  command-scope proof. Changing the sender or the event type while keeping the
  key yields distinct events instead of a replay.
- `missingIdempotencyKeyIsRejectedWithoutAnyWrite` — input-boundary proof. A
  blank key is refused with HTTP 422 `INVALID_IDEMPOTENCYKEY` before any state
  change.
- `aReplayStillRequiresCurrentAuthorization` — re-authorization proof. An
  active operator (which lacks the publish action) cannot replay a prior key;
  the request is refused with HTTP 403 `FORBIDDEN` and no second write.

Classes and contracts traced from the normal HTTP entry point (this proof wires
the test-only `InMemoryDeliveryStore`; persistence and forced row-level security
are proven by the existing database clause tests, not by this document's test
class):

- `com.aurevia.smartclinic.events.DeliveryController` — thin adapter;
  `POST /api/v1/delivery/events` binds the command and delegates to the service.
- `com.aurevia.smartclinic.events.DurableEventService` — `publish` derives the
  tenant from `TenantContext`, authorizes the action, scopes the dedup key by
  tenant, sender and event type, binds it to the payload digest, returns the
  first outcome on an identical retry, refuses a different digest with 409 and
  records a `DUPLICATE` audit for a replay.
- `com.aurevia.smartclinic.events.DeliveryActions`,
  `com.aurevia.smartclinic.events.DeliveryInputs` and
  `com.aurevia.smartclinic.api.contract.Sha256Hex` — bounded publish privilege,
  token and digest validation at the trust boundary.
- `com.aurevia.smartclinic.events.InMemoryDeliveryStore` — the tenant-keyed test
  fixture behind the `DeliveryStore` port; its production adapter
  `com.aurevia.smartclinic.events.JdbcDeliveryStore` is exercised by the
  existing database tests over the V55 `delivery_outbox` tables with forced row
  level security.
- `com.aurevia.smartclinic.api.ApiErrorHandler` and
  `com.aurevia.smartclinic.web.TenantContextInterceptor` — the uniform error
  contract (`$.code`) and tenant context derived from the authenticated request.

Existing proof observed in the source survey and not changed by this task:
`events/DurableEventServiceTest` (`publishWithSameKeyAndDigestReturnsTheOriginalEvent`
and `publishWithSameKeyAndDifferentDigestIsAConflict`) covers the same semantics
at unit level, and
`events/clauses/CommitStateAuditOutboxRecordAtomicallyApplicableTest`
(`sameKeyWithDifferentDigestConflictsWithoutASecondWrite`) proves the digest
conflict over real PostgreSQL, and
`orderentry/place/OrderPlacementServiceTest`
(`reusing_a_key_for_a_different_draft_conflicts`) proves the order-placement
conflict. The cross-cutting contract primitives
`IdempotencyKey`, `RequestDigest`, `ApiErrorCode` and `IdempotencyLedger` are
actively exercised by `api/contract/ApiContractPrimitivesTest` (428 absent, 422
malformed, server-computed canonical digest) and `api/contract/IdempotencyLedgerTest`
(`scopeIsTenantCallerAndCommandBound`, `reusedKeyWithDifferentPayloadConflicts`,
`replayRechecksCurrentAuthorizationAndConcealsOutcome`,
`expiringARetryKeyStartsANewClaimWithoutReplaying`). The 428 `Idempotency-Key`
header path is actively proven by `pharmacyflow/status/PharmacyStatusControllerHttpTest`
and neighbours.

The clause is only partly proven because of the caveats in `## Gap`; this task
adds proof only and does not claim `Proven by tests`. The route
`POST /api/v1/delivery/events` is already listed in `contracts/openapi.yaml`;
route parity and the absent generic `Idempotency-Key` header on some flows are
pre-existing contract debt, out of scope for this proof-only change.

## Gap

The generic contract in `com.aurevia.smartclinic.api.contract` is not reachable
from any production HTTP path, so the clause is not proven as written:

1. **Tenant, caller and command scope with a server-computed digest.** The
   `IdempotencyLedger` `Scope` record is tenant, caller and command bound and
   `RequestDigest` computes a server-side SHA-256 fingerprint, but the ledger
   has zero production callers and no JDBC store or migration: only the
   non-durable `InMemoryIdempotencyLedger` is used, by a unit test
   (`docs/decisions/0003-persistence-of-in-memory-stores.md:21,37,39,46,51`).
   The events route used for this proof scopes the key by tenant, body `sender`
   (not the authenticated subject) and event type, and validates a
   caller-supplied digest; it does not bind the authenticated caller or compute
   the digest from the canonical request. The order-placement route
   (`orderentry/place`) uses the authenticated subject and a canonical payload
   but keeps its replay index in memory and stores the canonical payload without
   comparing it on retry (`OrderPlacementService.java:123,132`).
2. **Different payloads using the same key yield conflict.** Proven on the
   events route here and on the order-placement route by
   `OrderPlacementServiceTest.reusing_a_key_for_a_different_draft_conflicts`
   (`409 IDEMPOTENCY_CONFLICT`, named in `## Evidence`), but not through the
   generic ledger's `resolve` decision.
3. **Expired retry keys must still respect durable business uniqueness for
   external clinical actions.** This component is NOT proven. `IdempotencyLedger.expire`
   is covered only by a unit test, and no durable retry-key expiry or business
   uniqueness constraint for external clinical actions is reachable in
   production; V55's `idempotency_key` column is delivery specific and does not
   implement this rule. Proving it needs the same durable replay-ledger wiring
   as item 1.
4. **Same key returns the original outcome subject to current authorization.**
   Proven through the events route (`aReplayStillRequiresCurrentAuthorization`)
   and, at unit level, by `IdempotencyLedgerTest`'s re-authorization case; the
   generic ledger's `stillAuthorized` gate is not exercised by a production
   caller.

Closing the gap requires a durable replay ledger in the owning module: a new
Flyway table with tenant ownership, forced row-level security and a uniqueness
constraint, a `Jdbc*` adapter behind the `IdempotencyLedger` port, binding the
authenticated caller and a server-computed `RequestDigest` at a real protected
HTTP command, and an HTTP-level integration test with active proof of all four
components. That is one cross-boundary implementation task per owning flow
(migration + store + service + controller + OpenAPI and regenerated client),
plus a separate task to add the expired-retry durable-uniqueness rule; the exact
flow and Product Owner confirmation of the conflict policy are needed before
those tasks can be written. OpenAPI route parity is pre-existing debt and is
not part of this gap.

This gap is already tracked in the backlog: `tasks/00096000-READY-42.md`
durable idempotency ledger for write endpoints (ARC-03/ARC-04, reserved V66),
and the sibling clause tasks `SPEC-ARC-03-C06`, `SPEC-ARC-03-C07` and
`SPEC-ARC-03-C08` for replay, conflict and expired-retry durable uniqueness.
No new task request is raised by this proof-only change.
