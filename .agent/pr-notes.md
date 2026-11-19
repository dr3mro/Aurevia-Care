# S-G2-07 — Trend only comparable observations with units, ranges, time and qualifiers visible

Primary requirement: **XCH-07**. Supporting: **ADM-01, ARC-01, ARC-02, ARC-03, OPS-02, IAM-01**.
Specification: `SMARTCLINIC_V1_SPEC.md` §4 XCH-07, §7 ARC-01..03, §8 OPS-02.
Story scope: `apps/api/src/main/java/com/aurevia/smartclinic/g2/s_g2_07`, `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g2_07`. Reserved migration: **V25** only.

## Acceptance mapping (XCH-07)

| Requirement text | Implementation | Synthetic test evidence |
|---|---|---|
| Trend numeric observations only when test identity, units and clinically relevant comparability are established | `TrendAnalyzer.classify` rejects non-matching test, non-matching comparability group, non-final revision, qualified numeric, qualitative and non-canonical unit; `ComparabilityRule` + effective-dated `current_trend_comparability` fold (V25) define the rule; `TrendActionPolicy`/`TrendService` enforce role separation | `TrendAnalyzerTest` (17), `TrendServiceTest` (17), `TrendControllerTest` (3), `TrendRepositoryTest` (8), `TrendMigrationContractTest` (1); `TrendingTest` (6 integration, integrator) |
| Display source unit, reference range, time and interpretation | `TrendPoint` carries `unit`, `referenceLow/High/Unit`, `effectiveAt`, `interpretation`, `sourceSystem/Version`; `Observation` validates reference-range unit equals source unit and inverted ranges are rejected | `TrendAnalyzerTest.eachTrendPointShowsValueUnitRangeTimeInterpretationAndProvenance`, `observationValidationRejectsMissingIdentityValueTimeAndBadRanges`; `TrendRepositoryTest.observationMapperReadsEveryDisplayedColumn` |
| Keep less-than / greater-than, qualitative and incompatible-unit results visible, never as comparable measurements | `ExcludedObservation` retains the whole `Observation` unmodified with a closed `ExclusionReason`; `TrendView.points() + excluded()` keeps every observation observable (nothing silently dropped) | `TrendAnalyzerTest.qualifiedNumericResultsAreVisibleButNotTrended`, `qualitativeResultsAreVisibleButNotTrended`, `incompatibleUnitsAreVisibleButNotTrended`, `exclusionsAreAlsoOrderedByEffectiveTimeThenId`, `viewCopiesItsListsDefensively`; `TrendingTest` asserts 3 points + 6 exclusions from 9 observations |
| Tenant isolation, fail-closed authorization and audit | Forced RLS on both V25 tables with `(organization_id, id)` composite keys, append-only triggers, actor-forced `created_by`/`approved_by`, tenant+test advisory lock for version allocation; `TrendService.authorize` re-derives membership and roles each request and audits denials | `TrendingTest.tenantsStayIsolatedAndRolesAreEnforced` (two tenants, identical local codes/MRN, wrong-unit/wrong-role denial, revocation on next request); `TrendServiceTest` denial/audit branches |

Entry-point-to-test trace: `POST /api/v1/trends/comparability` → `TrendController.define` → `TrendService.defineComparability` → guarded `TransactionTemplate` → `TrendRepository.nextComparabilityVersion` (V25 advisory lock) + `insertComparability` (forced actor) → `COMPARABILITY_DEFINE` audit → 201. `POST /api/v1/trends/observations` → `TrendController.record` → `TrendService.recordObservation` → guarded transaction → `TrendRepository.insertObservation` (forced RLS + actor) → `OBSERVATION_RECORD` audit → 201. `GET /api/v1/trends/{patientId}?testCode=…` → `TrendController.trend` → `TrendService.readTrend` → guarded transaction → `TrendRepository.findComparability`/`observationsForPatient` → `TrendAnalyzer.analyze` → `TREND_READ` audit → 200 with points + exclusions.

## Defect fixed in this pass (test-first, RED → GREEN)

