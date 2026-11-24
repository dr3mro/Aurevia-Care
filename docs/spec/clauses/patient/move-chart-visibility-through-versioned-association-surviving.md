# IAM-04 clause 4 — move chart visibility through a versioned association with the surviving record

## Clause

Move chart visibility through a versioned association with the surviving record.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.patient.clauses.MoveChartVisibilityThroughVersionedAssociationSurvivingTest`
(`apps/api/src/test/java/com/aurevia/patient/clauses/MoveChartVisibilityThroughVersionedAssociationSurvivingTest.java`),
an HTTP-level integration test on real PostgreSQL with synthetic staff and patients:

- `consolidationMovesDuplicateChartVisibilityToTheSurvivingRecord` — the second
  reviewer's approval appends version 1 of an `ACTIVE` `chart_association` whose
  `survivor_id` is the survivor and which snapshots the duplicate's original MRN
  and issuer. `public.effective_patient_id(duplicate)` and the review detail's
  `chartVisibilityId` both resolve to the survivor, while both original patient
  records remain in `patient_record`.
- `staleVersionRefusesChartVisibilityMoveAndWritesNoAssociation` — a stale
  `If-Match` version is refused (412), no association or consolidation row is
  appended and visibility stays on the duplicate's own id.
- `alreadyMovedChartCannotBeMovedAgainThroughANewAssociation` — once the first
  review moved the chart, a second review for the same duplicate is refused
  (409), leaving exactly one current association and visibility with the first
  review's survivor.

Production classes exercised through the real HTTP entry point
`POST /api/v1/duplicate-reviews/{reviewId}/consolidate` and
`GET /api/v1/duplicate-reviews/{survivorId}/{duplicateId}`:

- `com.aurevia.patient.consolidation.ConsolidationController` (thin adapter),
- `com.aurevia.patient.consolidation.ConsolidationService`
  (`consolidate` appends the `ACTIVE` association under the pair lock and refuses
  a stale version, an already-moved duplicate or an already-aliased survivor),
- `com.aurevia.patient.consolidation.DuplicateRepository`
  (`insertAssociation` and `effectivePatientId`), and
- the migration `db/migrations/V9__duplicate_review_consolidation.sql`:
  `chart_association` with forced RLS, append-only triggers, database-owned
  version allocation and the `effective_patient_id` / `current_chart_associations`
  fold functions.

Verified locally:
`OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=MoveChartVisibilityThroughVersionedAssociationSurvivingTest test`
— 3 tests, 0 failures, 0 errors.

Discovery found no other production implementation of this clause: the chart
visibility fold lives only in `DuplicateRepository.effectivePatientId` and V9,
and is called from `ConsolidationService` and the `ReviewDetail.chartVisibilityId`
read path. The related `com.aurevia.patient.identity.ChartLink`
records account-to-chart linking (IAM-05), not duplicate-consolidation visibility.

## Gap

None.
