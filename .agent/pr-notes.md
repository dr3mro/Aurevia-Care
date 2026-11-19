# S-G2-07 — Trend only comparable observations with units, ranges, time and qualifiers visible

Story: **S-G2-07** (swarm attempt 4), branch `swarm/S-G2-07-100`, resume checkpoint `f8deafe`.
Scope: `apps/api/src/main/java/com/aurevia/smartclinic/g2/s_g2_07`,
`apps/api/src/test/java/com/aurevia/smartclinic/it/s_g2_07`. Reserved migration: **V25** only.

## Requirement IDs and acceptance mapping

Primary: **XCH-07**. Supporting: **ADM-01, ARC-01, ARC-02, ARC-03, OPS-02, IAM-01**.
Specification: `SMARTCLINIC_V1_SPEC.md` §4 XCH-07, §7 ARC-01..03, §8 OPS-02.

| XCH-07 acceptance behaviour | Implementation (in scope) | Synthetic test evidence |
|---|---|---|
| Trend numeric observations only when test identity, units and clinically relevant comparability are established | `TrendAnalyzer.classify` requires matching test + comparability group, `final` revision, unqualified numeric value and canonical unit; `ComparabilityRule` + effective-dated `current_trend_comparability` fold (V25) define the rule; `TrendActionPolicy`/`TrendService` separate clinical access from policy definition | `TrendAnalyzerTest` (17), `TrendServiceTest` (17), `TrendControllerTest` (3), `TrendRepositoryTest` (8), `TrendMigrationContractTest` (1); `TrendingTest` (6 integration, integrator) |
| Display source unit, reference range, time and interpretation, with provenance | `TrendPoint` carries `unit`, `referenceLow/High/Unit`, `effectiveAt`, `interpretation`, `sourceSystem/Version`; `Observation` rejects inverted ranges and a range whose unit differs from the source unit | `TrendAnalyzerTest.eachTrendPointShowsValueUnitRangeTimeInterpretationAndProvenance`, `observationValidationRejectsMissingIdentityValueTimeAndBadRanges`; `TrendRepositoryTest.observationMapperReadsEveryDisplayedColumn` |
| Keep less-than / greater-than, qualitative and incompatible-unit results visible, never as comparable measurements | `ExcludedObservation` keeps the whole `Observation` unchanged with a closed `ExclusionReason`; `TrendView.points() + excluded()` keeps every observation observable (nothing silently dropped) | `TrendAnalyzerTest.qualifiedNumericResultsAreVisibleButNotTrended`, `qualitativeResultsAreVisibleButNotTrended`, `incompatibleUnitsAreVisibleButNotTrended`, `exclusionsAreAlsoOrderedByEffectiveTimeThenId`, `viewCopiesItsListsDefensively`; `TrendingTest` asserts 3 points + 6 exclusions from 9 observations |
| Tenant isolation, fail-closed authorization and audit | Forced RLS on both V25 tables with `(organization_id, id)` composite keys, append-only triggers, actor-forced `created_by`/`approved_by`, tenant+test advisory lock for version allocation; `TrendService.authorize` re-derives membership and roles each request and audits denials | `TrendingTest.tenantsStayIsolatedAndRolesAreEnforced` (two tenants, identical local codes/MRN, wrong-role denial, revocation on next request); `TrendServiceTest` denial/audit branches |

Entry-point-to-test trace: `POST /api/v1/trends/comparability` → `TrendController.define` → `TrendService.defineComparability` → guarded `TransactionTemplate` → `TrendRepository.nextComparabilityVersion` (V25 advisory lock) + `insertComparability` (forced actor) → `COMPARABILITY_DEFINE` audit → 201. `POST /api/v1/trends/observations` → `TrendController.record` → `TrendService.recordObservation` → guarded transaction → `TrendRepository.insertObservation` (forced RLS + actor) → `OBSERVATION_RECORD` audit → 201. `GET /api/v1/trends/{patientId}?testCode=…` → `TrendController.trend` → `TrendService.readTrend` → guarded transaction → `TrendRepository.findComparability`/`observationsForPatient` → `TrendAnalyzer.analyze` → `TREND_READ` audit → 200 with points + exclusions.

## What this worker did (attempt 4)

The base resume checkpoint already contains a complete, green S-G2-07 implementation. This
attempt re-ran the full fast unit tier and JaCoCo coverage: every in-scope behaviour passes and
all 14 `s_g2_07` production classes are at 100% instruction/branch/line/method coverage. No
in-scope production or test defect was found, so **no source change was necessary**. The only
change in this commit is this PR description — the file had become a recycled concatenation of
many other stories' notes and is now scoped to S-G2-07.

## Defect fixed in a prior pass (preserved for the integrator) — RED → GREEN

