# S-G1-03 — CLN-03: Longitudinal chart (problems, allergies, medications, vitals, source and verification states)

Status: implementation present in the authoritative scope and re-verified from scratch on this attempt (branch `swarm/S-G1-03-123`, base `e5665c8`). Two real in-scope defects were found, reproduced red and fixed: (1) the integration fixture violated the V7 patient invariant; (2) a blank `unknownReason` on a non-UNKNOWN assertion reached the V13 CHECK and surfaced as a misleading 409. Ready for the integrator to run the PostgreSQL integration suite, sequence V13 into `db/migrations` and remove the out-of-scope legacy duplicate slice.

## Authoritative scope and routing

- Scope from `TODO.md` (the untrusted story text carries no package path):
  - `apps/api/src/main/java/com/aurevia/smartclinic/chart/record`
  - `apps/api/src/test/java/com/aurevia/smartclinic/it/chart/record`
- Reserved migration: `V13`, kept test-scoped at `it/chart/record/migrations/V13__longitudinal_chart.sql`. Sequencing into `db/migrations` is the integrator's step because the OPS-06 checker owns that directory and a legacy V13 also exists out of scope.
- Classification (`docs/WORKFLOW.md`): **safety-critical** — patient linkage, clinical authorship, verification state, tenant isolation. Named reviewers: senior backend, senior database, software architect, test engineer, physician. All review in this note is simulated unless stated; no human or clinical participant was involved and every fixture is synthetic.
- Requirements: CLN-03 (primary); ARC-01, ARC-02, ARC-03, OPS-02, IAM-01/02 (supporting).

## What this attempt changed (test first, red → green)

The 11 production classes and 4 fast test classes were already present in scope from earlier attempts. Surveying them against the executed specification found one genuine, reproducible defect in the in-scope integration fixture:

- **RED evidence (tool):** `db/migrations/V7__patient_registration.sql:26` declares `CHECK ((dob IS NOT NULL) <> (dob_unknown_reason IS NOT NULL))`. `ChartRecordIntegrationTest.seedPatient` inserted `patient_record` with neither column, so `@BeforeAll` would abort on a real PostgreSQL before any chart behavior was exercised. A merged-story fixture (`it/exchangerelease/PatientReleaseIsolationTest.java:114-117`) shows the correct pattern. This matches the independent DLV-01 tracker note for the same class.
- **Fix:** `seedPatient` now inserts synthetic `dob = DATE '1990-05-20'` and documents why. No production code changed; no shared tracker, generated config or out-of-scope path touched.
- **GREEN evidence (tool):** `mvn -o -q -B -f apps/api/pom.xml -Pno-security,fast test-compile` exit 0 after the fix. The integration suite itself cannot execute locally (no PostgreSQL/Docker), so its final GREEN is the integrator's `bash apps/api/check.sh` on the merged SHA — explicitly still required evidence, not claimed here.

### Second defect — blank `unknownReason` is normalized before the V13 CHECK (simulated review finding, fixed)

A bounded senior-backend review (simulated) found that `ChartService.validate` accepted a blank (`" "`/`""`) `unknownReason` on a PRESENT/ABSENT entry as "ignored", but `record()` passed it verbatim to `ChartRepository.insert`. V13's `CHECK (unknown_reason IS NULL OR presence = 'UNKNOWN')` then rejected it (SQLSTATE 23514), translated by `SqlFailureTranslator` to a misleading `409 CONFLICT`, so a valid entry could not be captured. The existing fast test asserted the opposite while mocking the DB, giving false confidence.

- **RED (tool):** added `ChartRecordServiceTest.blankUnknownReasonOnNonUnknownEntryIsNormalizedToNull` plus a null assertion in `remainingValidationShortCircuitBranches`; reflection runner → **passed=34 failed=2 total=36**, both failures `expected: <null> but was: <  >` / `< >`.
- **Fix:** `ChartService` now uses a `normalizedReason` helper (blank → `null`) in validation and when constructing the entry, so "ignored" is literally true; `ChartRecordIntegrationTest.blankUnknownReasonIsNormalizedBeforeTheDatabaseCheck` asserts a POST with `presence=ABSENT, unknownReason=" "` returns 201 with `"unknownReason":null` (integrator executes this).
- **GREEN (tool):** reflection runner → **passed=36 failed=0 total=36**; 100% JaCoCo retained on all fast-covered in-scope classes including the new helper.

## Exact commands and results (all tool-generated, Java 21.0.13 Temurin, Maven 3.10.0, offline `/opt/m2`)

