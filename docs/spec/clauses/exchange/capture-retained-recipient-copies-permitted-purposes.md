# XCH-03 clause 3 — capture retained recipient copies and permitted purposes

## Clause

Capture retained recipient copies and permitted purposes.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.exchange.clauses.CaptureRetainedRecipientCopiesPermittedPurposesTest`
(`apps/api/src/test/java/com/aurevia/exchange/clauses/CaptureRetainedRecipientCopiesPermittedPurposesTest.java`).
It is a synthetic, database-free clause test in the `clauses` style of
`docs/spec/clauses/scheduling/transfer-handoff-requires-active-assignee.md`:
mocked `ExchangeResponseRepository` and `AuditAppender`, a `TransactionTemplate`
mock that runs the real guarded callback, `TenantContext.set`/`clear`, synthetic
UUIDs and a fixed instant. Every case enters through `ExchangeResponseService.accept`,
the command behind `POST /api/v1/xch03/orders/{id}/accept`, or through
`ExchangeResponseService.changeResponse`, the command behind the change response.
The retained-copy ledger read back through `ExchangeResponseService.get` is built
from the actual arguments of the `insertRetained` write, so the assertions prove
the captured values rather than an independent fixture.

- `acceptCapturesPurposePolicyChecksumAndImmutableRetainedCopy` — accepting a
  released revision writes exactly one retained copy carrying the verified
  patient, `RESULT_REVIEW` purpose, `STANDARD` policy, source checksum, active
  organization and authenticated actor; the copy is read back through `get`, its
  id is an opaque identifier, and the returned ledger cannot be mutated or
  changed by mutating the source list (rule met).
- `acceptUnderLegalHoldSetsTheHoldFlag` — a `LEGAL_HOLD` policy stamps
  `legalHold=true` on the retained copy; the standard-policy case proves the
  false side of the same decision.
- `respondingToAPromotedChangeRetainsTheNewRevisionUnderTheCapturedPurpose` —
  accepting a linked change retains the promoted revision under the purpose and
  policy captured at acceptance, with the new revision's checksum, and persists
  the promoted revision together with the accepted state.
- `changeResponseAppliesTheCapturedLegalHoldToTheNewRetainedCopy` — a promoted
  revision keeps the original legal hold, so expiry is not restarted on a change.
- `changeResponseWithoutAPublishedRevisionWritesNoRetainedCopy` — a change with
  no released revision is concealed as 404 and no retained row is written.
- `changeResponseRefusesToPromoteAChangeWithoutACapturedPurpose` and
  `changeResponseRefusesToPromoteAChangeWithoutACapturedRetentionPolicy` — both
  sides of the missing-purpose-or-policy guard refuse with 409 because a
  retained copy has non-null purpose and policy columns; no row is written.
- `rejectingALinkedChangeLeavesTheAcceptedRevisionCurrentAndWritesNoCopy` —
  rejecting a change leaves revision one current and writes no retained copy.
- `acceptIsRefusedForARoleWithoutExchangePermission` — a read-only
  `privacy_auditor` is refused with 403 after membership succeeds; the denial is
  audited and no copy is written (negative: wrong role).
- `acceptIsRefusedOnStaleVersionWrongStateAndInvalidInputWithoutRetaining` — a
  stale version (412), an already accepted order (409), a blank purpose (422)
  and an unapproved retention policy (422 `INVALID_RETENTION_POLICY`) all refuse
  without a retained write (negative: staleness, forbidden state and input).

Production classes and files surveyed and exercised by the entry points:

- `com.aurevia.exchange.response.ExchangeResponseService.accept` —
  validates the association, permitted purpose and approved retention policy,
  then inserts one immutable retained copy in the same transaction as the state
  change and response event.
- `com.aurevia.exchange.response.ExchangeResponseService.changeResponse`
  — retains a promoted revision under the purpose and policy captured at
  acceptance, carries a legal hold forward, and fails closed when either value
  or the released revision is missing.
- `com.aurevia.exchange.response.ExchangeResponseService.get` —
  exposes the append-only retained-copy ledger in `OrderView`.
- `com.aurevia.exchange.response.ExchangeResponseInputs` —
  `code` and `retentionPolicy` validators and the approved `RETENTION_POLICIES`
  set (`STANDARD`, `EXTENDED`, `LEGAL_HOLD`).
- `com.aurevia.exchange.response.ExchangeResponseActionPolicy` —
  recipient actions are limited to active recipient roles; `privacy_auditor` is
  read-only.
- `com.aurevia.exchange.response.ExchangeResponseCommands` —
  `AcceptRequest`, `ChangeResponseRequest`, `RetainedCopy`, `Revision` and the
  defensively copied `OrderView`.
- `com.aurevia.exchange.response.ExchangeResponseRepository` —
  `insertRetained` writes the copy and `retained` reads its ledger, both under
  the active tenant context.
- `com.aurevia.exchange.response.ExchangeResponseController` —
  `POST /api/v1/xch03/orders/{id}/accept` and the change-response route are the
  HTTP entry points; the controller injects the service and parses `If-Match`.
- `db/migrations/V21__xch03_order_response.sql` — creates
  `public.xch03_retained_copy` with tenant keys, purpose and policy checks, a
  sha-256 checksum check, a uniqueness constraint on the retained revision, an
  index on `(organization_id, order_id, revision)`, ENABLE and FORCE ROW LEVEL
  SECURITY with a fail-closed tenant policy, and append-only grants (no UPDATE,
  DELETE or TRUNCATE for the runtime role). Tenant isolation itself is a
  database invariant proved by the integration suite, not simulated by a mock.
- Contract survey: the accept and change-response routes are listed in
  `contracts/openapi.yaml` as `POST /api/v1/xch03/orders/{}/accept`
  and `POST /api/v1/xch03/orders/{}/change-response`. They are baselined there
  and are not described as operations in `contracts/openapi.yaml`. That is
  separate contract-documentation debt and does not affect whether the behavior
  is implemented and proven.

Broader coverage already present (not repeated here): the service-level branches
live in `it/exchangetask/ExchangeResponseServiceTest`, and the HTTP-plus-PostgreSQL
path, including the retained-copy purpose and legal-hold columns and the
no-copy-on-rejected-change rule, lives in `it/exchangetask/ExchangeResponseExchangeTest`.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=CaptureRetainedRecipientCopiesPermittedPurposesTest test`
— `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