**F1 — effective-dated fold ordered by version instead of effective date** (simulated reviewer
SR-BACKEND, Medium correctness). `defineComparability` accepts an arbitrary `effectiveFrom`, but
V25's `current_trend_comparability` used `ORDER BY org, test_code, version DESC`; a backdated
higher version could displace the rule actually in force and hide correct-unit results.

- RED: added `TrendMigrationContractTest.effectiveComparabilityOrdersByEffectiveDateBeforeVersion`;
  `mvn ... -Pno-security,fast -Dtest=TrendMigrationContractTest test` → EXIT 1.
- GREEN: changed the fold to `ORDER BY c.organization_id, c.test_code, c.effective_from DESC,
  c.version DESC` (version only breaks a tie); same command → EXIT 0.
- Behavioural proof: `TrendingTest.aBackdatedHigherVersionDoesNotOverrideTheLaterEffectiveRule`
  exercises the real PostgreSQL fold (integrator gate).

## Commands run and results (evidence source: executed tool commands, this attempt)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. Surefire: **1505 tests, 0 failures, 0 errors, 0 skipped** (exact Java command in `scripts/quick-check.sh`). In scope: `TrendAnalyzerTest` 17, `TrendControllerTest` 3, `TrendMigrationContractTest` 1, `TrendRepositoryTest` 8, `TrendServiceTest` 17 (46 tests). `TrendingTest` is `@Tag("integration")` and correctly excluded from this DB-less tier; its class compiled successfully.
- `mvn -q -B -f apps/api/pom.xml jacoco:report` → **EXIT 0**. Every `com.aurevia.smartclinic.g2.s_g2_07` class shows **0 missed instructions, branches, lines and methods** in `target/site/jacoco/jacoco.csv` (100% coverage; no fabricated or pending-as-covered results).
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0** (V25 `Change: expand`, unique version, format valid).
- `python3 -I scripts/check-docs.py` → **EXIT 0**. `python3 -I scripts/check-secrets.py` → **EXIT 0**.
- `mvn -q -B -f apps/api/pom.xml spotbugs:check` → EXIT 1 with 14 findings, **all in other stories' code** (`s_g0_07`, `s_g4_02`, `s_g4_04`, `s_g3_05`, `s_g3_08`, `s_g3_10`); **none in `s_g2_07`**. Pre-existing and out of scope; not modified.
- `bash scripts/quick-check.sh` → EXIT 1 at `check-pack: opencode.json is stale`. Known cloud-worker artifact: `agent-run.sh` copies the CI profile over `opencode.json` under `skip-worktree` (see BUGS `OPS-CW-003-01`); out of scope and not modified.
- `bash scripts/docs.sh` → EXIT 1 (`Install Doxygen to build the developer reference`) — Doxygen is not installed in this worker image; out of scope.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 1 failure in `messaging/s_g3_11/PatientFlowContract.java` (pre-existing, S-G3-11); out of scope.
- `TrendingTest` (real PostgreSQL) was **not** run locally: no database is reachable in this worker image per the worker contract. The integrator runs `db/check.sh` and the integration suite. No result was fabricated.

## Defects found

None in scope. Out-of-scope observations above are recorded for the integrator only and were not changed.

## Decisions / residual risks (not changed, with rationale)

- **F2 — corrected/entered-in-error revisions have no lineage.** `corrected` is classified `NON_FINAL_REVISION` and excluded, but V25 has no `supersedes_id`; a superseded `final` can stay plotted while its correction is hidden. Cross-story dependency on XCH-04 (S-G2-04) and LAB-02 (S-G2-09) correction versions; adding lineage here would exceed scope and duplicate another story's model. Recorded for the Product Owner / architect. The rest of XCH-07's acceptance is unaffected.
- Residual risks from simulated review: a single current rule is applied to all historical observations; unit comparison is exact string equality; `tenant_admin` policy definition is a mechanism only (clinical approval is a G5 site decision); no pagination on `observationsForPatient` (ARC-03 pagination is X-01); `DUPLICATE_OBSERVATION` 409 catches any `DataIntegrityViolationException` (cross-event dedup is ARC-05 / X-02). None blocks XCH-07's stated acceptance.
- Reserved migration **V25** is used only for this story (`Change: expand`); no other version was touched and migration contiguity passes.
- Scope respected: only the two declared `s_g2_07` paths plus this PR-description file were reviewed/changed; no shared tracker, no other migration version, no credentials or patient data.

## Evidence sources

All command results above are tool-generated from this worker image. The F1 finding came from a
prior simulated senior-backend review (finding verified by tool command, not re-run by a human).
No simulated-user, human or clinical evidence was used; no real patient data or credentials were
handled. Real-patient approval remains a G5 gate.
