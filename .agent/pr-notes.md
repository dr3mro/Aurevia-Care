# S-G2-07 — XCH-07 comparable-observation trending

## Requirement IDs and scope

- Primary: **XCH-07** (trend only comparable observations; keep source unit, reference range, time,
  interpretation, qualifiers, qualitative results and incompatible units visible).
- Supporting: ADM-01 (versioned, effective-dated comparability policy), ARC-01/02/03 (tenant keys,
  forced RLS, audited commands), OPS-02 (audit of reads and denials), XCH-04/LAB-02 (revision states).
- Scope note: the dispatch text named `g2/s_g2_07`, but AGENTS.md forbids story-ID package names and
  the canonical TODO.md scope is `exchange/trend` + `it/exchangetrend`. `.agent/pr-notes/REFACTOR-PACKAGES.md`
  records the repo-wide rename `g2/s_g2_07` → `exchange/trend`. All edits stay inside those two paths.
  Migration version: **V25 only** (already present at base SHA `d79a776`; not modified).

## Entry-point-to-test trace

`POST /api/v1/trends/observations` → `TrendController.record` → `TrendService.recordObservation`
→ `TenantContextInterceptor` (subject + explicit active organization) → tenant-guarded
`TransactionTemplate` → `TrendRepository.insertObservation` (V25 forced RLS + actor-derived
`created_by`) → `AuditAppender.append(OBSERVATION_RECORD, SUCCESS)` → 201.
`POST /api/v1/trends/comparability` → `TrendController.define` → `TrendService.defineComparability`
→ advisory-lock version fold (`next_trend_comparability_version`) → append-only rule →
`COMPARABILITY_DEFINE` audit → 201.
`GET /api/v1/trends/{patientId}?testCode=` → `TrendController.trend` → `TrendService.readTrend`
→ membership/role re-derivation → `TrendRepository.findComparability` + `observationsForPatient`
→ `TrendAnalyzer.analyze` → `TrendView` (points + visible exclusions) → `TREND_READ` audit → 200.
`TrendingTest` (real PostgreSQL, `@Tag("integration")`) exercises all three through HTTP;
`TrendAnalyzerTest`, `TrendServiceTest`, `TrendRepositoryTest`, `TrendControllerTest`,
`TrendMigrationContractTest`, `TrendingFixtureTest` cover the same paths in the DB-less fast tier.

## Acceptance mapping

| XCH-07 behavior | Fast-tier test | Integration assertion |
|---|---|---|
| Establish test identity, comparability group, canonical unit before trending | `TrendAnalyzerTest.trendsOnlyComparableObservationsInEffectiveTimeOrder`, `aDifferentTestIsVisibleButNotMixedIntoTheTrend`, `aDifferentComparabilityGroupIsNeverSilentlyJoined`, `incompatibleUnitsAreVisibleButNotTrended` | `TrendingTest.trendsOnlyComparableObservationsAndKeepsEveryExclusionVisible` |
| Qualifiers, qualitative and incompatible results stay visible, never plotted | `qualifiedNumericResultsAreVisibleButNotTrended`, `qualitativeResultsAreVisibleButNotTrended`, `nonFinalRevisionsAreNeverTreatedAsCurrentValues` | excluded-list assertions (qualifier `less_than`, `qualitativeValue`, unit `%`) |
| Display source unit, reference range, time, interpretation and provenance | `eachTrendPointShowsValueUnitRangeTimeInterpretationAndProvenance` | point assertions for `unit`, `referenceLow/High/Unit`, `effectiveAt`, `interpretation`, `sourceSystem`, `sourceVersion` |
| Stable wire value for qualifiers | `qualifierSerializesWithItsStableWireValue` (new) | excluded qualifier JSON assertion |
| Versioned, effective-dated policy; future/backdated rule handling | `TrendServiceTest.definesANewComparabilityVersionAndAuditsIt`, `TrendMigrationContractTest` | `comparabilityIsVersionedAndAFutureRuleDoesNotBecomeEffectiveEarly`, `aBackdatedHigherVersionDoesNotOverrideTheLaterEffectiveRule` |
| No rule still shows all observations | `absentOrNonTrendableComparabilityKeepsEveryObservationVisible`, `TrendServiceTest.withoutAnEstablishedRuleTheReadStillShowsEveryObservation` | — |
| Clinical role separation, tenant isolation, audited denials | `TrendServiceTest` denial tests, `trendPolicySeparatesClinicalReadFromPolicyWrite` | `tenantsStayIsolatedAndRolesAreEnforced` |
| Boundary validation | `observationValidationRejectsMissingIdentityValueTimeAndBadRanges`, `TrendServiceTest.rejectsInvalidTrendQueryInput` | `invalidInputsFailAtTheBoundary` |
| Deterministic ordering and defensive copies | `exclusionsAreAlsoOrderedByEffectiveTimeThenId`, `viewCopiesItsListsDefensively` | point-order assertion |

