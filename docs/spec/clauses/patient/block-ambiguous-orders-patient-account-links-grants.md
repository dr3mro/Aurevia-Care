# IAM-04 clause 7 — API test evidence

## Clause

Block ambiguous orders, patient-account links or grants until resolved.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.patient.clauses.BlockAmbiguousOrdersPatientAccountLinksGrantsTest`
(path: `apps/api/src/test/java/com/aurevia/patient/clauses/BlockAmbiguousOrdersPatientAccountLinksGrantsTest.java`).
All five tests are active (none disabled) and use only synthetic patients, staff and dependencies against a
migrated PostgreSQL database through the real HTTP entry point `POST /api/v1/duplicate-reviews/{reviewId}/consolidate`.

- `consolidationIsBlockedWhileAnAmbiguousOrderIsUnresolved` — negative proof. An ambiguous, unresolved
  `ORDER` dependency yields HTTP 409 `UNRESOLVED_DEPENDENCY`, appends no chart association or consolidation
  event, and records a `CONSOLIDATE`/`BLOCKED` audit event.
- `consolidationIsBlockedWhileAnAmbiguousPatientAccountLinkIsUnresolved` — negative proof. The same refusal
  and zero-write guarantee for an ambiguous `PATIENT_LINK` dependency.
- `consolidationIsBlockedWhileAnAmbiguousGrantIsUnresolved` — negative proof. The same refusal and zero-write
  guarantee for an ambiguous `GRANT` dependency.
- `consolidationProceedsOnlyAfterEveryAmbiguousDependencyIsResolved` — positive proof of "until resolved".
  Three unresolved blockers refuse the command; appending a later resolved row for each (the append-only
  resolution model) releases the block and the same approval commits, moving chart visibility to the survivor.
- `nonAmbiguousAndResolvedDependenciesDoNotBlockConsolidation` — positive proof that only *ambiguous* and
  *still-unresolved* dependencies block: a known order and an already-resolved grant allow consolidation and
  record no `BLOCKED` audit outcome.

Production classes exercised (unchanged by this task):

- `com.aurevia.patient.consolidation.ConsolidationService` — `consolidate` refuses with
  `409 UNRESOLVED_DEPENDENCY` ("ambiguous orders, links or grants must be resolved first") when
  `repository.hasUnresolvedBlockers(duplicate.id()) > 0`, after taking the pair lock and before any append.
- `com.aurevia.patient.consolidation.DuplicateRepository` — `hasUnresolvedBlockers` folds the
  append-only `consolidation_dependency` history to the latest row per `(patient, kind, reference)` and counts
  rows that are `ambiguous AND NOT resolved`; `dependencies` feeds the same fold to the review detail.
- `com.aurevia.patient.consolidation.ConsolidationController` — thin HTTP adapter binding the
  review identifier, `If-Match` precondition and confirmation body to the service.
- `com.aurevia.patient.consolidation.ReviewDetail` — the `Dependency` record documents the three
  blocking kinds `ORDER`, `PATIENT_LINK` and `GRANT` and their `ambiguous`/`resolved` state.
- `db/migrations/V9__duplicate_review_consolidation.sql` — `public.consolidation_dependency` with a
  `CHECK (kind IN ('ORDER','PATIENT_LINK','GRANT'))`, forced RLS and append-only triggers; the review,
  association and consolidation tables are written in the same tenant-guarded transaction.

Neighbouring implementations observed in the source survey (not changed by this task): the identity-proofing
package `com.aurevia.patient.identity` owns account-link lifecycle and `IdentityActionPolicy`,
the exchange packages own order creation, and the authorization/configuration packages own grants; none of
those creation paths is changed here. The existing `ConsolidationIsolationTest` and `ConsolidationServiceTest`
also cover this rule; this clause-specific class isolates only clause 7 with explicit per-kind and
resolved-state assertions.

## Gap

None.

The clause-7 rule is built and proven by active HTTP-level tests. This task added proof only; it did not
change production code, an existing test, the OpenAPI contract or a migration.
