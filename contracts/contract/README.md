# X-01 — Cross-cutting API contract (ARC-03, ARC-04)

Synthetic-only wire contract for API versioning, idempotency, cursor pagination,
correlation IDs and machine-readable errors. The Java primitives live in
`apps/api/src/main/java/com/aurevia/api/contract`; the worked HTTP example
is in [openapi.yaml](openapi.yaml). Requirement text is in
[PROJECT_V1_SPEC.md](../../.opencode/config/PROJECT_V1_SPEC.md).

This slice adds the missing wire primitives. Correlation IDs, the uniform error
body, the `If-Match` parser and the tenant interceptor were already implemented
in `apps/api` and are **not** duplicated here; they are documented below so the
contract is complete. No route, controller, migration or database table is added
by X-01.

## Obligation map

| Obligation | Requirement | Owner | Status in this slice |
|---|---|---|---|
| Versioned routes under `/api/v1` | ARC-03 | `web.TenantContextInterceptor` (pre-existing) | Only `/api/v1/**` is registered. `ApiVersion` resolves a version header and rejects unsupported values without creating new routes. |
| Stable tenant-scoped cursor pagination | ARC-03, ARC-02 | New `Cursor`, `PageRequest`, `ByteParts` | Opaque base64url token bound to the active tenant; bounded, canonical, uniform error on malformed or foreign tokens. |
| Request/correlation IDs | ARC-03 | `web.TenantContextInterceptor` (pre-existing) | Generated when absent, validated, echoed on the response and in every error body. No new correlation code. |
| Machine-readable errors | ARC-03, ARC-04 | `api.ApiException` + `api.ApiErrorHandler` (pre-existing); new `ApiErrorCode` | New primitives throw `ApiException` with specific codes; the single existing advice renders `{code, message, correlationId}`. |
| Idempotency keys scoped to tenant, caller and command with a request digest | ARC-03 | New `IdempotencyKey`, `RequestDigest`, `IdempotencyLedger` | Wire semantics plus a synthetic in-memory reference implementation. |
| Explicit POST commands for sign, correct, acknowledge, release and exchange | ARC-03 | Owning workflow modules | Contract shape and worked example below; endpoints stay in their feature stories. |
| Version preconditions for draft writes and irreversible transitions | ARC-03, ARC-04 | `api.IfMatch` (pre-existing) + owning service CAS | `STALE_VERSION` is declared here; `412` remains produced by the owning service. |

## Headers and canonical forms

| Header | Rule | Failure |
|---|---|---|
| `X-Correlation-Id` | `[A-Za-z0-9._-]{1,128}`; generated as a UUID when absent; echoed on every response and error body | `422 INVALID_CORRELATION_ID` |
| `X-Active-Organization` | UUID of the explicitly selected active tenant; never inferred from a body, cursor or key | `422 INVALID_TENANT_SELECTION` |
| `X-Synthetic-Subject` | Synthetic authenticated subject, accepted only under the `synthetic-auth` profile; not a production authentication mechanism | `401 UNAUTHENTICATED` |
| `X-Api-Version` | Absent or blank selects `v1`; `v1` and `V1` accepted; any other value rejected | `422 UNSUPPORTED_API_VERSION` |
| `Idempotency-Key` | Required on protected POST commands; trimmed `[A-Za-z0-9._:-]{1,255}` opaque value, scoped to `(organization, subject, command)` | `428 IDEMPOTENCY_KEY_REQUIRED`, `422 INVALID_IDEMPOTENCY_KEY` |
| `If-Match` | Positive integer version validator, strong, weak or quoted; compare-and-set belongs to the owning service | `428 PRECONDITION_REQUIRED`, `422 INVALID_IF_MATCH`, `412 STALE_VERSION` |
| `cursor` / `limit` query | Opaque base64url-without-padding `Cursor` token; limit default `50`, maximum `200` | `422 INVALID_CURSOR`, `422 INVALID_LIMIT` |

All version and idempotency parsing runs after the tenant interceptor, so an
unauthenticated request still receives `401` rather than a parser error.
The canonical request payload is four byte-length-prefixed UTF-8 parts —
`organizationId`, `subject`, `command`, `payload` — each written as a four-byte
big-endian length followed by its bytes. The server computes lowercase-hex
SHA-256 over those bytes; clients never submit a digest. Length prefixes keep
boundaries unambiguous for mixed-script Arabic and English text. The cursor uses
the same canonical encoding for `organizationId`, `sortKey` and `id`, then
base64url without padding; a padded or otherwise non-canonical token is
rejected. Each decoded part is capped at 256 bytes and a whole token at 1200
characters.

## Idempotency decision table

A completed outcome is an opaque resource reference such as
`order-1:v2:ACCEPTED`; it is not a cached HTTP response.

| Stored state | Incoming digest | Current authorization | Result |
|---|---|---|---|
| None | any | checked by normal protected path | `PROCEED` with an in-flight claim token |
| In progress | same | not evaluated | `409 IDEMPOTENCY_CONFLICT` (concurrent duplicate) |
| Completed | different | not evaluated | `409 IDEMPOTENCY_CONFLICT` (key reused for another payload) |
| Completed | same | denied | `404 NOT_FOUND`; the outcome is concealed |
| Completed | same | granted | `REPLAY` of the original outcome reference |
| Failed command | any | checked on retry | Claim released; failure is never cached |
| Retry key expired | any | checked by normal protected path | New `PROCEED`; no replay from the cache |

