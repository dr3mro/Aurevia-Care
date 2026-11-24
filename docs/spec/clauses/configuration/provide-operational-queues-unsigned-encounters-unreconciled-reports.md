# ADM-02 clause 1 — Provide operational queues

## Clause

Provide operational queues for unsigned encounters, unreconciled reports, unacknowledged critical findings, rejected exchanges, document failures, missing coverage and revoked accounts.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.configuration.clauses.ProvideOperationalQueuesUnsignedEncountersUnreconciledReportsTest`
(`apps/api/src/test/java/com/aurevia/configuration/clauses/ProvideOperationalQueuesUnsignedEncountersUnreconciledReportsTest.java`),
a pure unit suite over synthetic two-organization fixtures with no Spring context, database
or HTTP server. It proves only the two projections that exist today:

- `anUnsignedEncounterAppearsAsAnOpenOperationalQueueItem` (rule met) — an encounter with
  an editable draft and no signature becomes a single `OPEN` item tagged
  `unsigned_encounters`; three out-of-order draft revisions prove the oldest creation
  instant is used and that owner, reason and action history start empty.
- `aSignedEncounterIsAbsentFromTheUnsignedEncounterQueue` (negative) — a positive control
  first shows the item exists, then a signature-bearing revision removes it.
- `anotherTenantsUnsignedEncounterIsNotReturned` (negative) — facts of another tenant are
  filtered, including when both tenants use the same local encounter id, and each returned
  item carries its own organization id.
- `aRosterWithNoOwnerAppearsAsAnOpenMissingCoverageItem` (rule met) — a roster without a
  named owner becomes an `OPEN` item tagged `missing_coverage`.
- `aFullyStaffedRosterIsAbsentFromTheMissingCoverageQueue` (negative) — a roster with both
  owner and backup is absent, while a mixed-list control proves the query still returns a
  genuine gap.

The unbuilt part of the clause — the seven-queue HTTP aggregate, its durable store and the
operator/audit command boundary — cannot be evidenced by a runnable test today and is
recorded under Gap instead.

Production classes and files exercised by these tests:

- `com.aurevia.clinical.queues.UnsignedEncounterQueue` — derives
  `unsigned_encounters` from note revisions (CLN-05).
- `com.aurevia.config.rosters.MissingCoverageQueue` — derives
  `missing_coverage` from coverage rosters (XCH-05, ADM-01).
- `com.aurevia.config.queues.OperationalQueueItem`,
  `OperationalWorkState` and `QueueAction` — the shared work-item model; only `assign`
  and `resolve` verbs exist, with no `retry`, `escalate` or audit-id field.
- `com.aurevia.clinical.queues.EncounterNoteFact`,
  `com.aurevia.config.rosters.CoverageRosterFact`,
  `com.aurevia.clinical.notes.ClinicalNoteRevision` and `NoteState` — the
  immutable, tenant-scoped projection inputs.
- `infra/ops-queues/ops_queues.py` and `docs/operations/ops-queues/queue-catalog.md` — the
  enforced seven-queue vocabulary, operator roles, action/state rules and audit/export
  envelope. This is an offline evaluator of an evidence artifact, not a running service.

Discovery finding: there is **no** operational-queues HTTP controller, Jdbc store, Flyway
migration or OpenAPI entry under `apps/api/src/main`, `db/migrations` or `contracts`.
`com.aurevia.web.ConfigurationController` serves `/api/v1/configuration`
draft, approval and history operations and is unrelated to ADM-02 queues. Adjacent
controllers in other domains (exchange work items, delivery dead letters, overdue
follow-ups) do not implement the seven catalog queues.

Verified locally:

```
OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security \
  -Dtest=ProvideOperationalQueuesUnsignedEncountersUnreconciledReportsTest test
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

All five tests are active and assert outcomes; none is skipped.

## Gap

Five of the seven queues — `unreconciled_reports`, `unacknowledged_critical_findings`,
`rejected_exchanges`, `document_failures` and `revoked_accounts` — have no production
projection. They appear only in the offline evaluator, its synthetic fixtures and the
queue catalog. The two that do exist are pure, in-memory functions over caller-supplied
facts: they have no HTTP entry point, no `Jdbc*` store behind a store interface, no
tenant-owned table with forced row-level security, no OpenAPI entry or regenerated client,
and their data cannot survive a restart. No operator allowlist, next-action revocation,
non-empty reason check, `audit_id` corroboration, `retry`/`escalate` action or export
envelope is enforced at any command boundary; the `audit_id` field is absent from
`QueueAction` and `OperationalQueueItem`. The encounter-wide signing suppression is also a
declared assumption that needs a site ruling before live enablement.

Closing the Gap needs at least three separate, owner-approved tasks:

1. **Durable read aggregate.** Add a tenant-owned Flyway migration with RLS and FORCE RLS,
   `Jdbc*` stores behind store interfaces (with `InMemory*` fakes only under `src/test`),
   a service injected into a thin controller exposing all seven queues with role and
   tenant guards, an HTTP-level integration test through that controller, and the OpenAPI
   entry with a regenerated client.
2. **Five missing projections.** Implement the remaining source-fact projections with
   their own tenant-scoped inputs, stores and tests, following the two existing ones.
3. **Action, audit and export boundary.** Add the three-role operator allowlist
   (`tenant_admin`, `privacy_auditor`, `operator`), revocation effective on the next
   action, required reasons, a corroborating OPS-02 `audit_id`, `retry`/`escalate` actions
   and the explicit-scope bounded export envelope; then add an HTTP-level integration test
   with an injected port and a seeded audit event. Site decisions on role mapping, retry scope and
   signing suppression run in parallel with these tasks.

No production code, migration, contract or existing test was changed by this story.

History of the earlier follow-up: BUILD-17 added the two working projections and its
notes proposed reserving V91 for the wiring story; a later migration repair revised that
to V93. Both versions are now consumed (V91 staff messaging, V93 membership facility
scope), as are V94 and V95; V96 is reserved by another open task. The next physically
free version is V97 (checked with `python3 -I scripts/check-migrations.py --require-baseline`,
exit 0), but the follow-up was never approved and **no version is reserved by this story**.
A future task must obtain its own owner-approved reserved version.
