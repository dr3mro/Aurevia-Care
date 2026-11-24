# Partner integration guide — exchange API

Audience: an engineer connecting a laboratory or imaging partner to Orevia to
receive orders and return results. This guide describes what the service does
today. Every path, state, header and error code below is taken from the committed
contract or the running application code; links point at the exact source.
Anything not yet implemented is marked `PLANNED:` with the open tracker task id.

All names and values in the examples are invented. The base URL is supplied by
the site at onboarding; no host, credential or patient value is hard-coded in
this document or in the examples. Use synthetic data in every test environment.

## Authoritative sources and their limits

| Topic | Source |
|---|---|
| Shared wire conventions (headers, error body, `If-Match`) | [cross-cutting contract fragment](../../contracts/contract/openapi.yaml) |
| Catalog-generated command contract | [generated OpenAPI](../../contracts/openapi/generated/openapi.yaml) |
| Durable delivery, dedup and retry contract | [delivery contract](../../contracts/events/README.md) |
| Requirement text | [specification, sections 4 and 7–8.1](../../.opencode/config/PROJECT_V1_SPEC.md) |
| Exchange implementation | [exchange package](../../apps/api/src/main/java/com/aurevia/exchange) |

The catalog-generated contract is emitted from the Java command catalog, not
hand-authored. Its own [README](../../contracts/openapi/README.md) states that it
does **not** prove that a running `@PostMapping` calls the authorization gate or
that every documented path matches a deployed route. The drift is concrete: the
generated contract advertises `/api/v1/orders/{id}/accept` and
`/api/v1/critical/{id}/acknowledge`, while the controllers register
`/api/v1/xch03/orders/{id}/accept` and
`/api/v1/critical-results/{id}/acknowledge`. Treat the controller path in this
guide as authoritative and confirm paths during onboarding. Where the documented
contract and the controllers disagree, this guide says so explicitly (see
[Request conventions](#4-request-conventions) and
[Idempotency and replay](#7-idempotency-and-replay)). Do not rely on a documented
header being enforced merely because it appears in the generated file.

## 1. Transport

Use the versioned prefix `/api/v1` under the base URL given by the site. Only v1
exists; an optional `X-Api-Version` header selects v1 (absent, blank, `v1` or
`V1` are accepted) but cannot create a new route prefix. The version helper lives
in [ApiVersion.java](../../apps/api/src/main/java/com/aurevia/api/contract/ApiVersion.java);
the protected interceptor registers `/api/v1/**` only
([TenantContextInterceptor.java](../../apps/api/src/main/java/com/aurevia/web/TenantContextInterceptor.java)).
Transport encryption, HSTS, WAF and edge rate limiting are deployment-level
requirements of WEB-01 and WEB-04 in the specification; they are not implemented
by the application code described here and must be confirmed with the site owner.

## 2. Authentication

In every non-synthetic profile the API is a stateless OIDC resource server
([SecurityConfiguration.java](../../apps/api/src/main/java/com/aurevia/web/SecurityConfiguration.java)):

- Send `Authorization: Bearer <access-token>` on every protected request.
  A missing or unverified bearer token is rejected with `401 UNAUTHENTICATED`.
  Startup fails closed when no OIDC issuer is configured.
- The [tenant interceptor](../../apps/api/src/main/java/com/aurevia/web/TenantContextInterceptor.java)
  reads the already-verified claims. It requires a non-blank `sub` and a non-empty
  `orgs` claim whose entries are UUIDs
  ([ClaimsTenantResolver.java](../../apps/api/src/main/java/com/aurevia/web/ClaimsTenantResolver.java)).
- A partner credential must be scoped by the identity provider. The policy for
  issuing and rotating partner credentials is
  `PLANNED: SPEC-LAYER-IDENTITY-4`; until it is delivered, agree the credential
  scope with the site owner during onboarding.
- `X-Synthetic-Subject` is accepted **only** under the `synthetic-auth` profile.
  It is a test fixture, not a production authentication mechanism. Never send it
  to a deployed service.
- Client-supplied role, permission, actor or scope fields carry no authority.
  The generated client keeps such fields inert, and the server re-derives
  membership and roles from the active organization on every protected action
  ([CommandAuthorization.java](../../apps/api/src/main/java/com/aurevia/api/authorization/CommandAuthorization.java)).

## 3. Tenant selection

The active tenant is explicit and per request:

1. Send `X-Active-Organization` containing a UUID.
2. It must appear in the token's `orgs` claim. A well-formed but unentitled
   organization is `403 ORGANIZATION_NOT_PERMITTED`; a missing or malformed one is
   `422 INVALID_TENANT_SELECTION`.
3. The resolved tenant is never inferred from the request body, a path or query
   resource id, the pagination cursor, the first membership or a patient value.
   The interceptor binds it for the request and clears it on completion. Domain
   services open a guarded transaction that binds the tenant locally with
   `set_config(..., true)` and forced row-level security, so a pooled connection
   cannot leak one tenant's context into another request
   ([PROJECT.md decision for ARC-02](../../.opencode/config/PROJECT.md)).
4. Membership and roles are re-read on every protected operation. A deactivated
   subject is denied on the next request with `403 FORBIDDEN`; resources outside
   the active tenant are concealed with `404 NOT_FOUND`, not distinguished from
   a missing row.

Do not attempt to select a tenant by changing an id in a body or URL. That is
either ignored for tenant scope or denied; it never grants cross-tenant access.

## 4. Request conventions

| Header / value | Required where | Exact behavior |
|---|---|---|
| `X-Correlation-Id` | optional | Pattern `[A-Za-z0-9._-]{1,128}`; an invalid value is `422 INVALID_CORRELATION_ID`. When absent a UUID is generated. It is echoed on the response and carried in the error body. Use a fresh one for each retry. |
| `If-Match` | every irreversible transition | Positive integer version; bare `3`, quoted `"3"` and weak `W/"3"` are accepted. Absent is `428 PRECONDITION_REQUIRED`; malformed or < 1 is `422 INVALID_IF_MATCH`; a version that no longer matches is `412 STALE_VERSION` ([IfMatch.java](../../apps/api/src/main/java/com/aurevia/api/IfMatch.java)). |
| `Idempotency-Key` | documented on protected POST commands | Bounded `[A-Za-z0-9._:-]{1,255}`, scoped to tenant + subject + command; absent is `428`, malformed `422 INVALID_IDEMPOTENCY_KEY`. **The exchange controllers parse `If-Match` but do not parse this header today.** See [Idempotency and replay](#7-idempotency-and-replay). |
| `cursor`, `limit` | list endpoints | Cursor is the opaque canonical `<createdAt>:<id>` of the previous page's last row; `limit` is 1–200, default 50. Malformed values are `422 INVALID_CURSOR` / `INVALID_LIMIT`. |

Request header and body size caps, page-size/request-time enforcement at the edge,
and the exact-origin CORS allowlist are Web-tier protections in WEB-04 but are
not present in this application tree: `PLANNED: SPEC-WEB-04-C03` and
`PLANNED: SPEC-WEB-04-C04`. Enforce them at the gateway until delivered. Do not
send clinical payloads in URLs, query strings or logs.

## 5. Order and result lifecycles — every state and what moves it

The service deliberately keeps independent state channels. A transport receipt
can never close a clinical task, and patient release is not a clinical
acknowledgment (XCH-02, XCH-06).

### 5.1 Exchange task channels (XCH-02)

An [exchange task](../../apps/api/src/main/java/com/aurevia/exchange/task)
carries three channels and one shared `version` that increments on every command.
Read the task again before each command; do not reuse an earlier `If-Match`.

| Channel | States | Command that moves it |
|---|---|---|
| Workflow | `requested → accepted → processing → completed` | `accept`, `process`, `complete`. From `requested` a partner may `reject` (reason required, terminal) or `cancel` (reason required, terminal); from `accepted` or `processing`, `cancel` is legal. |
| Transmission | `pending → sent → delivered` or `failed` | `send`, `deliver`, `fail`. `delivered` and `failed` are terminal; a retry is a new work item, not a reversal. |
| Clinical review | `unreviewed → acknowledged → action_pending → resolved` | `acknowledge`, `request-action`, `resolve`. Only an authorized clinician may act; the actor is derived from the authenticated subject, and the revision and timestamp are bound to the command. |

Sources: [WorkflowState.java](../../apps/api/src/main/java/com/aurevia/exchange/task/WorkflowState.java),
[TransmissionState.java](../../apps/api/src/main/java/com/aurevia/exchange/task/TransmissionState.java),
[ClinicalReviewState.java](../../apps/api/src/main/java/com/aurevia/exchange/task/ClinicalReviewState.java),
[ExchangeCommand.java](../../apps/api/src/main/java/com/aurevia/exchange/task/ExchangeCommand.java),
[ExchangeStateMachine.java](../../apps/api/src/main/java/com/aurevia/exchange/task/ExchangeStateMachine.java).
Commands are explicit POSTs to `/api/v1/exchange-tasks/{id}/{command}` with an
`If-Match` header
([ExchangeTaskController.java](../../apps/api/src/main/java/com/aurevia/exchange/task/ExchangeTaskController.java)).
A reason for `reject`/`cancel` is required (`422 REASON_REQUIRED`) and bounded to
500 characters; an illegal transition is `409 INVALID_TRANSITION`. Workflow is
available to `lab_collector` and `clinician`, transmission also to `tenant_admin`,
and clinical review is clinician-only.

### 5.2 Released order (XCH-01, XCH-03)

Release is `POST /api/v1/orders`. The sending tenant's `clinician` role is
required, and an approved authorization must match the order code, recipient,
purpose and duration and still be inside its validity window; otherwise the
service returns `403 DISCLOSURE_NOT_AUTHORIZED`. The service snapshots the exact
released content with a deterministic fingerprint, writes a disclosure ledger
row and projects a bounded work item to the destination
([OrderService.java](../../apps/api/src/main/java/com/aurevia/exchange/order/OrderService.java)).
Sharing an order grants **no general chart access**.

The receiver sees `GET /api/v1/exchange/work-items`. A work item is `awaiting_local_patient`
until a receiver clinician binds a locally verified patient through
`POST /api/v1/exchange/work-items/{id}/verify-patient`; it then becomes `ready`.
The MRN and issuer are checked against the local patient and the work item; a
mismatch is `403 IDENTITY_MISMATCH`, a repeat bind is `409 ALREADY_VERIFIED`
([WorkItemView.java](../../apps/api/src/main/java/com/aurevia/exchange/order/WorkItemView.java)).

Recipient-side state for an exchanged order moved by
[ExchangeResponseController.java](../../apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseController.java)
and [ExchangeState.java](../../apps/api/src/main/java/com/aurevia/exchange/response/ExchangeState.java):

| From | Command | To |
|---|---|---|
| `requested` | `accept` (verified local patient, permitted purpose, retention) | `accepted` |
| `requested` | `reject` (reason) | `rejected` (terminal) |
| `requested` | `clarify` (question) | `clarification_requested` |
| `accepted` | `propose-change` (linked new revision) | `change_pending` |
| `change_pending` | `change-response` accept / reject | `accepted` (accepted revision promoted and retained) / `rejected` |
| `accepted` | `request-cancellation` (reason) | `cancellation_pending` |
| `cancellation_pending` | `cancellation-response` confirm / decline | `cancelled` (terminal) / `accepted` |
| any non-terminal | `revoke` (reason, retention, downstream notification) | `revoked` (terminal) |

`retrieve` returns the current released revision on every call and re-checks
revocation: a revoked release returns `410 RETRIEVAL_REVOKED`
([ExchangeResponseService.java](../../apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseService.java)).
A proposed change is held in the revision ledger until the recipient responds;
refusing it never serves unretained content.

### 5.3 Results and reconciliation (XCH-04)

Register the identifier expectation with `POST /api/v1/xch04/orders`, then import
with `POST /api/v1/xch04/results`. Before any import the service matches five
issuer-scoped identifiers — patient, source, accession, destination and order.
No expectation is `UNMATCHED`; a disagreement is `CONFLICT`. Both divert the
report to the reconciliation queue and return `202` with a reconciliation entry
instead of importing it. A matched report is routed to the named owner and
coverage queue and returns `201` with an immutable revision and an `unreviewed`
review task
([ResultRoutingService.java](../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRoutingService.java),
[ResultRules.java](../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRules.java)).

| Value | Meaning |
|---|---|
| Result kind `final` | A first report. A repeat with identical identifiers, kind, effective time and checksum resolves to the original revision; no second report or notification is created. `supersedesVersion` is not allowed. |
| Result kind `corrected` | Must name `supersedesVersion`, which must equal the current lineage tip. It appends the next revision, links the previous one, notifies prior recipients and reopens review even if the prior revision was acknowledged. A stale target is diverted to reconciliation. |
| Result kind `critical` | Imports and routes the report with a critical marker. Due-time escalation and human acknowledgment are the XCH-05 workflow below; import alone closes nothing. |
| Review state `unreviewed → acknowledged` | A clinician acknowledges one exact revision with `If-Match` at `POST /api/v1/xch04/results/{id}/acknowledge`. A repeat is `409 ALREADY_ACKNOWLEDGED`; a stale version is `412`. |
| Reconciliation entry `open` | An unmatched or conflicting report gets an `open` entry, listed with `GET /api/v1/xch04/reconciliations?status=open\|resolved`. It stays reprocessable; it is not consumed as a duplicate. Only listing is implemented today — no route moves an entry to `resolved` (`PLANNED: BUILD-08` covers operator match/reject/reassign). |

### 5.4 Critical findings (XCH-05)

Register with `POST /api/v1/critical-results`, then `assign` a responsible
owner and due time **before** `verify`: verification with no owner or due time is
`409 OWNER_REQUIRED`. The subsequent commands are
`assign → verify → escalate → read-back → acknowledge → follow-up`, each a
separate POST with an `If-Match` precondition
([CriticalResultController.java](../../apps/api/src/main/java/com/aurevia/exchange/critical/CriticalResultController.java),
[CriticalResultService.java](../../apps/api/src/main/java/com/aurevia/exchange/critical/CriticalResultService.java)).
The state channel is `unverified → awaiting_ack → acknowledged → closed`; each
step emits its own append-only event (owner assigned, verification, escalation
attempt, read-back, acknowledgment, follow-up, roster gap, patient release).
`escalate`, `read-back` and `acknowledge` require `awaiting_ack`; `follow-up`
requires `acknowledged`. Registration and verification use a laboratory verifier
role, assignment and escalation are clinician or tenant-admin actions, and
read-back, acknowledgment and follow-up are clinician actions; each service
re-derives the role from the active tenant. `report` surfaces overdue items,
roster gaps and failed notifications. A delivery receipt, a transmission
acknowledgment or a patient release does **not** acknowledge the alert; only an
authorized clinician's read-back / acknowledgment can
([CriticalContract.java](../../apps/api/src/main/java/com/aurevia/exchange/critical/CriticalContract.java)).

## 6. What errors look like

All protected failures use one machine-readable body with no stack trace, SQL,
other tenant's data or internal class name
([ApiErrorHandler.java](../../apps/api/src/main/java/com/aurevia/api/ApiErrorHandler.java)):

```json
{ "code": "STALE_VERSION", "message": "the exchange task changed since it was read", "correlationId": "corr-synthetic-01" }
```

| Status | Codes a partner will see (examples) | Meaning |
|---|---|---|
| 401 | `UNAUTHENTICATED` | No verified identity. |
| 403 | `FORBIDDEN`, `ORGANIZATION_NOT_PERMITTED`, `IDENTITY_MISMATCH`, `DISCLOSURE_NOT_AUTHORIZED` | Authenticated but denied. Do not retry unchanged. |
| 404 | `NOT_FOUND` | Inaccessible or missing resource; existence is concealed. |
| 409 | `INVALID_TRANSITION`, `CONFLICT`, `ORDER_EXISTS`, `ALREADY_VERIFIED`, `ALREADY_ACKNOWLEDGED`, `PAYLOAD_CONFLICT`, `OWNER_REQUIRED` | Conflict or a same-key/different-payload collision. Resolve before retrying. |
| 410 | `RETRIEVAL_REVOKED` | The release was revoked. Terminal. |
| 412 | `STALE_VERSION` | Re-read the resource and its version, then resend with a new `If-Match`. |
| 422 | `INVALID_INPUT`, `INVALID_IF_MATCH`, `INVALID_CURSOR`, `INVALID_LIMIT`, `INVALID_KIND`, `REASON_REQUIRED` | Fix the request; do not replay it unchanged. |
| 428 | `PRECONDITION_REQUIRED`, `IDEMPOTENCY_KEY_REQUIRED` | A required header is missing. |
| 429 | `RATE_LIMITED` | Back off for the seconds in the `Retry-After` header. |
| 500 | `INTERNAL_ERROR` | Generic failure; report the correlation id. |

Every response carries `X-Correlation-Id`. Quote it when contacting support —
it never contains a patient value.

## 7. Idempotency and replay

Two distinct mechanisms exist. Use them as follows.

- **Natural-key replay that is implemented.** A repeated result import whose
  identifiers, kind, clinical effective instant and checksum all agree with a
  stored revision returns that exact revision (`201`) and creates no duplicate
  report or notification. The same key with different clinical content is `409
  PAYLOAD_CONFLICT`, never a silently discarded finding
  ([ResultRoutingService.java](../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRoutingService.java)).
  Version and supersession fields are lineage metadata and are deliberately not
  part of the fingerprint.
- **Header-based idempotency that is specified but not yet wired into these
  controllers.** The contract documents `Idempotency-Key` scoped to tenant,
  caller and command, and the shared [IdempotencyLedger contract](../../apps/api/src/main/java/com/aurevia/api/contract/IdempotencyLedger.java)
  defines claim → replay → complete semantics with re-authorization on replay.
  The shipped implementation is `InMemoryIdempotencyLedger`, a single-process
  synthetic reference that is not durable and is used by no exchange controller.
  A durable, transactional ledger is `PLANNED: READY-42`. Until then, a retried
  exchange POST is **not** made safe by the header alone: rely on the natural-key
  replay above and on the version precondition.

For event-driven partners, the [delivery contract](../../contracts/events/README.md)
and the [conformance ledger](../../apps/api/src/main/java/com/aurevia/exchange/conformance/ConformanceLedger.java)
define the receiver rules: deduplicate external events inside the
`(sender, event type, external event id)` namespace; hold lower revisions rather
than writing over a later one; retry transient failures with bounded backoff and
a maximum of three attempts before the restricted dead-letter queue; keep a
dead-lettered event reprocessable so a replay after recovery produces exactly one
effect. Only a successfully applied event consumes its identity. Identifier
matching is issuer-scoped and type-qualified, and an identifier valid on the
wrong order is reconciled, not linked. Corrections preserve the prior revision
and reopen review.

On a retry: generate a fresh correlation id, re-read the current version, send
the same payload, and treat `409`, `410`, `412` and `422` as outcomes to resolve,
not as reasons to hammer the endpoint. Honour `Retry-After` on `429`.

## 8. What a partner may rely on — and must not

**May rely on**

- An explicit tenant derived from the bearer token's `orgs` claim and the
  `X-Active-Organization` header, with per-request membership checks and forced
  RLS; cross-tenant resources are concealed with `404`.
- Machine-readable errors with a stable `code` and a correlation id on every
  failure.
- `If-Match` compare-and-set on irreversible transitions; stale writes fail.
- Independent workflow, transmission and clinical channels: a delivery receipt
  never closes a clinical task, and patient release never acknowledges a
  critical finding.
- Natural-key replay for results, immutable revision history, correction
  notifications, and queueing (not resolution) of unmatched or conflicting
  reports.
- Bounded fields, cursor pagination, audited reads and writes, and an
  instance-local rate limit with `429` + `Retry-After`.

**Must not**

- Treat `X-Synthetic-Subject` as production authentication, or send a patient
  value as a tenant selector.
- Assume a client-supplied role, permission, scope or actor grants authority.
- Assume an `Idempotency-Key` header alone makes an exchange write replay-safe
  (`PLANNED: READY-42`), or that CORS and request-size caps are enforced by this
  service (`PLANNED: SPEC-WEB-04-C03`, `PLANNED: SPEC-WEB-04-C04`).
- Treat `410 RETRIEVAL_REVOKED` as transient, or retry `409`/`403`/`422` blindly.
- Cache a released revision past a correction; re-read through the retrieve
  route, which re-checks revocation on every call.
- Assume a documented catalog path is a deployed route: the generated contract
  does not prove route wiring, and its accept/acknowledge paths differ from the
  real ones in section 5.

## 9. Worked example — synthetic imaging hand-off

Every value below is invented (reserved `.invalid` style names and repeated-digit
UUIDs). Do not use them outside a test environment.

1. **Token and tenant.** The partner service authenticates with a bearer token
   whose claims include `sub: partner-tech-07` and
   `orgs: ["33333333-3333-4333-8333-333333333333"]` (the destination
   organization). It sends `X-Active-Organization: 33333333-3333-4333-8333-333333333333`
   and `X-Correlation-Id: corr-synthetic-01`.

2. **Sender authorizes.** The sending tenant's `tenant_admin` previously created
   an authorization for order code `IMAGING-CT-CHEST`, recipient
   `33333333-3333-4333-8333-333333333333`, purpose `DIAGNOSTIC_IMAGING`,
   `maxDurationMinutes: 240`, `validForDays: 30`.

3. **Release the order.** `POST /api/v1/orders` with invented values:
   `patientId: 22222222-2222-4222-8222-222222222222`,
   `encounterRef: ENC-SYN-9001`, `orderCode: IMAGING-CT-CHEST`,
   `priority: routine`, `clinicalQuestion: Synthetic chest imaging question`,
   `destinationOrg: 33333333-3333-4333-8333-333333333333`,
   `purpose: DIAGNOSTIC_IMAGING`, `authorizationId: 55555555-5555-4555-8555-555555555555`,
   `responsibleClinician: clinician-synthetic-01`, `workQueue: imaging-queue`,
   `durationMinutes: 120`. The response is `201` with an order id, version `1`, a
   snapshot fingerprint and a work-item id.

4. **Verify and accept.** The destination lists work items and finds one in
   `awaiting_local_patient`. It calls
   `POST /api/v1/exchange/work-items/{id}/verify-patient` with
   `localPatientId: 44444444-4444-4444-8444-444444444444`,
   `localMrn: SYN-MRN-9001`, `mrnIssuer: SYN-ISSUER`,
   `attestation: synthetic local verification`; state becomes `ready`. It then
   calls `POST /api/v1/xch03/orders/{id}/accept` with `If-Match: "1"` and a locally
   verified patient, `permittedPurpose: DIAGNOSTIC_IMAGING` and
   `retentionPolicy: SITE_POLICY_V1`.

5. **Move the task channels.** On the work item,
   `POST /api/v1/exchange-tasks/{id}/process` with `If-Match: "1"` moves workflow
   to `processing`. A later `deliver` command records a transmission receipt; it
   does not acknowledge any clinical review. Re-read the task version before
   each further command.

6. **Register and import the result.** `POST /api/v1/xch04/orders` records the
   expectation: `orderId: SYN-ORDER-9001`, the local patient id from step 4,
   `sourceId: partner-imaging`, `accessionId: SYN-ACC-1001`,
   `destinationId: imaging-queue`, `owner: radiologist-synthetic-01`,
   `coverageQueue: imaging-queue`. Then `POST /api/v1/xch04/results` with `kind:
   final`, `effectiveAt: 2026-11-21T09:00:00Z`, `checksum: syn-checksum-0001` and
   no `supersedesVersion` returns `201` with an immutable revision id and an
   `unreviewed` review task. Repeating the identical import returns the same
   revision id and creates no duplicate.

7. **Correct and acknowledge.** A later `kind: corrected` import with
   `supersedesVersion: 1` appends revision `2`, notifies prior recipients and
   reopens review. The clinician acknowledges revision `2` with
   `POST /api/v1/xch04/results/{id}/acknowledge` and `If-Match: "1"` against that
   review task. If a critical finding had been registered, delivery or patient
   release would still leave it awaiting a human acknowledgment.

8. **An error you may see.** If two clients read the same review task at version
   `1` and both acknowledge, the second receives `412` with the error body shown
   in [section 6](#6-what-errors-look-like). It must re-read and resend with the
   new version.

## 10. Entry-point-to-test trace

| Entry point | Owning code | Evidence class |
|---|---|---|
| `POST /api/v1/exchange-tasks/{id}/{command}` | `ExchangeTaskController`, `ExchangeStateMachine` | `ExchangeTaskControllerTest`, `ExchangeStateMachineTest`, `ExchangeTaskIsolationTest` |
| `POST /api/v1/orders`, `GET /api/v1/exchange/work-items` | `OrderController`, `OrderService` | `OrderExchangeIntegrationTest`, `OrderServiceTest` |
| XCH-03 accept/reject/revoke/retrieve | `Xch03Controller`, `Xch03Service` | `Xch03ControllerTest`, `Xch03IntegrityTest` |
| `POST /api/v1/xch04/results`, acknowledge | `ResultController`, `ResultRoutingService` | `ResultControllerHttpTest`, `ResultRoutingServiceTest`, `ResultRoutingIntegrationTest` |
| Critical workflow | `CriticalResultController`, `CriticalResultService` | `CriticalResultControllerHttpTest`, `CriticalResultFlowTest` |
| Auth, tenant, error shape | `TenantContextInterceptor`, `ApiErrorHandler`, `RateLimiter` | `TenantContextInterceptorJwtTest`, `ClaimsTenantResolverTest`, `ApiErrorHandlerTest`, `RateLimitInterceptorTest` |

This guide is engineering documentation for integration developers. It is not
clinical or legal advice; site-approved policies, product configuration and
real-patient acceptance remain separate gates.