Replay re-authorizes the loaded resource through the owning module's normal
protected path, mints a fresh correlation id for the current request and does
not re-emit the domain audit event. The ledger is keyed by tenant, caller and
command; equal values in a different tenant, caller or command never collide.
An expired retry key does **not** license replaying an arbitrary old outcome:
durable business uniqueness for external clinical actions must be a
transactional constraint in the owning module (for example a stable submission
identifier), not a process-local cache.

## Error code table

Every body is `{ "code": ..., "message": ..., "correlationId": ... }` and never
contains clinical payloads, stack traces or resource existence details.

| Status | Code | Owner |
|---|---|---|
| `401` | `UNAUTHENTICATED` | `web.TenantContextInterceptor` |
| `404` | `NOT_FOUND` | `api.SqlFailureTranslator`; `IdempotencyLedger` replay concealment |
| `409` | `IDEMPOTENCY_CONFLICT` | `api.contract` primitives |
| `409` | `INVALID_TRANSITION`, `CONFLICT`, `SLOT_CONFLICT` | Owning workflow service / `api.SqlFailureTranslator` |
| `412` | `STALE_VERSION` | Declared in `api.contract`; produced by owning service compare-and-set |
| `422` | `UNSUPPORTED_API_VERSION`, `INVALID_IDEMPOTENCY_KEY`, `INVALID_SCOPE`, `INVALID_CURSOR`, `INVALID_LIMIT` | `api.contract` primitives |
| `422` | `INVALID_CORRELATION_ID`, `INVALID_TENANT_SELECTION`, `INVALID_IF_MATCH` | Pre-existing interceptor / `api.IfMatch` |
| `428` | `IDEMPOTENCY_KEY_REQUIRED`, `PRECONDITION_REQUIRED` | `api.contract` / `api.IfMatch` |
| `500` | `INTERNAL_ERROR` | Generic handler; used when SHA-256 is unavailable, with no payload detail |

## Worked example — ARC-04

`POST /api/v1/orders/{id}/accept` requires an authenticated destination context,
`Idempotency-Key` and `If-Match`. The body contains the validated local
requisition association and an optional receiving assignee. On success the
service commits the new state, the acceptance event and the audit record in one
transaction and returns the order id, accepted state, new version and
timestamp. `401` for unauthenticated, `404` for an inaccessible order (existence
concealed), `409` for an invalid transition or a reused key with a different
payload, `412` for a stale version, `422` for invalid business inputs. Analogous
transitions use identical semantics. This route is owned by the exchange
stories; the contract fragment documents the shape only and X-01 does not
register an endpoint.

The same envelope applies to the explicit POST command families named by
ARC-03: sign, correct, acknowledge, release and exchange. They never mutate
state through `GET`, and each carries `X-Correlation-Id`,
`X-Active-Organization`, `Idempotency-Key`, `If-Match` and a server-computed
request digest as appropriate to the transition.

## Durability and scope limits

`InMemoryIdempotencyLedger` is a synthetic, process-local, single-node stand-in
that demonstrates the wire semantics and unit tests only. It does not survive
process loss and cannot satisfy the atomic state/event/audit commit of ARC-04 or
the durable event deduplication of ARC-05. Production idempotency, replay and
dead-letter handling belong to X-02 with a database-backed claim and outbox in a
migration. The reserved migration version for X-01 is `V54`; it is **not**
consumed because `db/migrations` is outside this story's scope, and version gaps
are permitted by the project's migration policy. Before live enablement,
signing, patient release, critical-result acknowledgment and prescribing need
the durable ledger plus explicit clinical and architecture review.

## Entry point to test trace

| Entry point | Primitive | Test |
|---|---|---|
| `X-Api-Version` header | `ApiVersion.from` | `ApiContractPrimitivesTest.versionDefaultsToV1AndExposesItsRoute`, `versionAcceptsCaseInsensitiveV1`, `versionRejectsUnsupportedValue` |
| `Idempotency-Key` header | `IdempotencyKey.parse` | `ApiContractPrimitivesTest.idempotencyKeyIsRequired`, `idempotencyKeyRejectsMalformedValues`, `idempotencyKeyAcceptsOpaqueTrimmedValue` |
| Canonical command payload | `RequestDigest.canonicalBytes`, `RequestDigest.of` | `ApiContractPrimitivesTest.digestCanonicalBytesAreLengthPrefixedAndOrderSensitive`, `digestComputesLowercaseHexSha256`, `digestFailsClosedWhenSha256IsUnavailable` |
| `cursor` and `limit` query | `ByteParts`, `Cursor.decode`, `PageRequest.parse` | `ApiContractPrimitivesTest.bytePartsRoundTripUtf8WithExplicitLengths`, `bytePartsRejectMalformedPayloads`, `cursorRoundTripsTenantScopedOrderingKey`, `cursorRejectsInvalidTokens`, `cursorRejectsNonCanonicalPaddedToken`, `pageAcceptsCursorAndBoundedLimit`, `pageRejectsInvalidLimitsAndForeignCursor` |
| Protected POST replay path | `IdempotencyLedger.claim`, `complete`, `fail`, `expire` | `IdempotencyLedgerTest.unseenClaimProceedsAndCompletedClaimReplaysAuthorizedOutcome`, `reusedKeyWithDifferentPayloadConflicts`, `replayRechecksCurrentAuthorizationAndConcealsOutcome`, `expiringARetryKeyStartsANewClaimWithoutReplaying` |
| Thrown `ApiException` | `ApiErrorCode.exception` | Existing `api.ApiErrorHandler` renders the uniform body; `ApiContractPrimitivesTest.errorCodesCarryStatusMessageAndException` |

Coverage is tool-generated from `mvn -Pno-security,fast test jacoco:report`:
100% instruction, branch, line, method and class for every new production class.
No clinical or human participant evidence is claimed.
