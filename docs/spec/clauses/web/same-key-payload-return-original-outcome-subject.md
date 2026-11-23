# ARC-03 clause 6 — same key and payload return the original outcome subject to current authorization

## Clause

Same key and payload return the original outcome subject to current authorization.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.web.clauses.SameKeyPayloadReturnOriginalOutcomeSubjectTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/web/clauses/SameKeyPayloadReturnOriginalOutcomeSubjectTest.java`),
a live, synthetic, contract-level unit test with no database or HTTP server. Both
tests are active (none disabled) and pass. It proves the rule on the component
that implements it, not on a production API route.

- `sameKeyAndPayloadReplayOriginalOutcomeWhenStillAuthorized` — positive proof.
  A first claim of a key scoped to one organization, caller and command, with a
  server-computed digest of a payload, returns `Decision.PROCEED` with a claim
  token and no outcome; `complete` commits the opaque outcome `order-1:v2:ACCEPTED`.
  Re-claiming with the same scope, key and digest calls the current-authorization
  supplier and returns `Decision.REPLAY` with exactly that original outcome and no
  newly minted claim token.
- `replayIsRefusedWhenAuthorizationNoLongerHolds` — negative proof for the
  authorization condition. After the same commit, re-claiming with a supplier that
  reports the caller is no longer authorized throws an `ApiException` with status
  `404` and code `NOT_FOUND`. No resolution or cached outcome is returned, and the
  generic denial message does not contain the outcome reference.

The adjacent ARC-03 sentence about different payloads under one key is clause 7
and is already proven by the existing
`IdempotencyLedgerTest.reusedKeyWithDifferentPayloadConflicts`; it is deliberately
not re-proved here. That existing test asserts a returned `Decision.CONFLICT`, not
a thrown 409: `IDEMPOTENCY_CONFLICT` is an `ApiErrorCode` constant, not an outcome
of `resolve`.

Production classes exercised by these tests (all in
`com.aurevia.smartclinic.api.contract`): `IdempotencyLedger`, its
`InMemoryIdempotencyLedger` reference implementation, `IdempotencyLedger.Scope`,
`IdempotencyLedger.Resolution`, `IdempotencyKey` and `RequestDigest`.
`InMemoryIdempotencyLedger.resolve` returns `REPLAY` with the committed outcome
only after the current-authorization supplier succeeds, and conceals it with 404
`NOT_FOUND` otherwise. That implementation is documented as a process-local,
non-durable stand-in.

Verified locally:

```
mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=SameKeyPayloadReturnOriginalOutcomeSubjectTest test
```

## Gap

The production half of the clause is absent. `IdempotencyLedger` has no caller
under `apps/api/src/main/java`: `claim` and `complete` are reached only from tests.
There is no `JdbcIdempotencyLedger` behind the interface over a Flyway table with
row level security enabled and forced, no service or thin controller injects the
ledger, no HTTP integration test replays an outcome for a retried
`Idempotency-Key`, and no OpenAPI replay entry backed by it. The only
implementation, `InMemoryIdempotencyLedger`, cannot survive process loss. The
status is therefore `Partly proven`: the rule is real and tested at the contract
layer but is not enforced on any live API route.

Closing the gap needs two separate tasks:

1. A durable transactional ledger store: a new Flyway migration such as
   `db/migrations/V66__idempotency_ledger.sql` creating a tenant-scoped table with
   forced RLS, grants and unique `(organization_id, subject, key)`, plus a
   `JdbcIdempotencyLedger` implementing `IdempotencyLedger` under
   `apps/api/src/main/java/com/aurevia/smartclinic/api/contract/`.
2. HTTP wiring and conformance: a thin controller or interceptor under
   `apps/api/src/main/java/com/aurevia/smartclinic/web/` that calls `claim` with a
   fresh authorization check, an HTTP integration test under
   `apps/api/src/test/java/com/aurevia/smartclinic/it/`, a `Idempotency-Key` replay
   entry in `contracts/openapi.yaml`, and the regenerated
   `apps/staff-web/src/api/generated/openapi.d.ts`.

Open task `READY-42` already owns that production work (it depends on `READY-41`),
so no duplicate task was requested here. Its brief currently specifies a 422
`IDEMPOTENCY_KEY_REUSED` conflict and replay of a stored HTTP status and body,
whereas this contract returns `Decision.CONFLICT` and an opaque outcome reference
that the caller re-authorizes, concealing lapsed authorization with 404
`NOT_FOUND`. A follow-up must reconcile those two semantics rather than build a
second, contradictory replay path; the narrow re-authorization conformance proof
is requested in the worker notes.