## Defects found and fixed (test first, synthetic fixtures)

1. **Missing ORG_B administrator seed (blocking integration failure).** `TrendingTest.prepare()` seeded
   only `clinician-b` in ORG_B, but `tenantsStayIsolatedAndRolesAreEnforced` calls
   `POST /api/v1/trends/comparability` as `admin-b`/ORG_B expecting 201. With no membership the
   guarded service denies with 403. Added a `seededMembers()` fixture and the missing
   `{ORG_B, admin-b, tenant_admin}` row. New fast regression `TrendingFixtureTest.secondTenantSeedsAnAdministratorForTheCrossTenantScenario`
   was RED (`expected <true> but was <false>`) before the fix and GREEN after.
2. **Cross-test state contamination (blocking, order-dependent integration failure).** Three acceptance
   methods resolved the same `patientId(ORG_A)` (MRN-SYN-1) and posted observations with no per-test
   reset. `TrendRepository.observationsForPatient` returns all tests for the patient, so a GLU point
   recorded by one method either became a second point in the versioning scenario (2 instead of 1) or
   a seventh `DIFFERENT_TEST` exclusion in the primary scenario (7 instead of 6), depending on JUnit
   method order. Each observation-recording scenario now creates its own patient via `createPatient`
   with a distinct MRN. New fast guard `TrendingFixtureTest.observationScenariosUseDistinctPatients`
   asserts the MRNs are unique and never reuse the shared seeded MRN.
3. **Qualifier enum leaked its Java name.** `Qualifier` lacked `@JsonValue`, so HTTP responses showed
   `LESS_THAN` instead of the documented wire value `less_than` that `Qualifier.from` accepts and the
   database stores. Fast test `qualifierSerializesWithItsStableWireValue` was RED (`"LESS_THAN"`)
   then GREEN after annotating `wire()` with `@JsonValue`, matching every other public enum in the repo.
4. **Garbled Javadoc** in `TrendObservation` (a stray unlabelled fragment before `@return`) was replaced
   with the intended statement that only `final` revisions in an established comparability rule are
   plotted and non-final revisions remain visible as exclusions.
5. **XCH-07 display fields only asserted at the domain tier.** Added HTTP-level assertions for
   `effectiveAt` and `sourceVersion` on points and for qualifier value, qualitative text and unit on
   exclusions, so a JSON mapping regression cannot pass silently.

## Commands and results (tool evidence unless labelled otherwise)

- `mvn -B -f apps/api/pom.xml -Pno-security,fast test -Dtest=TrendingFixtureTest` → **1 test, 1 failure**
  (RED: missing admin-b seed) at base; after fix **1 test, 0 failures** (GREEN).
- `mvn -B -f apps/api/pom.xml -Pno-security,fast test -Dtest=TrendAnalyzerTest` → **18 tests, 1 failure**
  (RED: `"LESS_THAN"`); after `@JsonValue` **18 tests, 0 failures** (GREEN).
- `mvn -B -f apps/api/pom.xml -Pno-security,fast test` → **1548 tests, 0 failures, 0 errors** (final full
  fast unit suite; was 1546 before the two new guards).
