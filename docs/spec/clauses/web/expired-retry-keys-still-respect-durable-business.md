# ARC-03 clause 8 — API test evidence and gap analysis

## Clause

Expired retry keys must still respect durable business uniqueness for external clinical actions.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.web.clauses.ExpiredRetryKeysStillRespectDurableBusinessTest`
(path: `apps/api/src/test/java/com/aurevia/web/clauses/ExpiredRetryKeysStillRespectDurableBusinessTest.java`).
All three tests are active (none skipped) and use synthetic identifiers, keys and digests
on a dedicated PostgreSQL database migrated from `db/migrations` with forced row-level
security. They drive the real HTTP entry point with the synthetic-auth profile, reusing
the fixture style of `com.aurevia.events.clauses.CommitStateAuditOutboxRecordAtomicallyApplicableTest`
and `com.aurevia.pharmacyflow.substitution.SubstitutionPersistenceIntegrationTest`.
Direct JDBC assertions bind `app.current_org` under the same tenant.

The proof surface is the pharmacy substitution decision, an external clinical action that
carries both a retry key and a durable business identity. The in-memory idempotency cache
(`IdempotencyLedger.java:14-21`) is process-local by design: its `expire` removes only a
cache entry, and `IdempotencyLedgerTest.java:80-87` shows a completed key re-claiming
`PROCEED` afterwards. The clause is therefore tested by presenting a fresh, unseen key
after the first action has committed and its claim has been evicted in the test database.

- `expiredKeyCannotCreateASecondExternalClinicalAction` — positive proof. A pending
  proposal is created and approved once with key `claim-<uuid>` through
  `POST /api/v1/pharmacy/substitutions/{proposalId}/approve`; the response is `200 OK` and
  a direct JDBC count finds exactly one `pharmacy_substitution_revision` row for that
  proposal. The test then deletes only that proposal's `approve` claim row from
  `pharmacy_substitution_decision` (temporarily disabling the append-only trigger in the
  dedicated test database and restoring it in a `finally` block), leaving the proposal and
  its signed revision untouched. A second approval uses a new key `fresh-<uuid>` and an
  `If-Match` of `2` matching the committed revision, so the only remaining guard is the
  durable business identity. The response is `409 CLOSED_PROPOSAL`; the revision count is
  still one; and no claim row exists for the fresh key. The refusal therefore cannot come
  from a lookup of the original retry key.

- `expiredRetryKeyDoesNotBypassRoleOrTenant` — negative proof (wrong role and peer tenant),
  each run against a fresh pending proposal. The pharmacy proposer holds only the
  `pharmacy_staff` role, so its approval is refused with `403 FORBIDDEN`. A prescriber in
  the peer tenant, which has no projection of the proposal under forced RLS, is refused
  with `404 NOT_FOUND`. Both attempts leave zero `pharmacy_substitution_revision` rows for
  their proposals.

- `expiredRetryKeyDoesNotBypassMissingOrDifferentPayload` — negative proof (missing and
  malformed input). An absent `Idempotency-Key` is refused with `428 IDEMPOTENCY_KEY_REQUIRED`
  from `IdempotencyKey.parse`; a present but malformed key (`bad key!`) is refused with `422
  INVALID_IDEMPOTENCY_KEY`. Neither attempt writes a revision. The malformed-key case is
  the applicable "different payload under an unusable key" check on this route: the
  substitution decision binds a proposal identity rather than a digest, so the digest
  conflict of the delivery route does not apply here.

Production classes and objects exercised through the normal HTTP entry point:

- `com.aurevia.pharmacyflow.substitution.SubstitutionController` — thin
  adapter binding `Idempotency-Key` and `If-Match` on the approve command.
- `com.aurevia.pharmacyflow.substitution.SubstitutionService` — `decide` and
  `decideInside`: trust-boundary validation, role check, durable claim, version
  precondition, the `CLOSED_PROPOSAL` business guard, signed revision insertion and the
  one-time proposal close.
- `com.aurevia.pharmacyflow.substitution.JdbcSubstitutionStore` — `claim`,
  `replay`, `findRevision`, `insertRevision` and `closeProposal` behind `SubstitutionStore`.
- `com.aurevia.pharmacyflow.substitution.SubstitutionRules`,
  `SubstitutionInputs` and `SubstitutionApplication` — role matrix, input validation and
  entitlement recheck.
- `com.aurevia.api.contract.IdempotencyKey`, `com.aurevia.api.IfMatch`
  and `com.aurevia.api.ApiErrorHandler` — key and precondition parsing and the
  asserted `403`/`404`/`409`/`422`/`428` machine-readable bodies.
- `com.aurevia.web.TenantContextInterceptor` — binds the synthetic tenant and
  actor for each request.
- `db/migrations/V94__durable_pharmacy_substitution.sql` — the
  `UNIQUE (organization_id, proposal_id)` constraint on `pharmacy_substitution_revision`,
  the `(organization_id, command, idempotency_key)` decision claim, the append-only
  immutability triggers and forced RLS on every table.

Read-only evidence reviewed for the gap: `IdempotencyLedger.java:14-21,109-115`,
`IdempotencyLedgerTest.java:79-87,113-120`, `events/DurableEventService.java:98-124`,
`api/contract/IdempotencyKey.java`, and migrations `V55__durable_event_outbox.sql:53`,
`V19__exchange_orders.sql:122`, `V22__result_routing.sql:33,68`, `V44__prescription_transmission_transmission.sql:76`,
`V45__finance.sql`, `V91__staff_messaging.sql` and `V95__durable_staff_mail.sql`.

This proof is scoped to the pharmacy substitution action. It does not claim that every
external clinical action enforces the rule, and it does not claim a tenant-isolation or
PHI-protection proof beyond the refused-command assertions above.

## Gap

The rule is proven for pharmacy substitution decisions, but not for the delivery and
event-externalisation surface. `DurableEventService.publish` deduplicates only through
`store.byIdempotencyKey(organizationId, sender, eventType, key)` (`DurableEventService.java:109`)
and `V55__durable_event_outbox.sql:53` makes `(organization_id, sender, event_type,
idempotency_key)` unique — both keyed on the retry key, not on a business identity. A
fresh key carrying the same `aggregateId`, `sender`, `eventType` and `payloadDigest`
therefore creates a second `delivery_outbox` row. The in-memory `IdempotencyLedger` has no
production caller, so no other module currently couples retry-key expiry to a durable
business constraint.

Closing the gap needs **one** cross-boundary implementation task:

1. Add a forward Flyway migration under `db/migrations` (never edit applied V55) with an
   organisation-scoped business-identity unique key on `delivery_outbox`, for example
   `UNIQUE (organization_id, aggregate_id, sender, event_type, payload_digest)`, keeping
   forced RLS and the tenant policy on that table. The exact business key must be
   chosen against the delivery contract's re-publish semantics so a legitimate
   re-publication of an identical payload at a new aggregate version is not rejected;
   the key above is an illustrative starting point, not a final contract decision.
2. Extend `DurableEventService.publish` with a business-identity lookup that returns the
   original event on an identical retry and refuses a conflicting second action with a
   concrete status (409 `DUPLICATE_EXTERNAL_ACTION`) without writing a second outbox row,
   with deterministic unit tests for the empty, replay and conflict sides.
3. Enable an active HTTP-over-PostgreSQL test through `POST /api/v1/delivery/events` that
   evicts the first claim, re-applies with a fresh key and the same business identity, and
   asserts one outbox row, with 100% JaCoCo coverage of the new branches.

A later survey may decide whether the schema-level business identities in
`V19__exchange_orders.sql:122`, `V22__result_routing.sql:33,68` and
`V44__prescription_transmission_transmission.sql:76` need the same retry-key coupling; those
routes do not currently bind an expiring retry key, so they are a separate scope.
