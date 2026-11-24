# BUILD-43 · Onboarding import: versioned manifest, mapping and dry run

PR #299, tested branch tip aaa945a095b624592c98a6f02bbdd371f63925e4.

Task: BUILD-43

Swarm worker 10. Local checks: passed. Agent result: ok.

# BUILD-43 — Onboarding import: versioned manifest, mapping and dry run

Status: complete. One story, test-first, synthetic data only. No dependency added;
no file outside the reserved scope changed.

## Requirements and acceptance mapping
- **OPS-08** (PROJECT_V1_SPEC.md line 141; operational contract in
  `docs/operations/s_g5_09/{onboarding-manifest,dry-run-and-batches,sign-off-and-provenance}.md`).
- Envelope + versioned manifest: `ImportManifest` (schemaVersion, requirement,
  `manifest_version`, source, `synthetic_only`); checked by `validateEnvelope`.
- Deterministic mapping: `FieldMapping` with `canonical_sha256`, `source_to_target`,
  `identity_fields` (issuer-scoped MRN), `ignored_fields`; canonical JSON/SHA-256
  identity keys in `OnboardingImportService.identityKey`/`canonical`/`sha256`.
- Dry run reports the five findings (`DryRunReport.Counts`, unsupported fields,
  duplicate risk, missing attachments, errors) and writes nothing: `dryRun` is a
  pure computation and `dryRunChangesNoRow` asserts the source records are unchanged.
- Provenance on every imported record + preserved historical signature, no new
  attestation: `ImportedRecord` (`imported=true`, `new_attestation=false`) produced
  by `toImportedRecords`; V90 table `import_record_provenance` enforces the same
  invariants with CHECK constraints and append-only triggers. Controlled batches,
  hash reconciliation and authorized sign-off are the separate BUILD-44 story.
- Acceptance tests: dry run changes no row (`dryRunChangesNoRow`), same input gives
  the same report (`sameInputGivesSameReportRegardlessOfJsonKeyOrder`, canonical
  hashing independent of map insertion order), unsupported field is listed
  (`unsupportedFieldIsListedAndIgnoredFieldIsNot` + HTTP `ImportControllerTest`),
  historical signature stays marked as imported
  (`historicalSignatureStaysMarkedAsImportedWithoutNewAttestation`).

## Entry-point-to-test trace
`POST /api/v1/tenant/imports/dry-run` -> `ImportController.dryRun` (injected
`OnboardingImportService`) -> `OnboardingImportService.dryRun` -> assertions in
`ImportControllerTest` (HTTP-level) and `OnboardingImportServiceTest` (behavior).
No commit endpoint is exposed in this story.

## Test evidence
Commands run (final lines / results):
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=OnboardingImportServiceTest,ImportControllerTest test`
  -> EXIT 0. Surefire: `OnboardingImportServiceTest tests=23 errors=0 skipped=0 failures=0`,
  `ImportControllerTest tests=1 errors=0 skipped=0 failures=0`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report` -> EXIT 0.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check`
  -> EXIT 0 (no SpotBugs findings; records freeze lists/maps with defensive copies).
- `python3 -I -m unittest discover -s scripts/tests -q` -> Ran 436 tests, 3 failures.
  The 3 failures are the documented CI-profile `opencode.json` false positive
  (`test_check_pack*`: "opencode.json is stale / out of date"), not caused by this story:
  `opencode.json` is flagged `S` (skip-worktree) in this worker and is the CI profile
  copied by `agent-run.sh`; HEAD truth is unchanged. See MEMORY.md decision 2026-11-19
  "inside a worker treat worktree config checks as CI-profile results, not HEAD truth".
- `python3 -I scripts/check-names.py` -> 0; `python3 -I scripts/check-docs.py` -> 0;
  `python3 -I scripts/check-secrets.py` -> 0; `python3 -I scripts/check-wiring.py` -> 0
  (only stale-baseline notes, no new findings);
  `python3 -I scripts/check-migrations.py --require-baseline` -> 0.
- `bash db/check.sh` -> EXIT 0: "db: migrations, forced RLS, role separation,
  isolation, audit, append-only staff history and versioned config verified"
  (V90 migration applied against real PostgreSQL 18.6).
- `bash scripts/check.sh` -> EXIT 1 at `check-pack: opencode.json is stale` — the same
  pre-existing skip-worktree CI-profile artifact above; it stops before the module
  verify stage and is unrelated to the changed files. All in-scope checks pass.

## Coverage
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=OnboardingImportServiceTest,ImportControllerTest test jacoco:report`,
then read `apps/api/target/site/jacoco/jacoco.xml` for package
`com/aurevia/tenant/importing`:
INSTRUCTION 1079/1079, BRANCH 124/124, LINE 195/195, METHOD 45/45, CLASS 13/13 —
0 missed on every counter for all 13 classes. Every new branch and failure path has
a named test:
- envelope (schema/requirement/version incl. null/source/synthetic) -> `envelopeViolationsBecomeErrors`,
  `nullManifestVersionIsAnError`, `cleanManifestProducesZeroFindings`.