- `bash scripts/quick-check.sh` → stopped at `check-pack: opencode.json is stale` (see environment
  below). The policy steps before it passed: `bash -n`/shellcheck, both `project-name.py` values,
  `check-docs.py`, `check-secrets.py`, `check-migrations.py --require-baseline` (all exit 0).
- `python3 -I -m unittest discover -s scripts/tests -q` → 164 tests with 10 failures/errors, all
  environmental and unrelated to the scoped files (details below).
- `bash scripts/docs.sh` → "Install Doxygen to build the developer reference" (tool unavailable).
- `mvn` runs used JDK 21.0.13 LTS locally; the `fast` profile excludes `@Tag("integration")`.
- Database/integration tests were **not executed locally** (port 5432 is closed and no
  `SMARTCLINIC_PG_ADMIN`); per the dispatch the integrator runs them.

## Reviewer evidence (simulated; no human or clinical participant)

Two bounded specialist reviews were run and their findings triaged:
- `smartclinic-test-engineer`: found the cross-test patient contamination (fixed) and the missing
  HTTP-level time/qualifier assertions (fixed); confirmed the fixture guard is RED without the fix.
- `smartclinic-senior-backend`: raised three broader findings recorded as residual risks below.
Both reviews were static source review; the reviewers did not edit files, run builds or commit.

## Residual risks / decisions required (not resolved here)

These need a named owner decision; they were **not** silently implemented or assumed:

- **Revision supersession (senior-backend HIGH).** V25 has no lineage column or current-revision fold,
  so a corrected / entered-in-error result is excluded while an earlier `final` row can still be
  plotted. Needs an Architect + Clinical Safety decision on revision lineage and a forward migration;
  a new migration version is outside this story's reserved V25. Owner: Architect / clinical safety lead.
- **Durable dedup on source revision (senior-backend HIGH).** V25 only has a surrogate PK, so the
  `409 DUPLICATE_OBSERVATION` path in `TrendService` cannot currently be raised by a duplicate event
  and a retried external result could be plotted twice. Durable event dedup is owned by ARC-05/X-02;
  the dedup key and owner need a Product Owner + Architect decision. Not changed here.
- **Named approval and effective window for comparability (senior-backend MEDIUM/HIGH).** A single
  `tenant_admin` can set `trendable=true` and supply a backdated `effectiveFrom`; there is no second
  approver or `effective_to`. The code deliberately stores the mechanism only and defers clinical
  values to site approval (ADM-01/G5). A Clinical Safety + Privacy decision is required before a
  live policy is enabled. Not changed here.
- **Fixture guard coverage (test-engineer caveat).** The fast guard covers the `admin-b`/ORG_B pair
  and patient MRN uniqueness; other privileged pairs remain covered only by the PostgreSQL suite.
- **No clinical or human evidence.** All fixtures are synthetic; site clinical approval is a G5 gate.

## Environment caveats (evidence quality)

- `opencode.json` in this worker is the CI profile placed under **skip-worktree** (`git ls-files -v`
  shows `S`); its bytes differ from HEAD (extra `deny` rules for `gh`, `git push/tag/remote/config`,
  `curl`). This is the documented OPS-CW-003-01 worker artifact, not a change from this story.
  Verified with the generator: `git show HEAD:opencode.json` **matches** the generated local profile and
  `.github/opencode.ci.json` matches its generated CI profile. `check-pack.py`/`gen-opencode.py --check`
  judge the worktree CI profile and are red for that reason only. The file was not edited.
- Python tooling failures: `ModuleNotFoundError: No module named 'yaml'` (4 errors: `test_fast_tier`,
  `test_workflow_concurrency`, two in `test_swarm`), three `test_check_pack` failures from the
  skip-worktree artifact, two `test_agent_run` failures from simulated branch/CI state in this worker,
  and `test_open_spec_tasks_are_dispatchable` (`9 not >= 10` from the current TODO.md snapshot).
  None of these tests read the scoped trending files, so none is caused by this change.
- Doxygen and PostgreSQL are unavailable in this worker; their gates are owned by CI/the integrator.
