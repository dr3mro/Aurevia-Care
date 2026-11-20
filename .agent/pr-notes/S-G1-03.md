# S-G1-03 — CLN-03: Longitudinal chart (problems, allergies, medications, vitals, source and verification states)

Status: COMPLETE — implementation present in scope and re-verified by a fresh worker; ready for the integrator to sequence V13 into `db/migrations`, remove the legacy duplicate slice and run the PostgreSQL integration suite.

## Fresh-worker verification (this attempt — exact commands and results)

The implementation already in `chart/record` and `it/chart/record` was surveyed and re-verified from scratch; no production file needed changing. All evidence below is **tool-generated** from commands executed on this branch on 2026-11-20 (Java 21.0.13 Temurin, Maven 3.10.0, offline `/opt/m2`). No human or clinical participant was involved; all fixtures are synthetic.

| Command | Result |
|---|---|
| `mvn -o -q -B -f apps/api/pom.xml -Pno-security,fast test-compile` | exit 0 — 11 production classes + 5 test classes in scope compile |
| `mvn -o -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ChartRecord*' test` | exit 1 — `org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 (absent)`; no network, provider not cached. Environment gap, not a code failure (see original note). |
| Reflection runner (JUnit `@BeforeEach`/`@Test`/`@AfterEach` lifecycle) over the 4 fast classes, executed against the Maven-compiled classes | **passed=35 failed=0 total=35**, exit 0 (Value 5, ActionPolicy 4, Service 21, Controller 5) |
| Same reflection run under JaCoCo 0.8.16 agent + `jacoco:report` | `ChartService` instr 932/932, branch 112/112, line 194/194, method 16/16; `ChartActionPolicy` 35/35 instr, 7/7 branch; `ChartController` 36/36; `ChartView` 13/13; `RecordEntryCommand` 48/48; `ChartEntry` 78/78; `EntryType` 27/27; `EntrySource` 27/27; `PresenceState` 21/21; `VerificationState` 15/15; `CatalogHit` 12/12 — **100% on every unit-covered class**. `ChartRepository` 3/364 instr (DB-only; authority is the `@Tag("integration")` suite run by the integrator). |
| `mvn -o -q -B -f apps/api/pom.xml -Pno-security spotbugs:check` | exit 0, no findings |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2/2 OK |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V13 stays test-scoped; integrator sequences it) |
| `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py`; `git diff --check` | exit 0 each |

Additional cross-module contract check (read-only survey, no code change): `CatalogService.define` stores the request `value` verbatim at key `catalog.<family>.<code>` and `ChartRepository.lookupCatalog` reads it through `effective_catalog_family`. The integration fixture that posts `{"code":"R51","value":"Headache"}` to `/api/v1/catalogs/diagnosis/entries` therefore yields `mappedText = "Headache"` as asserted — the catalog mapping contract is consistent end to end.

