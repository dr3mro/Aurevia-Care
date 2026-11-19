# S-G1-08 — Clinical dictionaries, diagnosis/procedure catalogs and RTL mixed-script operation (PRD-05, UX-01)

## Requirement IDs and acceptance mapping

Primary: **PRD-05** (English and Arabic operation, one market per deployment), **UX-01** (RTL,
local-name display, mixed-script clinical text, UTC instants with IANA zones, civil dates,
preserved language/units in exports). Supporting: **CLN-03** (versioned market diagnosis
catalog, retain original code and text after mapping, explicit unknown/none states),
**CLN-04** (versioned field dictionaries, governance-approved requiredness, historical field
meanings), **ADM-01** (named approval with effective data), **ARC-01/ARC-02** (domain keys and
tenant isolation — deferred to the persistence/policy layers in this kernel slice),
**OPS-02** (audit — likewise delegated).

| Acceptance clause | Implementation | Executed evidence |
|---|---|---|
| Versioned market diagnosis/procedure catalog; mapping never replaces original code or text (CLN-03) | `DiagnosisCode`, `DiagnosisCode.withMapping`, `DiagnosisCatalogService`, `VersionStore` | `ClinicalCatalogAcceptanceTest.marketMappingAddsAnEntryWithoutRewritingTheOriginalCodeOrText`, `searchReadsOnlyTheApprovedVersionAndNeverMutatesTheStoredSpellings`; existing `DiagnosisCatalogTest` |
| Versioned clinical field dictionaries; governance approves fields and requiredness; historical meanings preserved (CLN-04, ADM-01) | `DictionaryField`, `Requiredness`, `Approval`, `CatalogVersion`, `ClinicalDictionaryService` | `ClinicalCatalogAcceptanceTest.governanceApprovalRequiresADistinctNamedReviewerAndOnlyApprovedVersionsAreEffective`; existing `ClinicalDictionaryTest` |
| English/Arabic direction and local-name display, mixed-script isolation without altering stored text (PRD-05, UX-01) | `UiLocale`, `Direction`, `RunDirection`, `LocalizedName`, `LocalizedText`, `MixedScriptRun`, `MixedScriptSupport` | `ClinicalCatalogAcceptanceTest.rtlDirectionAndLocalNameDisplayAreDerivedFromTheInterfaceLocale`, `mixedScriptRunIsIsolationMetadataAndKeepsStoredCodePointsExactly`; existing `LocalizationTest`/`MixedScriptSupportTest` |
| UTC instants with IANA zone across DST, civil dates without zone shift, source units/language preserved in measurement and export (UX-01, PRD-05) | `MixedScriptSupport.instantWithZone`, `civilDate`, `formatMeasurement`, `export`, `LocalizedExport` | `ClinicalCatalogAcceptanceTest.utcInstantsKeepTheirIdentityAcrossDstAndCivilDatesNeverShift`, `measurementAndExportPreserveSourceUnitsAndLanguageWithoutConversion` |
| **This attempt:** entries supplied at a trust boundary are validated and cannot cause an unhelpful NPE or a partially allocated version | `VersionStore.define` null-entry guard, shared by both services | New red→green `ClinicalCatalogAcceptanceTest.defineRejectsNullDictionaryEntryWithoutAllocatingAVersion` and `defineRejectsNullCatalogEntryWithoutAllocatingAVersion` |

Supporting IDs traced only at the domain-kernel boundary: server-side authorization, tenant
RLS, persistence, idempotency and audit are owned by the controller/policy/database layers,
not by this in-memory kernel. No real patient data or credentials appear anywhere.

## Scope and files changed