- unsupported mapped/ignored/unmapped + dedup/sort -> `unsupportedFieldIsListedAndIgnoredFieldIsNot`,
  `unsupportedFieldsAreDeduplicatedAndSorted`.
- required field present/blank/absent + null identity source -> `blankRequiredFieldIsAnError`,
  `missingRequiredFieldIsAnError`, `blankAndMissingMappedTargetsAreErrors`.
- source_id valid/blank -> `invalidSourceIdIsAnError`; empty dataset -> `emptyRecordsIsAnError`.
- attachment valid, blank key, short/null checksum, bad type, null present, missing
  -> `invalidAttachmentsAreErrorsAndMissingAttachmentsAreReported`.
- signature null/blank issuer/blank signed_at/bad checksum/valid
  -> `historicalSignatureValidationCoversEveryGuard`, `provenanceViolationsBecomeErrors`.
- mapping deterministic/strategy/duplicate+blank+missing targets, identity empty/
  missing mrn/missing mrnIssuer/unmapped target
  -> `mappingViolationsBecomeErrors`, `blankAndMissingMappedTargetsAreErrors`,
  `emptyAndIncompleteIdentityFieldsAreErrors`, `missingIdentityTargetStillComputesAKey`.
- provenance null/blank source/flags -> `dryRunWithNullProvenanceReportsProvenanceErrors`,
  `provenanceViolationsBecomeErrors`.
- duplicate identity group / single -> `duplicateRiskListsEveryMemberOfTheIdentityGroup`,
  `cleanManifestProducesZeroFindings`.
- SHA-256 unavailable (catch) -> `missingDigestAlgorithmFailsClosed` (injected `DigestFactory`).
- imported-record projection with/without provenance -> `historicalSignatureStaysMarkedAsImportedWithoutNewAttestation`,
  `nullProvenanceUsesNullSourceValues`.
- HTTP adapter -> `ImportControllerTest.dryRunReturnsFindingsWithoutACommitEndpoint`.
The full `mvn verify` / JaCoCo gate and integration suites are left to the integrator
and Delivery as instructed; targeted tests for every touched class were run here.

## Findings
- No product defect found. `scripts/check.sh` and the 3 `test_check_pack` failures are
  the environment's skip-worktree CI-profile `opencode.json` artifact (confirmed with
  `git ls-files -v opencode.json` = `S`, and differing md5 between HEAD and worktree);
  they reproduce independently of this change.
- Design decision: BUILD-43 is the pure dry-run/evaluation slice; controlled commits,
  batch reconciliation and sign-off (BUILD-44) are intentionally not implemented. The
  V90 table exists so BUILD-44 has a durable, RLS-protected, append-only provenance
  target. No `InMemory*` fake or `*Store` interface was added in this slice, avoiding
  a new wiring finding; the domain service is referenced by the injected controller.

## Lessons for workers
- In a cloud worker, `check-pack.py` / `test_check_pack` failures are usually the CI
  `opencode.json` copied under skip-worktree (`git ls-files -v` shows `S`); compare
  `git show HEAD:opencode.json` before treating it as real staleness. Do not regenerate
  or commit `opencode.json` (outside worker scope).
- JaCoCo requires the failure side of a `catch` to be tested: inject a digest factory
  (as `EvidenceHash` does) instead of calling `MessageDigest.getInstance` inline.
- Java records that hold `List`/`Map` need a compact constructor with `List.copyOf`/
  `Map.copyOf` plus an overridden accessor returning a copy, or SpotBugs flags
  EI_EXPOSE_REP; every collection accessor then needs a
