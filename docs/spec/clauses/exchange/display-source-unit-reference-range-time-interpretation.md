# XCH-07 clause 2 — display source unit, reference range, time and interpretation

## Clause

Display source unit, reference range, time and interpretation.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.DisplaySourceUnitReferenceRangeTimeInterpretationTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/DisplaySourceUnitReferenceRangeTimeInterpretationTest.java`),
an HTTP-level integration test on a dedicated real PostgreSQL database with
forced row-level security and synthetic tenants `4111…` / `4222…`. Every method
drives `GET /api/v1/trends/{patientId}?testCode=HBA1C`:

- `sourceUnitRangeTimeAndInterpretationAreShownOnAComparablePoint` — a final,
  unqualified HBA1C result of 3.9 mmol/mol with reference range 4.0–7.0
  mmol/mol, effective time `2026-09-10T00:00:00Z` and interpretation `low` is
  returned as the single comparable point. The response asserts `unit`,
  `referenceLow`, `referenceHigh`, `referenceUnit`, `effectiveAt` and
  `interpretation` verbatim, plus value and source provenance.
- `aNonFinalOrQualifiedResultKeepsItsDisplayFieldsButIsNotPlotted` — a
  preliminary result and a `< 3.0` qualified result never enter `points()`, yet
  both remain in `excluded()` with their unit, effective time, interpretation
  and reference range intact (reasons `NON_FINAL_REVISION` and
  `QUALIFIED_NUMERIC`). This is the negative/forbidden-state case.
- `aWrongRoleIsRefusedTrendRead` — the same trend is readable by `clinician-a`
  (200) but refused to `admin-a` and `reception-a` (403), with the policy's
  `allowsRead` sides asserted at the domain boundary.
- `aPatientInAnotherTenantIsConcealed` — a clinician reading a foreign tenant's
  patient receives 404 rather than another tenant's observations.
- `aComparablePointWithNoApprovedRangeStillShowsUnitTimeAndInterpretation` — a
  result with no approved reference range still shows its source unit,
  effective time and interpretation, with the three range fields returned null
  rather than fabricated.

Production classes exercised through the `TrendController.trend` entry point:
`TrendController`, `TrendService` (`readTrend` authorization, patient existence
and analyzer call), `TrendAnalyzer` (`analyze`, `toPoint`, `classify`), `TrendActionPolicy`,
`TrendPoint`, `TrendObservation`, `ComparabilityRule`, `ExcludedObservation` and
`ExclusionReason`; persistence is `TrendRepository` over the V25
`trend_observation` / `trend_comparability` tables with ENABLE + FORCE RLS.

Corroborating coverage already in the repository (not duplicated here):
`TrendAnalyzerTest.eachTrendPointShowsValueUnitRangeTimeInterpretationAndProvenance`
and the exclusion tests, plus `TrendingTest.trendsOnlyComparableObservationsAndKeepsEveryExclusionVisible`
and `TrendingTest.tenantsStayIsolatedAndRolesAreEnforced`.

## Gap

None. The clause is implemented and proven end to end. Separately, the
`/api/v1/trends` routes are not yet published in `contracts/openapi.yaml` (they
are already listed in `contracts/openapi/missing-routes.txt`); that contract
publication is pre-existing, separately tracked debt and is outside this
test-only story's scope.