**F1 — effective-dated fold ordered by version instead of effective date (reviewer SR-BACKEND, Medium correctness).** `defineComparability` accepts an arbitrary `effectiveFrom` on every version but V25's `current_trend_comparability` used `ORDER BY org, test_code, version DESC`. A backdated higher version would displace the rule actually in force, applying the wrong canonical unit and hiding correct-unit results as `INCOMPATIBLE_UNIT`.

- **RED:** added `TrendMigrationContractTest.effectiveComparabilityOrdersByEffectiveDateBeforeVersion`; `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=TrendMigrationContractTest test` → EXIT 1, 1 failure (`order by c.organization_id, c.test_code, c.version desc`).
- **GREEN:** changed V25 fold to `ORDER BY c.organization_id, c.test_code, c.effective_from DESC, c.version DESC` (version only breaks an effective-date tie); same command → EXIT 0, 1 pass.
- **Behavioural coverage for the integrator:** added `TrendingTest.aBackdatedHigherVersionDoesNotOverrideTheLaterEffectiveRule` — defines v1 (2026-01-01, mmol/mol) then a backdated v2 (2025-01-01, %) and asserts `ruleVersion` stays v1, only the mmol/mol observation trends, and the % result stays visible as `INCOMPATIBLE_UNIT`. This exercises the real PostgreSQL fold; I could not run it locally (no PostgreSQL server/client in this worker image — connection refused on 127.0.0.1:5432).

## Commands run and results (evidence: executed tool commands)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. Surefire: **1464 tests, 0 failures, 0 errors, 0 skipped** (exact Java command in `scripts/quick-check.sh`). In scope: `TrendAnalyzerTest` 17, `TrendControllerTest` 3, `TrendMigrationContractTest` 1, `TrendRepositoryTest` 8, `TrendServiceTest` 17. `TrendingTest` is `@Tag("integration")` and correctly excluded from this DB-less tier; its class compiled successfully.
- `mvn ... -Pno-security -Dtest=TrendingTest test` (probe) → EXIT 1, `Connection refused` at 127.0.0.1:5432 — **no PostgreSQL in this worker image**. Database/integration verification is delegated to the integrator as instructed; no result was fabricated.
- `python3 -I scripts/check-migrations.py --require-baseline` → EXIT 0 (V25 `Change: expand`, version sequence and format valid after the fold fix).
- `python3 -I scripts/check-docs.py` → EXIT 0. `python3 -I scripts/check-secrets.py` → EXIT 0.
- `git status --short` clean at the base commit `da4fbb4` before this change.

## Findings not changed (with rationale)

- **F2 — corrected/entered-in-error revisions have no lineage (reviewer SR-BACKEND, Medium).** `corrected` is classified `NON_FINAL_REVISION` and excluded, but V25 has no `supersedes_id`, so a superseded `final` can stay plotted while its correction is hidden. Cross-story dependency on **XCH-04 / S-G2-04** and **LAB-02 / S-G2-09** (correction versions, reconciliation). Adding lineage here would exceed the declared scope and duplicate another story's model; recorded for the Product Owner / architect rather than silently changing clinical semantics. The rest of XCH-07 (comparability gates, display fields, visible exclusions) is unaffected.
- Residual risks noted by review: a single current rule is applied to all historical observations; unit comparison is exact string equality; `tenant_admin` self-approves comparability policy (clinical approval is a G5 site decision); no pagination on `observationsForPatient` (ARC-03 pagination is X-01); the `DUPLICATE_OBSERVATION` 409 catches any `DataIntegrityViolationException` (V25 has no source-revision unique constraint; cross-event dedup is ARC-05 / X-02). None blocks XCH-07's stated acceptance.
- Pre-existing, out-of-scope: `test_java_conventions.py` reports 1 failure for `messaging/s_g3_11/PatientFlowContract.java` (S-G3-11, committed at base). Not modified.
- I did **not** run `db/check.sh`, `mvn verify`, OWASP, SpotBugs or JaCoCo — per the worker contract these belong to the integrator. No coverage result was fabricated.
- Synthetic fixtures only. No patient data, credentials or raw logs recorded. Evidence sources: executed tool commands; one independent simulated senior-backend review; no human or clinical evidence. Real-patient approval remains a G5 gate.
