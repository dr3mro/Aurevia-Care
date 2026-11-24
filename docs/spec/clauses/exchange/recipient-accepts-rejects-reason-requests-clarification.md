# XCH-03 clause 1 — recipient response

## Clause

The recipient accepts, rejects with reason, or requests clarification.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.exchange.clauses.RecipientAcceptsRejectsReasonRequestsClarificationTest`
(`apps/api/src/test/java/com/aurevia/exchange/clauses/RecipientAcceptsRejectsReasonRequestsClarificationTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants
sharing the same local order codes. Each test owns a distinct order because the
response ledgers are append-only; the seeded `received` event is the baseline,
so a successful command yields exactly two history events.

- `recipientAcceptsAndRetainsVerifiedAssociation` — `POST /api/v1/xch03/orders/{id}/accept`
  with a verified local patient, assignee, permitted purpose and retention policy
  moves `requested → accepted`, advances version 1 → 2, appends an `accept` event
  with the authenticated actor, and retains one recipient copy whose purpose,
  policy, legal-hold flag and actor are read back through `GET /{id}`.
- `recipientRejectsWithRecordedReason` — `POST /{id}/reject` with a reason moves
  `requested → rejected` and appends a `reject` event carrying that reason; no
  retained copy is created.
- `recipientRequestsClarificationWithRecordedQuestion` — `POST /{id}/clarify`
  with a question moves `requested → clarification_requested`; the question is
  persisted on the order projection and in the append-only event's `reason`
  field (the event record has no separate question property).
- `rejectWithoutReasonIsRefused` and `clarifyWithoutQuestionIsRefused` — an
  absent reason or question is refused at the trust boundary with `422 REQUIRED`
  and leaves the order `requested` with no additional event.
- `nonRecipientRoleCannotRespond` — the seeded `auditor-1111` has an active
  membership and may read the order, but its `privacy_auditor` role is not a
  recipient role: the reject is denied `403 FORBIDDEN` by the action policy and
  appends no event.
- `peerTenantCannotRespondToAnotherTenantsOrder` — a peer tenant's recipient
  receives `404 NOT_FOUND` on the other tenant's identical local order, proving
  forced RLS conceals the row; the owner's order stays `requested`.
- `staleIfMatchIsRefused` — a real clarify advances the version to 2, and
  reusing `If-Match: "1"` for a reject is refused `412 STALE_VERSION` before
  any state or history change.
- `acceptAfterRejectionIsRefused` — accepting a genuinely rejected order with a
  current version is refused `409 INVALID_TRANSITION`; the order stays
  `rejected` with no retained copy.

Production classes exercised through the controllers
`POST /api/v1/xch03/orders/{id}/{accept,reject,clarify}` and `GET /api/v1/xch03/orders/{id}`:

- `com.aurevia.exchange.response.ExchangeResponseController` — thin
  HTTP adapter; parses `If-Match` and the accept/reason/clarify payloads.
- `com.aurevia.exchange.response.ExchangeResponseService` — `accept`,
  `reject`, `clarify`, `run` and `authorize`: re-derives roles and membership,
  locks the order, checks the version, validates the transition, and commits the
  state change, retained copy, event and audit together.
- `com.aurevia.exchange.response.ExchangeResponseActionPolicy` —
  `RECIPIENT_ROLES` / `RECIPIENT_ACTIONS`, failing closed for read-only roles.
- `com.aurevia.exchange.response.ExchangeState` — the workflow
  states and terminal branches.
- `com.aurevia.exchange.response.ExchangeResponseInputs` and
  `ExchangeResponseCommands` — trust-boundary validation and immutable commands.
- `com.aurevia.exchange.response.ExchangeResponseRepository` — the
  tenant-scoped, append-only persistence behind the HTTP reads.
- `db/migrations/V21__xch03_order_response.sql` — the order/revision/event/
  retained-copy tables, forced tenant RLS, immutable-ledger triggers and the
  actor-from-context trigger.

Verified locally with `OREVIA_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=RecipientAcceptsRejectsReasonRequestsClarificationTest test`
— `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`; the Surefire XML for the
class confirms 9 tests and 0 skipped. Static analysis with
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check`
(compiled classes, no bare `spotbugs:check`) exited 0 with no findings.
`python3 -I scripts/check-docs.py` and `python3 -I scripts/check-names.py`
both exited 0. No production file was changed by this test-only story, so there
is no new production branch to map to a test.

## Gap

None in the clause. The six XCH-03 response routes relevant to this clause are
absent from the published `contracts/openapi.yaml` and are listed in
`contracts/openapi.yaml`
(lines 119-121: `GET /api/v1/xch03/orders/{}`, `/history`, `/retrieve`; lines
342-349: `POST /{}/accept`, `/reject`, `/clarify`, plus the other XCH-03 commands).
This test exercises four of them (`GET /{id}` and the three POST commands);
`/history` and `/retrieve` belong to other clauses.
Publishing them needs a separate contract task that adds a tag, the
`Idempotency-Key` header on every POST, a route-parity entry, and the regenerated
client; it would change `contracts/openapi.yaml` and the generated client types.
That publication is tracked debt outside this test-only scope and was not edited
here.