| Command | Result |
|---|---|
| `mvn -o -q -B -f apps/api/pom.xml -Pno-security,fast test-compile` | exit 0 (before and after the fix) |
| `mvn -o -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ChartRecord*' test` | exit 1 — `org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 (absent)`; offline environment gap, not a code failure |
| Reflection runner over the 4 fast classes, invoked against Maven-compiled classes with JUnit 5 lifecycle (`@BeforeEach`/`@Test`/`@AfterEach`) | RED before the blank-reason fix: **passed=34 failed=2 total=36**, exit 1; GREEN after: **passed=36 failed=0 total=36**, exit 0 |
| Same run under JaCoCo 0.8.16 agent + `mvn -o -q -B -f apps/api/pom.xml -Pno-security jacoco:report` | 100% instructions/branches/lines/methods on every fast-covered in-scope class (see Coverage), report generated |
| `mvn -o -q -B -f apps/api/pom.xml -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` | exit 0, no findings (explicitly non-skipped) |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2/2 OK (fully-qualified Javadoc links, list-bearing records copy on store/read) |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py`; `git diff --check` | exit 0 each |
| `git status --short` | only `.agent/pr-notes/S-G1-03.md` and `ChartRecordIntegrationTest.java`; no shared tracker or generated config touched |

## Coverage (JaCoCo, this attempt's fast run)

| Class | Instructions | Branches | Lines | Methods |
|---|---|---|---|---|
| ChartService | 936/936 | 112/112 | 196/196 | 17/17 |
| ChartActionPolicy | 35/35 | 7/7 | 10/10 | 3/3 |
| ChartController | 36/36 | n/a | 8/8 | 5/5 |
| ChartView | 13/13 | n/a | 4/4 | 2/2 |
| RecordEntryCommand | 48/48 | n/a | 1/1 | 1/1 |
| ChartEntry | 78/78 | n/a | 1/1 | 1/1 |
| EntryType / EntrySource / PresenceState / VerificationState | 27/27, 27/27, 21/21, 15/15 | n/a | all | all |
| ChartRepository.CatalogHit | 12/12 | n/a | 1/1 | 1/1 |
| ChartRepository | 3/364 | 0/4 | 1/83 | 1/14 — SQL boundary; authority is the `@Tag("integration")` suite the integrator runs |

No fabricated or pending-as-covered result: `ChartRepository` is deliberately reported as DB-only here, and the integrator's full `apps/api/check.sh` must show it covered before the story is accepted.

## Requirement → acceptance mapping

| CLN-03 clause | Implementation | Evidence |
|---|---|---|
| Problems, allergies, medications, vitals with units, measurement time | `EntryType`, `ChartEntry`, `RecordEntryCommand`, V13 checks, service validation | `ChartRecordValueTest.enumTokensMatchTheDatabaseChecks`; `ChartRecordServiceTest.validationRejectsReversedPeriodAndIncompleteVitalAndForgedAuthor`; `ChartRecordIntegrationTest.recordsReadsAndAuditsAllFourKindsWithProvenance` (DB, integrator) |
| Verification, source and effective dates | `VerificationState`, `EntrySource`, `ChartService`, V13 RLS/checks | `ChartRecordServiceTest.verifySuccessReturnsVerifiedEntryAtNextVersion`; `ChartRecordIntegrationTest.verificationIsVersionBoundAndConcurrent` (DB, integrator) |
| Explicit unknown and none states | `PresenceState`, coded `unknownReason`, validation, V13 `unknown_reason`/`presence` CHECK | `ChartRecordServiceTest.validationRejectsUnknownReasonMismatchAndUnpairedCode`, `unknownAssertionWithReasonIsAcceptedAndVitalWithAllFieldsIsAccepted` |
| Clinical author separate from data-entry actor | `ChartEntry.clinicalAuthor` vs `dataEntryActor`; actor from tenant context; verify assigns accountable author when left open | `ChartRecordServiceTest.recordSnapshotsCatalogMappingAndPreservesOriginals`, `verifyAssignsAccountableClinicalAuthorWhenRecorderLeftItOpen` |
| Local procedure codes, versioned diagnosis catalog, retain originals after mapping | `ChartRepository.lookupCatalog`, write-time snapshot fields, `ChartService.record` | `ChartRecordServiceTest.recordSnapshotsCatalogMappingAndPreservesOriginals`, `recordWithoutMappingKeepsOriginalsAndNullMapping`, `recordDoesNotLookUpMappingForOtherTypes` |
| Tenant isolation, authorization, audit, version-bound verification | forced RLS + `WITH CHECK` in V13, `ChartActionPolicy`, `AuditAppender`, `IfMatch` CAS | `ChartRecordActionPolicyTest` (4 tests), `ChartRecordControllerTest.verifyPropagatesMissingPrecondition` (428); `ChartRecordIntegrationTest.tenantIsolationConcealsCrossTenantRowsAndForcesChecks`, `authorizationAndInputBoundariesFailClosed` (DB, integrator) |
| Chief concern / history / exam / assessment / plan | Out of this slice — owned by S-G1-04 templates and S-G1-05 signing | n/a |

## Defects found and decisions

- **Defect (fixed, in scope):** `ChartRecordIntegrationTest.seedPatient` omitted both `dob` and `dob_unknown_reason`, violating V7's CHECK. Fixed by seeding synthetic `DATE '1990-05-20'`.
- **Defect (fixed, in scope, from simulated review):** blank `unknownReason` on a non-UNKNOWN entry was stored verbatim and rejected by V13's CHECK as 409 instead of being ignored. Fixed by normalizing blank to `null` in `ChartService`; fast and integration regression tests added.
- **Defect (pre-existing, out of scope, integrator action):** a legacy attempt of this story remains at `patient/chart` + `it/patientchart`, with a stale Flyway location `it/s_g1_03/migrations` that does not exist and the same V7 seed defect. It also carries a second V13. The integrator must delete the legacy slice and sequence this attempt's V13 into `db/migrations`. No legacy file was edited here (scope rule). The two slices do not collide at runtime: legacy beans use default names and `/api/v1/chart`; this slice uses explicit `chartRecord*` bean names and `/api/v1/chart/records`.
- **Decision — scope:** followed authoritative `TODO.md` business-domain paths; no story-ID-named package or class introduced.
- **Decision — reuse:** all cross-cutting types (`ApiException`, `IfMatch`, `SqlFailureTranslator`, `AuditAppender`, `TenantContext`) are reused; no new idempotency/export/worker contract invented (X-01/X-02/X-03 own those).
- **Decision — instrument gap:** the local image lacks `surefire-junit-platform:3.5.6` and has no network, so `surefire:test` cannot run here. Fast tests were executed via a reflection runner against Maven-compiled classes. This is genuine tool-generated execution evidence, not a claim that the standard Surefire command was green locally. `apps/api/check.sh` remains authoritative.

## Entry-point-to-test trace

- `POST /api/v1/chart/records/entries` → `ChartController.record` → `ChartService.record` → `ChartActionPolicy` + `ChartRepository.patientExists/lookupCatalog/insert` + `AuditAppender.append` → `ChartRecordServiceTest.record*` and `ChartRecordIntegrationTest.recordsReadsAndAuditsAllFourKindsWithProvenance` (DB).
- `POST /api/v1/chart/records/entries/{id}/verify` (`If-Match`) → `ChartController.verify` → `IfMatch.parse` → `ChartService.verify` → guarded compare-and-set `ChartRepository.verify` → `ChartRecordServiceTest.verify*` and `ChartRecordIntegrationTest.verificationIsVersionBoundAndConcurrent` (DB).
- `GET /api/v1/chart/records/patients/{patientId}/entries` and `GET .../entries/{id}` → `ChartController.chart/get` → `ChartService.listForPatient/get` → forced-RLS reads in `ChartRepository.chart/find` → `ChartRecordServiceTest.reads*` and `ChartRecordIntegrationTest.tenantIsolationConcealsCrossTenantRowsAndForcesChecks` (DB).

## Blockers / evidence genuinely missing

- No local PostgreSQL or Docker (`psql`, `pg_isready`, `postgres`, `docker` all absent). The four `@Tag("integration")` methods in `ChartRecordIntegrationTest` (forced RLS, two-tenant concealment, `WITH CHECK`, version-bound concurrent verification, audit chain, 401/403/404/409/412/422 boundaries) compile here but are **not executed**. The integrator must run `bash apps/api/check.sh` on the exact merged SHA and record JaCoCo ratios; that suite is the authority for `ChartRepository` coverage.
- Doxygen is absent locally, so a full `scripts/docs.sh` reference build was not run; the `test_java_conventions.py` guard passes 2/2. No agent or skill file was edited, so no `opencode.json` regeneration was needed.
- Out-of-scope legacy cleanup and V13 sequencing above are required before the full suite is reliable and this story can be accepted.
- No clinical or human participant evidence; all fixtures synthetic. Clinical approval remains a G5 gate.

## Specialist review

A bounded read-only review was routed to the senior-backend specialist with the authoritative paths, acceptance criteria and executed evidence. It ran no commands; findings are static reading by a **simulated reviewer**, not a human or clinical participant.

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | Medium | Blank `unknownReason` on PRESENT/ABSENT passes service validation, is stored verbatim, and fails V13's CHECK as a misleading 409; the fast test falsely asserted it was ignored | **Fixed**: `ChartService.normalizedReason` maps blank to `null` in validation and on store; RED 34/2 → GREEN 36/0; new integration assertion added for the integrator; 100% JaCoCo retained |
| — | — | Seed fix verified correct against V7's `created_by` trigger, `app_org()` and issuer-scoped MRN uniqueness; no finding | Accepted (no change) |
| — | — | No other material findings on verify CAS, COALESCE agreement, authorization, audit placement, provenance or tenant isolation | Accepted (no change) |