- `apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/VersionStore.java` — added an
  explicit null-entry check in `define` before the identity function is applied, and documented it
  on the `@throws` clause. Both `ClinicalDictionaryService` and `DiagnosisCatalogService` share this
  guard, so the fix applies to dictionaries and catalogs symmetrically.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/catalog/dictionary/ClinicalCatalogAcceptanceTest.java`
  — new acceptance-level suite (9 tests) in the declared test scope, covering the CLN-03/CLN-04,
  PRD-05 and UX-01 backend data contracts and the new boundary validation.
- `.agent/pr-notes/S-G1-08.md` — this file.
- No other file, shared tracker, generated config or migration was edited. Reserved **V18 is
  unused**, consistent with sibling domain-kernel stories (the store is a deterministic in-memory
  adapter; DB RLS/uniqueness constraints for a production adapter are a separate persistence
  story). The integrator runs the database and integration gates.

The implementation already present at the base (16 production classes in `catalog/dictionary`
plus 26 unit tests in `it/catalog`) was inherited and reused, not duplicated. Those tests sit in
`it/catalog`, outside this worker's declared test scope, so no attempt was made to move or edit
them; the new suite is additive and lives in the declared `it/catalog/dictionary` path.

## Entry-point-to-test trace

`ClinicalCatalogAcceptanceTest` calls the public domain services directly:
`DiagnosisCatalogService.define` → `VersionStore.define` (new null guard) → `CatalogVersion`
immutable entries → `VersionStore.approve` → `DiagnosisCatalogService.search`/`effective`/`history`;
`ClinicalDictionaryService.define` → the same fold. Localization flows call
`MixedScriptSupport.directionFor`, `displayLocalName`, `annotateRun`, `formatMeasurement`,
`export`, `instantWithZone` and `civilDate`. Each assertion is in the same test class, so the
happy path, validation failure, governance denial, DST instants and exact mixed-script round trip
are each traceable from public entry point to assertion.

## Test-first sequence (tool evidence)

1. Added `ClinicalCatalogAcceptanceTest` with 9 tests, including the two null-entry tests, before
   touching production code.
2. `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ClinicalCatalogAcceptanceTest test`
   → **exit 1, Tests run: 9, Failures: 2**, both failures `expected IllegalArgumentException but
   was NullPointerException` from `VersionStore.define` (genuine red).
3. Added the null-entry guard in `VersionStore.define`.
4. Same targeted command → **exit 0, Tests run: 9, Failures: 0, Errors: 0**.
5. Full fast suite and coverage re-run green (see below).

## Exact commands run and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ClinicalDictionaryTest,DiagnosisCatalogTest,LocalizationTest,MixedScriptSupportTest' test` | exit 0; inherited 26 tests green (baseline established before any edit) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ClinicalCatalogAcceptanceTest test` | red 9/2 failures → green 9/0 after the fix |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast clean test jacoco:report` | **exit 0**; Surefire **1694 tests, 0 failures, 0 errors, 0 skipped** (was 1685 before the 9 new tests). JaCoCo CSV: all **16** `catalog.dictionary` classes have **0 missed** instructions (1100 covered), branches (201 covered), lines (213 covered) and methods (161 covered) — 100% at instrument/line/branch/method/class level |
| `mvn -q -B -f apps/api/pom.xml -Pno-security spotbugs:check` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests OK — list-bearing records copy on store and read; Javadoc links fully qualified |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 each (V18 unused; no migration added) |
| `git diff --check` | exit 0 |

## Defects found and decisions

- **Defect fixed (boundary validation):** `VersionStore.define` dereferenced a null entry through
  `identity.apply(entry)` and threw `NullPointerException`. A caller could therefore receive an
  unstructured runtime error and no clear reason the command failed. It now throws
  `IllegalArgumentException("entry must not be null")` before allocating a version, so no version
  is consumed on the failed command. Both services inherit the guard. Covered by the two new
  red→green tests.
- **No other production defect found** in the 16 seeded classes relative to CLN-03/CLN-04/PRD-05/
  UX-01 after reviewing every value object, the version fold and the localization kernel. The
  domain kernel intentionally omits tenancy, authorization, persistence and audit (documented on
  `VersionStore`); this matches sibling in-memory kernel slices and is not reported as covered.
- **Decision — in-memory store and unused V18:** no database migration was added. The reserved V18
  is not needed because this slice is a deterministic domain kernel with no persistence adapter;
  tenant RLS and uniqueness would belong to the later persistence story. See sibling stories where
  reserved versions are likewise unused (S-G1-04/V14, S-G1-06/V16).
