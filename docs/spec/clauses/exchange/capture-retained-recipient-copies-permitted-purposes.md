# XCH-03 clause 3 — capture retained recipient copies and permitted purposes

## Clause

Capture retained recipient copies and permitted purposes.

## Status

Proven by tests

## Evidence

Active HTTP-level proof (`synthetic-auth` profile, a fresh PostgreSQL database migrated from
`db/migrations`, synthetic fixtures only):

- Test class: `com.aurevia.smartclinic.exchange.clauses.CaptureRetainedRecipientCopiesPermittedPurposesTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/CaptureRetainedRecipientCopiesPermittedPurposesTest.java`).
- `acceptCapturesRetainedCopyWithPermittedPurposeAndRetentionPolicy` — positive proof. Through
  `POST /api/v1/xch03/orders/{id}/accept`, the order moves to `accepted` at version 2 and
  `GET /api/v1/xch03/orders/{id}` exposes `/retained/0` with the released revision and checksum,
  the submitted `permittedPurpose`, the approved `retentionPolicy`, the `legalHold` derived from
  that policy, and the authenticated recipient's opaque `actorId`. A direct, RLS-bounded count of
  `xch03_retained_copy` confirms one durable row.
- `acceptedChangeRetainsNewRevisionUnderOriginalPurpose` — positive proof. Accept, propose a linked
  change and accept that change through `POST /{id}/change-response`: a second retained copy is
  stored for revision 2 with the new revision's checksum while keeping the purpose and policy
  captured at the original acceptance. The revision ledger link (`previousRevision = 1`) is intact.
- `refusesUnapprovedRetentionPolicyWithoutCapturingACopy` — negative proof. An unapproved policy
  is refused with HTTP 422 `INVALID_RETENTION_POLICY` and no retained row is written.
- `refusesMalformedPermittedPurposeWithoutCapturingACopy` — negative proof. Invalid characters in
  the purpose are refused with HTTP 422 `INVALID_INPUT` and no retained row is written.
- `refusesAcceptByNonRecipientRolesWithoutCapturingACopy` — negative proof. `reception` and
  `privacy_auditor` are refused with HTTP 403 `FORBIDDEN` and no retained row is written.
- `concealsRetainedLedgerFromAnotherTenant` — negative proof. A peer-tenant subject carrying
  `X-Active-Organization` for the other organization receives HTTP 404 for `GET /{id}`, while the
  owner's same request shows `/retained/0`; forced row level security is the separator.
- `refusesStaleAcceptReplayWithoutCapturingASecondCopy` — negative proof. Replaying accept with
  the already-consumed `If-Match` version is refused with HTTP 412 `STALE_VERSION` and no second
  retained row is written.
- `rejectAndClarifyNeverCaptureARetainedCopy` — negative proof. Reject and clarify reach their
  workflow states without capturing any retained copy.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.exchange.response.ExchangeResponseController` — thin adapter publishing
  `POST /api/v1/xch03/orders/{id}/accept`, `POST /{id}/propose-change`,
  `POST /{id}/change-response` and `GET /{id}`.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseService` — `accept` validates the
  association, purpose and policy and calls `insertRetained` inside the tenant-guarded transaction;
  `changeResponse` retains a new linked revision under the original purpose; `get` returns the
  retained ledger in the order view.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseInputs` — boundary validation:
  `code` refuses a malformed purpose with `INVALID_INPUT` and `retentionPolicy` refuses an
  unapproved policy with `INVALID_RETENTION_POLICY`.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseActionPolicy` — `EXCHANGE_ACCEPT` is a
  recipient-only action, so sender and read-only roles cannot capture a copy.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseRepository` — `insertRetained` writes
  the immutable `xch03_retained_copy` row; `retained` reads the tenant-bounded ledger.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseCommands` — the immutable
  `RetainedCopy` and `OrderView` records carried by the response.
- `db/migrations/V21__xch03_order_response.sql` — the `xch03_retained_copy` table with NOT NULL
  purpose and approved-policy checks, ENABLE and FORCE ROW LEVEL SECURITY, a tenant isolation
  policy, a transaction-bound actor trigger and append-only update/delete/truncate triggers.

## Gap

None for this clause: capture of retained recipient copies and their permitted purposes is built
and proven by the active tests above.

Adjacent context, not part of this clause's proof: `POST/GET /api/v1/xch03/orders/*` are absent from
`contracts/openapi.yaml` (they are listed in `contracts/openapi/missing-routes.txt`), so generated
clients cannot yet call them. This is tracked separately and is listed here as context, not as a
new ask of this evidence task. Separately, enforcement of the recorded retention policy by a
scheduled expiry job belongs to clause 5 ("Apply law and policy to recipient retention") and is
out of scope here.

This evidence task added only a test class and this note. It changed no production code, no
existing test, no migration and no contract.
