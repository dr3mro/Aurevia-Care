# ARC-04 clause 2 — API test evidence

## Clause

Input contains the validated local requisition association and optional receiving assignee.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.web.clauses.InputContainsValidatedLocalRequisitionAssociationOptionalTest`
(`apps/api/src/test/java/com/aurevia/web/clauses/InputContainsValidatedLocalRequisitionAssociationOptionalTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants
sharing identical local order codes. Every assertion goes through the real
`POST /api/v1/xch03/orders/{id}/accept` route and reads the persisted projection
back through `GET /api/v1/xch03/orders/{id}`. Each test owns an order because the
response ledgers are append-only; the seeded `received` event is the baseline,
so a successful command yields exactly two history events.

- `acceptWithValidatedLocalPatientAndOptionalAssigneeIsStored` — positive proof.
  A tenant-local patient identifier, a receiving assignee, a permitted purpose
  and an approved retention policy move `requested → accepted`, advance version
  1 → 2, round-trip the association and assignee in the response and in the
  persisted order, append an `accept` event with the authenticated actor, and
  retain one recipient copy under the stated purpose and policy.
- `acceptWithMalformedLocalPatientIdIsRefused` — `422 INVALID_INPUT` with
  `localPatientId is malformed` takes the `ExchangeResponseInputs.uuid` parse
  guard before any transaction opens; the order and its ledgers are unchanged.
- `acceptWithoutLocalPatientIdIsRefused` — an omitted association is refused
  `422 INVALID_INPUT` by the same trust-boundary null guard, so it cannot be
  written as a null local patient.
- `validButNonExistentLocalPatientIdIsConcealedAsNotFound` — a syntactically
  valid identifier with no patient in the active tenant, and a real patient of
  another tenant, are each refused `404 NOT_FOUND` with
  `referenced record not found`. This proves the association is validated by a
  tenant-scoped lookup at write time, not merely shaped as a UUID, and that the
  already-issued retained-copy insert rolls back with the failed command.
- `blankOrAbsentAssigneeIsOptionalAndStoredAsNull` — blank and omitted assignee
  values each yield `200` with a JSON `null` assignee in the response and in
  the persisted order, with one retained copy per order.
- `overlongAssigneeIsRefused` — a 129-character assignee is refused
  `422 INVALID_INPUT` with `assignee is too long`, so the database check
  constraint cannot be reached with a value the API should have bounded.
- `nonRecipientRoleIsDenied` — an active `privacy_auditor` with an otherwise
  valid accept body is refused `403 FORBIDDEN` with `missing exchange
  permission`; the role check, not input validation, produces the denial.
- `peerTenantCannotAcceptOtherTenantsOrder` — a genuine `lab_verifier` of the
  peer tenant receives `404 NOT_FOUND` (`order not found`) for the concealed
  order while it can still read its own order, proving forced RLS rather than a
  missing role or subject. The owner's order stays `requested`.
- `staleIfMatchIsRefused` — a real accept advances the row to version 2;
  reusing `If-Match: 1` is refused `412 STALE_VERSION` before any second event
  or retained copy is appended.

Production classes and database objects exercised through the controller and
service (no production file was changed by this proof-only task):

- `com.aurevia.exchange.response.ExchangeResponseController` — thin
  HTTP adapter; `accept` parses `If-Match` and delegates the accept payload.
- `com.aurevia.exchange.response.ExchangeResponseService` — `accept`
  validates the command at the boundary; `run` re-derives authorization, locks
  the order, checks the version, commits the state change, retained copy, event
  and audit together, and translates integrity failures.
- `com.aurevia.exchange.response.ExchangeResponseInputs` and
  `ExchangeResponseCommands.AcceptRequest` — trust-boundary validation and the
  immutable input carrying the local association and optional assignee.
- `com.aurevia.exchange.response.ExchangeResponseRepository` —
  tenant-scoped JDBC write of `local_patient_id` and `assignee` and the
  append-only ledgers read back through `GET /{id}`.
- `com.aurevia.exchange.response.ExchangeResponseActionPolicy` —
  recipient roles fail closed for a read-only auditor.
- `com.aurevia.api.SqlFailureTranslator` — maps the tenant-local
  patient trigger's SQLSTATE `23503` to `404 NOT_FOUND` without revealing the
  collision.
- `db/migrations/V21__xch03_order_response.sql` — `xch03_order_patient_check`
  resolves the local patient inside the active organization, plus FORCE RLS on
  the order and ledger tables.

Verified locally with
`AUREVIA_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=InputContainsValidatedLocalRequisitionAssociationOptionalTest test`
— `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`; the Surefire XML for the
class reports `tests="9"` with no failures, errors or skipped cases. This is a
test-only change: no production line or branch was added, so there is no new
production coverage to map. SpotBugs on compiled classes and
`python3 -I scripts/check-docs.py` also passed; their commands and results are
recorded in the worker notes file.

## Gap

None in the clause. Two pre-existing drifts are recorded, not fixed here because
this story forbids production, contract and migration edits:

1. The specification's sample route `POST /api/v1/orders/{id}/accept` is not
   served; the live route is `POST /api/v1/xch03/orders/{id}/accept`
   (`ExchangeResponseController.java:33,88-92`, `CommandCatalog.java:174`). The
   test targets the live route. Correcting the sample contract or adding an
   alias would change the controller and `contracts/openapi.yaml` — one separate
   ARC-04 contract task.
2. The controller consumes only `If-Match`; it does not consume the
   `Idempotency-Key` required by ARC-04 clause 1. The published accept operation
   in `contracts/openapi.yaml` declares a `RetryKey` header and 200/401/403/404/
   409/422 responses but no `If-Match` and no 412/428 responses. Adding the key
   and publishing the precondition would change the service, controller,
   `contracts/openapi.yaml` and the regenerated staff-web client — one separate
   ARC-04/WEB-05 contract and implementation task.

Both drifts were present before this story; neither is introduced by this test.
