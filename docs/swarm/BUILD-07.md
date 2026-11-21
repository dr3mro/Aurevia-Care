# BUILD-07 · Result trending only for comparable units

PR #290, tested branch tip 1437766850c884cb207ba97d4d901034e3f76238.

Task: BUILD-07

Swarm worker 10. Local checks: passed. Agent result: ok.

# BUILD-07 — Result trending only for comparable units

## Summary

Survey first found that the XCH-07 trend engine (persisted observations, effective-dated
comparability policy, visible exclusions, qualified/qualitative handling) was already
delivered by S-G2-07 in `exchange/trend` (migration V25, `TrendController` ->
`TrendService` -> `TrendRepository`/`TrendAnalyzer`). Its join rule, however, only ever
accepted an exact source-unit match: both `TrendAnalyzer` and `LabRules` rejected every
mixed unit and the migration states "no conversion is attempted". Story point 2 also
allows a join "or a documented conversion exists". This change adds that missing,
versioned, documented conversion mechanism to the pure `lab/result` kernel and extends
the numeric comparability predicate to use it. No clinical factor is invented: a
conversion must name the approved document, version, factor and rounding scale.

Change is test-first, synthetic data only, inside the declared scope, no new dependency,
no migration (none reserved for this story).

## Affected requirements

- XCH-07 — join numeric observations only when test identity and unit match or a
  documented conversion exists; qualifiers stay qualifiers; mixed units stay visible.
- LAB-02 — "Evaluate only eligible comparable results"; units and qualifiers are
  retained on every observation.

Acceptance mapping:
| Story point | Where satisfied | Evidence |
|---|---|---|
| 2. join only on matching test+unit or documented conversion | `LabRules.comparable(first, second, conversions)`; existing `TrendAnalyzer.classify` | `DocumentedUnitConversionTest`, `TrendAnalyzerTest` |
| 3. qualifiers never exact; source unit/range/time visible | existing `Qualifier`, `TrendPoint`, `TrendView` (read, unchanged); qualified results stay ineligible in `LabRules.eligible` | `TrendAnalyzerTest.qualifiedNumericResultsAreVisibleButNotTrended`, `DocumentedUnitConversionTest.aDocumentedConversionNeverJoinsADifferentTestOrAQualifiedValue` |
| 4. mixed units excluded with a reason; qualified not plotted exact | existing `ExclusionReason.INCOMPATIBLE_UNIT` / `.QUALIFIED_NUMERIC`; new lab-level negative tests | `TrendAnalyzerTest.incompatibleUnitsAreVisibleButNotTrended`, `DocumentedUnitConversionTest.mixedUnitsWithoutADocumentedConversionAreNotJoined` |

## Risk class

Safety-critical (clinical comparability). The change is a domain mechanism only; it
creates no persisted clinical rule, no patient link and no release/escalation path.
Approved conversion documents and site clinical approval remain a human G5 decision.

## Review owner

Senior Java/Spring Boot and Senior PostgreSQL (boundary unchanged: no SQL added);
Physician reviewer for the documented-conversion policy concept (not performed here —
no authorized clinician available to this worker).

## Data changes

None. No Flyway migration, backfill, lock or volume impact. Conversion documents are
supplied to the pure kernel by the caller; the engine never persists or invents one.
Rollback is reverting this commit.

## Authorization boundaries and audit events

None added or changed. The comparability kernel has no HTTP, tenant or audit boundary;
existing `TrendService` authorization (clinical role for reads/writes, tenant admin for
policy) is unchanged. No new audit event or patient data handling.

## Migration and rollout considerations

Not applicable to this change. **Follow-up required (see Findings):** persisting
site-approved conversion documents needs a reserved migration version, RLS/FORCE RLS,
an approver-derived column, a version fold and a comparability-policy API, plus clinical
approval before any conversion is enabled for a site. That work is outside this story's
scope and was not started.

## Test evidence

All commands run from the repository root. Evidence source: executed tool commands
(automated). No reviewer, simulated-user or human-participant evidence was used.

- `mvn -q -B -f apps/api/pom.xml -Dtest=DocumentedUnitConversionTest test`
  -> red before implementation (missing `LabUnitConversion`, `LabConversionCatalog`,
  `Qualifier.LT` in the new test); corrected the enum reference, implemented production
  code, then green.