- **Decision — test scope:** the authoritative `TODO.md`/AGENTS.md business-domain paths were used.
  The new tests are in `it/catalog/dictionary` as the dispatch scope requires; no story/task-ID
  package or class name was introduced.
- **Scope/classification:** cross-boundary domain data contract (docs/WORKFLOW.md) — one bounded
  senior-backend review plus the executed gates; no clinical enablement is claimed.

## Specialist review outcome (simulated senior-backend reviewer)

A bounded senior-backend review inspected the new test, the `VersionStore.define` change and all
context classes; it executed **read-only inspection only** (no Maven/JUnit/SpotBugs available to the
reviewer). It confirmed the red/green fix is correct, allocates no version on failure, breaks no
existing behavior, and that the tests are deterministic and independent (explicit `Locale.ROOT`,
`ZoneId.of`, tolerant fall-back assertion). It found **no blocking defect** in the slice.

| Finding | Severity | Disposition |
|---|---|---|
| F-1: class-level `@return this record` and empty `@param` descriptions on pre-existing records/enums (`DiagnosisCode`, `DictionaryField`, `Approval`, `LocalizedText`, `LocalizedName`, `MixedScriptRun`, `LocalizedExport`, `Direction`, `RunDirection`, `UiLocale`) | Low, pre-existing, not introduced by this diff | **Deferred.** These files predate this change; CODING_STYLE.md keeps unrelated cleanup out of feature diffs. No Javadoc doclint plugin runs today, and `python3 -I scripts/check-docs.py` passes. Doxygen is not installed locally, so the integrator's `docs.sh` remains authoritative before any separate hygiene story. No behavior change, so no risk to this PR's gates. |
| F-2: `Approval.approvedAt` is unvalidated text and `VersionStore.effective()` ignores it, so a future-dated approval is effective immediately | Low, pre-existing, layering | **Deferred by design.** This is a deterministic domain kernel; effective dating is enforced by the V5 persistence/policy fold (`effective_config()` selects entries with `effective_from <= now()`), as recorded in the maintainer guide and PROJECT.md. Validating ISO-8601 and injecting a clock here would duplicate a rule owned by the database layer and change the public record contract used by the inherited tests. A production adapter must enforce it; no new story is needed now. |
| F-3: the catalog null-entry test did not assert `effective() == null` symmetrically with the dictionary test | Info, in this diff | **Fixed.** Added `assertNull(service.effective(CATALOG_KEY))`; targeted and full fast suites re-run green (1694 tests), JaCoCo unchanged at 100%. |

Evidence source for the review: simulated specialist (static read-only inspection); all gate results
in this note were re-executed locally by the primary agent with tools. No simulated-user or human
clinical evidence.

## Evidence limitations / blockers (honest report)

1. **No database or integration tests run here** by instruction — the integrator runs
   `bash apps/api/check.sh` on the merged SHA with PostgreSQL. The new tests are plain JUnit domain
   tests and need no database, so they ran in the fast tier. JaCoCo evidence above is from the fast
   tier with the integration group excluded; the integrator's full-tier coverage is authoritative.
2. **Doxygen is not installed locally**, so `bash scripts/docs.sh` exits with "Install Doxygen to
   build the developer reference." `python3 -I scripts/check-docs.py` (Markdown links, requirement
   IDs) passes; the integrator's `integrator-gate.sh` runs Doxygen.
3. **`opencode.json` worktree artifact:** `git ls-files -v opencode.json` shows `S` (skip-worktree)
   and the worktree file differs from `git show HEAD:opencode.json`, so `gen-opencode.py --check`
   and `check-pack.py` fail locally. This is the documented CI-profile artifact (BUGS
   OPS-CW-003-01, MEMORY.md), not HEAD truth; the file was not edited.
4. **No simulation or human evidence:** the specialist review is a static, simulated review; no
   simulated user exercise and no human or clinical participant evidence was produced. Site
   clinical/privacy approval for real use remains a G5 gate.
5. All evidence above is tool-generated and synthetic; no PHI, real identifiers or credentials
   were used.
