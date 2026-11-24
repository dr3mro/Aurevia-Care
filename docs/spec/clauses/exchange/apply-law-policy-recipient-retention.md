# XCH-03 clause 5 — API test evidence

## Clause

Apply law and policy to recipient retention.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.exchange.clauses.ApplyLawPolicyRecipientRetentionTest`
(path: `apps/api/src/test/java/com/aurevia/exchange/clauses/ApplyLawPolicyRecipientRetentionTest.java`).
All seven tests are active (none are disabled) and use synthetic data on a dedicated PostgreSQL
database (`orevia_xch03_retention_it`) migrated from `db/migrations` with forced row-level
security. They drive the real HTTP entry point with the synthetic-auth profile, the same fixtures
and the same style as the neighbouring `com.aurevia.it.exchangetask.ExchangeResponseExchangeTest`.

- `acceptAppliesApprovedPolicyAndLegalHoldToRetainedCopy` — positive proof. A recipient accepts a
  released order with `LEGAL_HOLD` through `POST /api/v1/xch03/orders/{id}/accept` under an
  `If-Match` version. The response is `200` and `accepted`; `GET /api/v1/xch03/orders/{id}` returns
  one retained copy whose `retentionPolicy` is `LEGAL_HOLD`, `legalHold` is `true` and
  `permittedPurpose` is `RESULT_REVIEW`. A second order accepted with `STANDARD` retains policy
  `STANDARD` and `legalHold` `false`. Direct database checks confirm both rows exist with the
  expected policy and hold flag, so the policy was captured on the durable copy and not only in
  the response.
- `unapprovedRetentionPolicyIsRefusedBeforeAnyRetainedCopy` — negative proof (unapproved policy).
  An `accept` with `retentionPolicy` `UNAPPROVED` is refused with HTTP 422 `INVALID_RETENTION_POLICY`;
  the order stays `requested` at version 1 and the retained-copy ledger is empty.
- `roleWithoutExchangeAcceptCannotRetain` — negative proof (wrong role). A `reception` member has
  active membership but no `EXCHANGE_ACCEPT` permission; `accept` is refused with HTTP 403
  `FORBIDDEN` and nothing is retained.
- `peerTenantCannotSeeOrRetainAnotherTenantsOrder` — negative proof (another tenant). The peer
  organization uses the same local order code but cannot see the order under forced RLS; `GET`
  and `accept` from tenant A are refused with HTTP 404. Tenant B's order is still `requested` with
  an empty retained-copy ledger.
- `staleVersionCannotOverwriteARetainedCopy` — negative proof (stale version). An `If-Match` of 9
  is refused with HTTP 412 `STALE_VERSION`; no retained copy is written and the order version is
  unchanged.
- `missingPermittedPurposeIsRefusedBeforeAnyRetainedCopy` — negative proof (missing input). A blank
  `permittedPurpose` is refused with HTTP 422 `REQUIRED` before any retained copy is written.
- `alreadyAcceptedOrderCannotApplyAnotherRetentionPolicy` — negative proof (forbidden state). A
  second `accept` on an already accepted order is refused with HTTP 409 `INVALID_TRANSITION`; the
  original `STANDARD` retained copy remains the only one.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.exchange.response.ExchangeResponseController` — thin adapter;
  `POST /api/v1/xch03/orders/{id}/accept` parses `If-Match` and delegates, `GET /api/v1/xch03/orders/{id}`
  returns the view with the retained-copy ledger.
- `com.aurevia.exchange.response.ExchangeResponseService` — `accept(String, int, AcceptRequest)`
  validates the policy at the trust boundary before a transaction opens; authorization, version and
  state checks and the retained-copy insert with the `LEGAL_HOLD` flag then run inside one
  tenant-guarded `TransactionTemplate`. `changeResponse` reuses the captured purpose and policy for
  a linked change.
- `com.aurevia.exchange.response.ExchangeResponseInputs` — `retentionPolicy(String)`
  accepts only `STANDARD`, `EXTENDED` and `LEGAL_HOLD` and reports other codes as 422
  `INVALID_RETENTION_POLICY`; `code` and `text` validate the permitted purpose.
- `com.aurevia.exchange.response.ExchangeResponseActionPolicy` — `EXCHANGE_ACCEPT` is
  allowed for recipient roles, not for `reception`, which is the wrong-role test's actor.
- `com.aurevia.exchange.response.ExchangeResponseRepository` — `insertRetained` writes
  the purpose, policy and hold flag; `retained` reads the immutable ledger used by the HTTP view.
- `com.aurevia.exchange.response.ExchangeResponseCommands.AcceptRequest` and
  `RetainedCopy` — the command payload and the immutable retained-copy projection.
- `db/migrations/V21__xch03_order_response.sql` — `xch03_retained_copy` with `retention_policy`,
  `legal_hold`, an append-only guard and ENABLE/FORCE row-level security; the forced-RLS check is
  the peer-tenant proof.

No test is disabled and no production code, existing test, contract or migration was changed by
this task. The missing enforcement half is specified below rather than carried as a disabled test:
`scripts/check-constraints.py` rejects an added Java `@Disabled` at the integrator gate, and a
prior clause task resolved the same conflict by recording the design in this exempt document.

## Gap

The clause is only partly built. The policy code and the legal-hold flag are captured and persisted
at acceptance, but the *application* of the policy over time is absent: there is no expiry deadline,
no retention enforcement job and no behaviour that suspends scheduled expiry while a legal hold is
in force. `docs/security/data-inventory.md` records for `xch03_retained_copy`:
"Retention/legal-hold fields are *declared* here, no enforcement job (see TASK-RETENTION)". A RED
probe during this task queried `expires_at` on `public.xch03_retained_copy` and failed with
`ERROR: column "expires_at" does not exist`, confirming there is no persisted expiry metadata to
enforce against.

Complete test design for a later task (enable as an active test; do not re-add it as a disabled
test):

- Method: `legalHoldSuspendsScheduledRetentionExpiry` in a clause test for the retention worker.
- Setup: seed three accepted orders retained under `STANDARD`, `EXTENDED` and `LEGAL_HOLD`. Inject a
  controllable `java.time.Clock` so the enforcement job runs at `occurredAt + 31 days`; persist an
  explicit `retain_until` deadline on each copy derived from the approved policy. Seed a separate
  legal hold whose deadline was previously scheduled and then suspended.
- Action: run the enforcement job once at the injected time through its normal entry point (a
  scheduled worker tick or an authorized admin route).
- Assertions: the `STANDARD` copy is expired/purged with a durable audit event; the `LEGAL_HOLD`
  copy is unchanged with `legal_hold = true` and no purge event; the `EXTENDED` copy is still
  retained before its 365-day window; a legal hold placed on a previously scheduled copy prevents
  its expiry at the same simulated time. Each assertion must check the database row and the audit
  ledger, not only a response body.
- Files a later task would change: a new Flyway migration adding the retention deadline and hold
  columns with ENABLE/FORCE RLS and an index on the deadline; the repository expiry query and
  update; a new domain service and worker with an injected clock; its controller or scheduled entry
  point plus the OpenAPI entry and regenerated client; the retention row of
  `docs/security/data-inventory.md`; and the new active test.
- Task count: two. One cross-boundary story for the schema, store, service, worker, contract and
  test, then one follow-up for scheduling/operations (job cadence, runbook and monitoring). A real
  legal hold also needs the product owner's approval before live enablement.

This task adds proof only. It did not change production code, an existing test, the OpenAPI
contract or a migration.
