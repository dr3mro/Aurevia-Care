# ARC-03 clause 7 — API test evidence

## Clause

Different payloads using the same key yield conflict.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.web.clauses.DifferentPayloadsUsingSameKeyYieldConflictTest`
(path: `apps/api/src/test/java/com/aurevia/web/clauses/DifferentPayloadsUsingSameKeyYieldConflictTest.java`).
All seven tests are active (none are disabled) and use synthetic data only. They drive the real
`POST /api/v1/order-entry/drafts/{id}/place` route through the Spring MVC pipeline with the
synthetic-auth profile, reusing the same controller, service, interceptor, error handler and fixture
style as the neighbouring `com.aurevia.orderentry.place.PlaceOrderControllerTest`.

- `differentPayloadsUnderOneKeyConflict` — positive proof of this clause. Two drafts carry different
  catalog items, clinical questions and delivery modes. Placing the first with key `shared-key`
  returns 200 with the first payload; placing the second with the same key returns HTTP 409
  `IDEMPOTENCY_CONFLICT`. The first order remains visible, replaying `shared-key` still returns that
  first order and its first payload, and the second draft can still be placed with a fresh key into
  a distinct order — so the conflict created nothing for the second payload.
- `sameKeyAndPayloadReplayTheFirstOrder` — an identical retry of the same draft and key returns the
  same order identifier; no second order is created.
- `missingIdempotencyKeyIsRejected` — an absent header is refused with HTTP 428
  `IDEMPOTENCY_KEY_REQUIRED`; the draft can still be placed with a key afterwards.
- `malformedIdempotencyKeyIsRejected` — a key containing disallowed characters is refused with
  HTTP 422 `INVALID_IDEMPOTENCY_KEY`; the draft remains placeable with a valid key.
- `aClinicianWithoutOrderingRoleCannotPlace` — a member with only the `reception` role is refused
  with HTTP 403 `FORBIDDEN`. Restoring the clinician role and reusing the SAME key succeeds, proving
  the refusal consumed neither the draft nor the key.
- `aPeerTenantCannotReuseTheKey` — the key is scoped to the active tenant. A peer tenant placing its
  own draft under the same key gets a distinct order, and reading the first tenant's order under the
  peer tenant is concealed with HTTP 404 `NOT_FOUND`. This case varies only the organization; the
  caller and command components of the recorded scope are not exercised by this class.
- `staleVersionOnFirstPlacementIsRefused` — a wrong `If-Match` on the first placement is refused with
  HTTP 412 `STALE_VERSION` before any claim; correcting the version and reusing the SAME key succeeds.

Production classes and contracts traced from the normal HTTP entry point:

- `com.aurevia.orderentry.place.PlaceOrderController` — thin adapter;
  `POST /api/v1/order-entry/drafts/{id}/place` binds `If-Match` and `Idempotency-Key` and delegates to
  the injected service. `GET /api/v1/order-entry/orders/{id}` is used to prove which orders exist.
- `com.aurevia.orderentry.place.OrderPlacementService` — `place` builds a scope of
  `(organizationId, subject, "orders.place", key)`, checks the ordering role, and when that scope was
  already processed compares `existing.draftId()` with the incoming draft: a different draft raises
  `ApiException(409, IDEMPOTENCY_CONFLICT)`, while the same draft replays the original order. The
  version precondition is checked only on the first placement, before the processed entry is
  recorded. `get` conceals another tenant's order with 404.
- `com.aurevia.orderentry.place.PlacedOrderRequest` — validates the payload at the trust
  boundary and stores its `canonicalPayload()` fingerprint on the placed order; that fingerprint is
  recorded but is not compared on replay. The idempotency decision in this implementation compares
  draft identifiers, and each saved draft is an immutable payload snapshot, so distinct drafts in
  this proof carry distinct payloads.
- `com.aurevia.api.contract.IdempotencyKey` and `ApiErrorCode` — parse the header into
  428 `IDEMPOTENCY_KEY_REQUIRED` / 422 `INVALID_IDEMPOTENCY_KEY`, and own the 409
  `IDEMPOTENCY_CONFLICT` code. `com.aurevia.api.IfMatch` supplies the 428/422 version
  precondition parsing; the 412 `STALE_VERSION` is decided by `OrderPlacementService.requireVersion`.
- `com.aurevia.orderentry.place.OrderDraft` and `PlacedOrder` — the immutable snapshot
  and placed-order records carried through the responses; placement consumes the draft.
- `com.aurevia.api.ApiErrorHandler` and `com.aurevia.web.TenantContextInterceptor`
  — the machine-readable `$.code` error contract and the tenant/subject context derived from the
  authenticated request.

This proof wires the domain kernel that already exists in `src/main` and adds no database setup.
Persistence and forced row-level security for orders and idempotency state remain the concern of the
existing JDBC integration and migration tests, not of this clause document's test class. The header
and the 409 code already exist in `contracts/openapi.yaml` for this route, so no contract change was
needed.

## Gap

None. ARC-03 clause 7 is built and proven by active tests. This task added proof only; it did not
change production code, an existing test, the OpenAPI contract, a migration or a dependency.