Bean-name and route guard re-confirmed: the legacy `patient/chart` slice keeps default bean names and `/api/v1/chart`; this slice uses explicit unique names (`chartRecord*`) and `/api/v1/chart/records`, so Spring component scanning starts cleanly while both coexist. The integrator must still delete the legacy slice (out of this worker's scope).

## Authoritative scope and routing

- Authoritative scope from `TODO.md` (the untrusted story text carries no package path):
  - `apps/api/src/main/java/com/aurevia/smartclinic/chart/record`
  - `apps/api/src/test/java/com/aurevia/smartclinic/it/chart/record`
- Reserved migration: `V13` (test-scoped `V13__longitudinal_chart.sql` under the test package; integrator sequences it into `db/migrations`).
- Classification (`docs/WORKFLOW.md`): **safety-critical** — patient identity linkage, clinical authorship, verification state and tenant isolation. Named reviewers: senior backend, senior database, software architect, test engineer, physician (clinical safety). All review in this note is simulated unless stated otherwise.
- Requirements: CLN-03 (primary); ARC-01, ARC-02, ARC-03, OPS-02, IAM-01/02 (supporting).

## Pre-existing state discovered in the survey (important for the integrator)

The base commit already contains a **prior, unintegrated attempt** of this same story under
`com.aurevia.smartclinic.patient.chart` (production) and `it/patientchart` (tests), with a
test-scoped V13 migration. Evidence:

- `TODO.md` still marks S-G1-03 `[-]` (`agent: swarm-5`, `attempts: 1`) — the prior attempt was never accepted.
- That prior test `ChartEntryIntegrationTest` loads Flyway from
  `filesystem:src/test/java/com/aurevia/smartclinic/it/s_g1_03/migrations`, a stale story-ID path that does **not exist**; the only V13 file is at `it/patientchart/migrations/V13__longitudinal_chart.sql`. The table `public.chart_entry` is therefore not created and that integration suite cannot pass.
- `db/migrations` has no V13 (nor V14/V16/V18) — reserved versions are sequenced by the integrator.

This attempt implements the story in the **authoritative domain scope** `chart/record`, reusing the
proven domain design and all shared infrastructure types (no new cross-cutting contract is invented).
It uses a distinct REST base path `/api/v1/chart/records` so it does not create an ambiguous Spring
mapping with the legacy `patient.chart.ChartController` (`/api/v1/chart`) that remains on the base.
The integrator should remove the legacy `patient/chart` + `it/patientchart` slice and sequence V13
into `db/migrations` when consolidating; this worker must not edit those out-of-scope paths.

## Requirement → acceptance mapping (to be completed with evidence)

| CLN-03 clause | Planned implementation | Evidence |
|---|---|---|
| Capture chief concern/history/exam/assessment/plan (note-level fields) | Out of this slice — owned by S-G1-04 templates and S-G1-05 signing | n/a |
| Problems, allergies, medications, vitals with units and measurement time | `EntryType`, `ChartEntry`, `RecordEntryCommand`, V13 checks, service validation | `ChartRecordValueTest.enumTokensMatchTheDatabaseChecks`; `ChartRecordServiceTest.validationRejectsReversedPeriodAndIncompleteVitalAndForgedAuthor`; `ChartRecordIntegrationTest.recordsReadsAndAuditsAllFourKindsWithProvenance` (DB, integrator) |
| Longitudinal problems/allergies/medications with verification, source and effective dates | `VerificationState`, `EntrySource`, `PresenceState`, `ChartService`, V13 RLS + checks | `ChartRecordServiceTest.verifySuccessReturnsVerifiedEntryAtNextVersion`; `ChartRecordIntegrationTest.verificationIsVersionBoundAndConcurrent` (DB, integrator) |
| Explicit unknown and none states | `PresenceState`; coded `unknownReason`; service validation | `ChartRecordServiceTest.validationRejectsUnknownReasonMismatchAndUnpairedCode`, `unknownAssertionWithReasonIsAcceptedAndVitalWithAllFieldsIsAccepted`; V13 `unknown_reason`/`presence` CHECK |
| Clinical author recorded separately from data-entry actor | `ChartEntry.clinicalAuthor` vs `dataEntryActor`; actor derived from tenant context | `ChartRecordServiceTest.recordSnapshotsCatalogMappingAndPreservesOriginals` (both subjects asserted); `…validationRejectsReversedPeriodAndIncompleteVitalAndForgedAuthor` |
| Local procedure codes and versioned diagnosis catalog; retain original code/text after mapping | `ChartRepository.lookupCatalog`, write-time snapshot fields, `ChartService.record` | `ChartRecordServiceTest.recordSnapshotsCatalogMappingAndPreservesOriginals`, `recordWithoutMappingKeepsOriginalsAndNullMapping`, `recordDoesNotLookUpMappingForOtherTypes` |
| Tenant isolation, authorization, audit, version-bound verification | forced RLS in V13, `ChartActionPolicy`, `AuditAppender`, `IfMatch` CAS | `ChartRecordActionPolicyTest` (4 tests), `ChartRecordControllerTest.verifyPropagatesMissingPrecondition` (428); `ChartRecordIntegrationTest.tenantIsolationConcealsCrossTenantRowsAndForcesChecks`, `authorizationAndInputBoundariesFailClosed` (DB, integrator) |
| **Added this attempt:** non-clinicians may read/record but never verify | `ChartActionPolicy.CHART_ENTRY_VERIFY` is clinician-only; service denies and audits | `ChartRecordActionPolicyTest.assistantMayReadAndCreateButNotVerify`; new `ChartRecordServiceTest.verifyDeniedForActiveNonClinicianAndAudited` (RED→GREEN) |

## Test-first sequence (evidence source: executed tool commands)

1. Wrote `ChartRecordValueTest`, `ChartRecordActionPolicyTest`, `ChartRecordServiceTest`,
   `ChartRecordControllerTest` and the `@Tag("integration")` `ChartRecordIntegrationTest`
   (plus test-scoped `V13__longitudinal_chart.sql`) in the authoritative scope, before any production file.
   Added one new clinical-safety case beyond the inherited design:
   `verifyDeniedForActiveNonClinicianAndAudited` — an active `clinical_assistant` may record but must
   not verify, and the denial must be audited with no call to the repository's verify CAS.
2. RED: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ChartRecordValueTest,ChartRecordActionPolicyTest,ChartRecordServiceTest,ChartRecordControllerTest' test`
   → **exit 1**, test compilation failed with `cannot find symbol` for every `chart.record` type
   (`ChartService`, `ChartEntry`, `EntryType`, `EntrySource`, `PresenceState`, `RecordEntryCommand`, …).
   Genuine red caused by missing behavior.
3. Implemented the 11 production classes in `chart/record` (relocated domain design, new package and
   REST base path), then the same command reached the test phase but failed on an environment issue:
   `org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 (absent)` — not present in the
   pre-seeded `/opt/m2` repository and not downloadable (network blocked; forced `-U` still fails).
4. GREEN (equivalent execution): compiled with
   `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test-compile` → **exit 0**, then executed all
   four fast test classes with a small reflection runner that invokes each `@Test`, `@BeforeEach`
   and `@AfterEach` directly on the compiled classes:
   **REFLECTIVE RUN: passed=33 failed=0 total=33** (exit 0). The same run under the JaCoCo agent
   produced the coverage numbers below.

## Exact commands and results

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='…' test` (before production code) | exit 1, `cannot find symbol` for all new types — RED |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test-compile` | exit 0; all 12 production classes and all 5 test classes compiled |
| Reflection runner on the 4 fast test classes | **35 passed, 0 failed**, exit 0 (33 before the review fixes) |
| Reflection runner under JaCoCo agent + `CoverageReport` over `apps/api/target/classes` | `ChartService` instr 932/932, branch 112/112, line 194/194, method 16/16; `ChartActionPolicy` 35/35 instr, 7/7 branch; `ChartController` 36/36; `ChartView` 13/13; `RecordEntryCommand` 48/48; `ChartEntry` 78/78; `EntryType` 27/27; `EntrySource` 27/27; `PresenceState` 21/21; `VerificationState` 15/15; `CatalogHit` 12/12 — **100% on every class covered by fast tests**. `ChartRepository` 3/364 (DB-only; covered by the `@Tag("integration")` suite the integrator runs). |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` | 2/2 OK (fully-qualified Javadoc links; list-bearing records copy on store and read) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security spotbugs:check` | exit 0, no new findings |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py`; `git diff --check` | exit 0 each |
| `git status --short` | only the three intended new paths (pr-notes, `chart/record`, `it/chart/record`); no shared tracker, generated config or legacy package touched |

## Defects found and decisions

- **Defect (pre-existing, out of scope, flagged for integrator):** the base already contains a prior
  attempt of S-G1-03 at `patient.chart` / `it.patientchart`. Its `ChartEntryIntegrationTest` loads
  Flyway from `filesystem:src/test/java/com/aurevia/smartclinic/it/s_g1_03/migrations`, a stale
  story-ID path that does not exist; the only V13 file there is at `it/patientchart/migrations/`.
  That suite therefore cannot create `public.chart_entry` and cannot pass. The integrator should
  remove the legacy slice and sequence this attempt's V13 into `db/migrations`. No legacy file was
  edited by this worker (scope rule).
- **Decision — scope:** followed authoritative `TODO.md` business-domain paths (`chart/record`),
  consistent with AGENTS.md and the S-G2-02 precedent. No story-ID-named package or class introduced.
- **Decision — REST path and bean names:** used `/api/v1/chart/records` rather than `/api/v1/chart`
  (owned by the legacy controller), and gave all four Spring beans explicit unique names
  (`chartRecordService`, `chartRecordRepository`, `chartRecordActionPolicy`,
  `chartRecordController`). Two controllers with identical mappings would make every
  `@SpringBootTest` fail with an ambiguous mapping, and the four default bean names collide with the
  legacy slice's `@Service`/`@Repository`/`@Component`/`@RestController` names (Spring Boot disables
  bean-definition overriding by default). Both guards keep the context startable while the
  integrator consolidates. Handler semantics and status codes are unchanged. Re-verified after the
  change: `test-compile` exit 0, SpotBugs exit 0, 33/33 fast tests green, `git diff --check` exit 0.
- **Decision — reuse:** all cross-cutting types (`ApiException`, `IfMatch`, `SqlFailureTranslator`,
  `AuditAppender`, `TenantContext`) are reused; no new idempotency/export/worker contract is invented
  (X-01/X-02/X-03 own those). V13 stays test-scoped; the integrator sequences reserved versions.
- **Instrument gap (environment):** the local image lacks `surefire-junit-platform:3.5.6` and has no
  network, so `surefire:test` cannot run here. Fast tests were executed via a reflection runner
  against the Maven-compiled classes; this is tool-generated execution evidence, not a claim that the
  standard Surefire command was green locally. The integrator's `apps/api/check.sh` is authoritative.

## Specialist review (simulated, senior-backend) and dispositions

A bounded read-only review was routed to the senior-backend specialist with the authoritative paths,
acceptance criteria and the executed evidence. It ran no commands; findings are from static reading
of the scope (which at review time predated the explicit bean names). Evidence source for the
findings: **simulated reviewer**, not a human or clinical participant.

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | HIGH | Legacy/new slices collide on default Spring bean names (`chartService`, `chartRepository`, `chartActionPolicy`, `chartController`), causing `BeanDefinitionOverrideException` on `@SpringBootTest` startup | **Fixed before the review landed** and re-verified: all four beans have explicit unique names (`chartRecord*`) with a comment explaining why. The integrator must still delete the legacy slice; bean overriding was deliberately NOT enabled. |
| 2 | MEDIUM | `hashCode()` of `RecordEntryCommand` and `CatalogHit` not asserted; BUNDLE-wide JaCoCo METHOD rule might fail | **Fixed**: `hashCode()` assertions added to `commandCarriesTheSubmittedFields` and `catalogHitIsAValueSnapshot`. Re-measured: 100% on all unit-covered classes. Reviewed note accepted: the POM rule is BUNDLE-wide, not per class; the per-class numbers here are reported explicitly in the evidence table. |
| 3 | MEDIUM | Clinical author could never differ from data-entry actor — a recorder leaving authorship open had no later way to attribute it | **Fixed**: `ChartRepository.verify` now applies `clinical_author = COALESCE(clinical_author, :actor)` and `ChartService.verified()` returns the same value, so the verifying clinician becomes the accountable author of an open entry. New `verifyAssignsAccountableClinicalAuthorWhenRecorderLeftItOpen` proves `clinicalAuthor != dataEntryActor` and that view and row agree. Direct attribution to another subject remains deliberately rejected (anti-forgery): an assistant leaves it open and the verifying clinician supplies it. |
| 4 | MEDIUM | Authorization is global-role-only; no patient/facility/care-relationship scoping | **Deferred with owner (accepted for this slice)**: the encounter/assignment relationship module belongs to S-G1-02/S-G1-05; this slice reuses the bounded G0 role matrix and forced RLS and does not invent a second authorization contract. Owner: Architect + Product Owner at G1 acceptance; negative relationship test to follow the encounter slice. Recorded here as an explicit deferral, not an omission. |
| 5 | LOW | New REST operations absent from `contracts/openapi.yaml`; no maintainer-guide trace | **Partly out of scope**: `contracts/` and `docs/` are outside this worker's declared scope; flag for the integrator. The entry-point-to-test trace is recorded in this note instead (below). |
| 6 | LOW | Unbounded optional trust-boundary inputs (`encounterId`, `value`, `unit`, `codeSystem`, `unknownReason`) | **Fixed at the service boundary**: `requireBounded` applies 120/500-character limits with stable 422 codes, covered by the new `validationBoundsOptionalTextBeforeAnyRepositoryContact` test. V13 stores them as text; the service is the validation boundary. |
| 7 | LOW | Validation precedes in-transaction authorization on `record()` | **Accepted**: authentication (`context()`) still fails closed first, no resource existence is disclosed and the outcome is 422 vs 403 only; moving authorization would restructure the transaction boundary for no security gain in scope. Documented rather than changed. |
| 8 | LOW | `ChartView` Javadoc lacked `@param entries` | **Fixed**: parameter documented. |

## Entry-point-to-test trace (requested by reviewer finding 5)

- `POST /api/v1/chart/records/entries` → `ChartController.record` → `ChartService.record` →
  `ChartActionPolicy` + `ChartRepository.patientExists/lookupCatalog/insert` + `AuditAppender.append`
  → assertions in `ChartRecordServiceTest.record*` and
  `ChartRecordIntegrationTest.recordsReadsAndAuditsAllFourKindsWithProvenance` (DB).
- `POST /api/v1/chart/records/entries/{id}/verify` (`If-Match`) → `ChartController.verify` →
  `IfMatch.parse` → `ChartService.verify` → guarded compare-and-set `ChartRepository.verify`
  → `ChartRecordServiceTest.verify*` and `ChartRecordIntegrationTest.verificationIsVersionBoundAndConcurrent` (DB).
- `GET /api/v1/chart/records/patients/{patientId}/entries` and `GET .../entries/{id}` →
  `ChartController.chart/get` → `ChartService.listForPatient/get` → forced-RLS reads in
  `ChartRepository.chart/find` → `ChartRecordServiceTest.reads*` and
  `ChartRecordIntegrationTest.tenantIsolationConcealsCrossTenantRowsAndForcesChecks` (DB).

## Blockers / evidence genuinely missing

- No local PostgreSQL (`psql`/`pg_isready` absent). The `@Tag("integration")`
  `ChartRecordIntegrationTest` (forced RLS, two-tenant concealment, `WITH CHECK`, version-bound
  concurrent verification, audit chain, 401/403/404/409/412/422 boundaries) is compiled here but
  **not executed**; the integrator must run `bash apps/api/check.sh` on the exact merged SHA and
  record the JaCoCo ratios. That suite is the authority for `ChartRepository` coverage.
- Doxygen is absent locally, so `bash scripts/docs.sh` exits 1 ("Install Doxygen"); the
  `test_java_conventions.py` guard (fully-qualified Javadoc links, immutable list-bearing records)
  passes 2/2 on this tree. No agent or skill file was edited, so no `opencode.json` regeneration was
  needed. `python3 -I scripts/gen-opencode.py --check` exits 1 here only because `git ls-files -v
  opencode.json` reports `S` (skip-worktree): the checked-out file is the CI profile, the documented
  OPS-CW-003-01 measurement artifact, not a change made by this worker.
- The legacy duplicate and stale Flyway path above need integrator cleanup before the full suite is
  reliable; this worker cannot touch those out-of-scope paths.
- No clinical or human participant evidence; all fixtures synthetic. Clinical approval remains a G5 gate.