- `mvn -q -B -f apps/api/pom.xml -Dtest=DocumentedUnitConversionTest,ComparabilityTest,LabResultServiceTest test`
  -> green.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test`
  -> exit 0; Surefire totals: **Tests run: 2055, Failures: 0, Errors: 0, Skipped: 0**.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check`
  -> exit 0 (SpotBugs clean). OWASP stays with the integrator (worker has no NVD key).
- `bash scripts/frontend-check.sh` -> "frontend standards: policy and tool commands
  passed"; staff-web 49 files / 540 tests green, patient-mobile 41 suites / 454 tests
  green, typecheck/lint/prettier clean (unchanged areas, run as a policy check).
- `bash scripts/quick-check.sh` -> stopped at `check-pack: opencode.json is stale` —
  known cloud-worker artifact (see Findings); all other quick-check steps were then run
  directly and passed.
- Direct policy checks: bash -n + shellcheck on all scripts; `project-name.py`,
  `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`,
  `check-migrations.py --require-baseline` -> all exit 0.
- Python tooling: `python3 -I -m unittest discover -s scripts/tests -q` -> 436 run,
  3 failures, all in `test_check_pack` (same `opencode.json` artifact). Re-running the
  other 427 tests with `test_check_pack` excluded -> **427 run, 0 failures, 0 errors**.
- `bash scripts/docs.sh` -> exit 0, developer reference generated; no warning named a
  file changed by this story.
- Full `scripts/check.sh` / `mvn verify` was **not run**: it launches every PostgreSQL
  integration suite and JaCoCo/OWASP and exceeds the worker time budget. The worker
  instruction says to leave that gate to the integrator and Delivery.

Entry point to test trace: `LabRules.comparable(...)` (pure comparability predicate used
by the LAB-02 kernel) -> `DocumentedUnitConversionTest` and `ComparabilityTest`; the
HTTP trend entry point `TrendController.record/define/trend` ->
`TrendService.readTrend` -> `TrendRepository.observationsForPatient` ->
`TrendAnalyzer.analyze` is proven by the existing `TrendControllerTest`,
`TrendServiceTest`, `TrendRepositoryTest` and `TrendAnalyzerTest` (verified by source
read; not re-run here because integration classes are outside this worker's scope).

## Coverage

Command: `mvn -q -B -f apps/api/pom.xml -Dtest=DocumentedUnitConversionTest,ComparabilityTest,LabResultServiceTest test`
followed by `mvn -q -B -f apps/api/pom.xml jacoco:report`, reading
`apps/api/target/site/jacoco/jacoco.xml` per touched source file. Results (missed/covered):

| File | Instruction | Line | Branch | Method | Class |
|---|---|---|---|---|---|
| `lab/result/LabUnitConversion.java` | 0 / 142 | 0 / 20 | 0 / 32 | 0 / 3 | 0 / 1 |
| `lab/result/LabConversionCatalog.java` | 0 / 39 | 0 / 9 | 0 / 2 | 0 / 4 | 0 / 1 |
| `lab/result/LabRules.java` | 0 / 75 | 0 / 14 | 0 / 18 | 0 / 5 | 0 / 1 |

100% on every touched production file. New branches and their tests:

- `LabUnitConversion` constructor: null/blank test code, from unit, to unit and
  documentation (four `||` pairs) -> `conversionValidationRejectsIncompleteOrUnsoundedDocuments`;
  same from/to unit, non-positive factor (zero and negative), negative scale, version
  < 1 -> same test; valid path -> `conversionKeepsTheDocumentedScaleAndUsesHalfUpRounding`.
- `LabUnitConversion.appliesTo`: all three identity matches -> `catalogValidationAndAppliesToCoverTheirBoundaries`;
  code mismatch, source-unit mismatch, target-unit mismatch -> same test.
- `LabConversionCatalog` constructor null vs non-null, `none()`, `find` empty vs present
  vs highest version -> `catalogSelectsTheHighestApprovedVersionAndOnlyTheExactDirection`,
  `mixedUnitsWithoutADoc
