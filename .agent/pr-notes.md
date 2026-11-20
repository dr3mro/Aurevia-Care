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
| Entries supplied at a trust boundary are validated and cannot cause an unhelpful NPE or a partially allocated version (CLN-03/04) | `VersionStore.define` null-entry guard, shared by both services | `ClinicalCatalogAcceptanceTest.defineRejectsNullDictionaryEntryWithoutAllocatingAVersion` and `defineRejectsNullCatalogEntryWithoutAllocatingAVersion` (prior attempt) |
| **This attempt:** malformed time/zone/civil-date inputs at the trust boundary are rejected with the domain error contract, not a leaked `java.time` exception (UX-01, PRD-05) | `MixedScriptSupport.instantWithZone` and `civilDate` catch `DateTimeException` and rethrow `IllegalArgumentException` with a value-specific message and preserved cause | New red→green `ClinicalCatalogAcceptanceTest.malformedTimeAndZoneInputsAreRejectedWithDomainErrors` |

Supporting IDs traced only at the domain-kernel boundary: server-side authorization, tenant
RLS, persistence, idempotency and audit are owned by the controller/policy/database layers,
not by this in-memory kernel. No real patient data or credentials appear anywhere.

## Scope and files changed

- `apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/MixedScriptSupport.java`
  — **this attempt:** `instantWithZone` now parses the UTC instant and IANA zone separately
  inside `DateTimeException` handlers and rethrows `IllegalArgumentException` with distinct
  messages and the original cause; `civilDate` does the same for malformed ISO dates. Javadoc
  `@throws` updated from "missing" to "missing or malformed". Happy-path logic, DST rendering
  and civil-date identity are unchanged.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/catalog/dictionary/ClinicalCatalogAcceptanceTest.java`
  — new acceptance-level suite (now 10 tests) in the declared test scope. This attempt added
  `malformedTimeAndZoneInputsAreRejectedWithDomainErrors` (three boundary assertions).
- `apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/VersionStore.java`
  — prior attempt: explicit null-entry check in `define` before the identity function is
  applied, shared by both services.
- `.agent/pr-notes/S-G1-08.md` — this file.
- No other file, shared tracker, generated config or migration was edited. Reserved **V18 is
  unused**: this slice is a deterministic in-memory domain kernel with no persistence adapter;
  DB RLS/uniqueness constraints belong to a separate persistence story. The integrator runs the
  database and integration gates.

The implementation already present at the base (16 production classes in `catalog/dictionary`
plus 26 unit tests in `it/catalog`) was inherited and reused, not duplicated. Those tests sit in
`it/catalog`, outside this worker's declared test scope, so they were not moved or edited; the
new suite is additive and lives in the declared `it/catalog/dictionary` path.

## Entry-point-to-test trace

`ClinicalCatalogAcceptanceTest` calls the public domain services directly:
`DiagnosisCatalogService.define` → `VersionStore.define` → `CatalogVersion` immutable entries →
`VersionStore.approve` → `DiagnosisCatalogService.search`/`effective`/`history`;
`ClinicalDictionaryService.define` → the same fold. Localization flows call
`MixedScriptSupport.directionFor`, `displayLocalName`, `annotateRun`, `formatMeasurement`,
`export`, `instantWithZone` and `civilDate`. Each assertion is in the same test class, so the
happy path, validation failure, governance denial, DST instants, exact mixed-script round trip
and malformed-input rejection are each traceable from public entry point to assertion.

## Test-first sequence (tool evidence)

**Prior attempt:** added the 9-test acceptance suite before touching production code; the two
null-entry tests were red (`NullPointerException` from `VersionStore.define`), then green after
the guard.

**This attempt:**

1. Added `malformedTimeAndZoneInputsAreRejectedWithDomainErrors` to
   `ClinicalCatalogAcceptanceTest` (10 tests total) before changing production code.
2. `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ClinicalCatalogAcceptanceTest test`
   → **BUILD FAILURE, Tests run: 10, Failures: 1**. The failure was
   `Unexpected exception type thrown, expected: <java.lang.IllegalArgumentException> but was:
   <java.time.format.DateTimeParseException>` from `instantWithZone("not-an-instant", "UTC")`
   — a genuine red caused by missing boundary handling, not by a test defect.
3. Added the `DateTimeException` handling in `MixedScriptSupport` (instant, zone, civil date).
4. Same targeted command → **BUILD SUCCESS, Tests run: 10, Failures: 0, Errors: 0**.
5. Full fast suite with JaCoCo, SpotBugs, conventions, policy checks and `git diff --check`
   re-run green (see below).

## Exact commands run and results

Evidence source for this attempt: executed tool commands by the primary agent (not the reviewer).

| Command | Result |
|---|---|
| `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ClinicalDictionaryTest,DiagnosisCatalogTest,LocalizationTest,MixedScriptSupportTest,ClinicalCatalogAcceptanceTest' test` (baseline, before edit) | BUILD SUCCESS; **35 tests**, 0 failures/errors (7+9+7+5+7) |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ClinicalCatalogAcceptanceTest test` (after new test, before fix) | **BUILD FAILURE; Tests run: 10, Failures: 1** — `DateTimeParseException` leaked at line 207 |
| Same targeted command (after fix) | **BUILD SUCCESS; Tests run: 10, Failures: 0, Errors: 0** |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast clean test jacoco:report` | **BUILD SUCCESS; Tests run: 1761, Failures: 0, Errors: 0, Skipped: 0** |
| JaCoCo CSV inspection — `apps/api/target/site/jacoco/jacoco.csv`, package `com.aurevia.smartclinic.catalog.dictionary` | all **16 classes** have **0** missed instructions, branches, lines, complexity and methods. `MixedScriptSupport`: 179 instructions / 38 branches / 40 lines / 27 complexity / 8 methods covered, 0 missed — **100%** |
| `mvn -B -f apps/api/pom.xml -Pno-security spotbugs:check` | BUILD SUCCESS |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests OK — list-bearing records copy on store and read; Javadoc links fully qualified |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 each (V18 unused; no migration added) |
| `git diff --check` | exit 0 |

## Defects found and decisions

- **Defect fixed (this attempt — boundary error contract):** `MixedScriptSupport.instantWithZone`
  called `Instant.parse` and `ZoneId.of` directly, and `civilDate` called `LocalDate.parse`
  directly. Malformed input therefore escaped as `java.time.format.DateTimeParseException` or
  `ZoneRulesException` instead of the `IllegalArgumentException` used by every other method in
  the class. The pre-existing `MixedScriptSupportTest` (line 137) even used the loose
  `assertThrows(RuntimeException.class, ...)` for an invalid zone, confirming the gap. Each parse
  is now wrapped and translated to `IllegalArgumentException` with a message identifying the
  bad value, preserving the cause. Covered by the new red→green test.
- **Defect fixed (prior attempt — boundary validation):** `VersionStore.define` dereferenced a
  null entry through `identity.apply(entry)` and threw `NullPointerException`; it now throws
  `IllegalArgumentException("entry must not be null")` before allocating a version, so no
  version is consumed on the failed command. Both services inherit the guard.
- **No other production defect found** in the 16 seeded classes relative to CLN-03/CLN-04/PRD-05/
  UX-01. The domain kernel intentionally omits tenancy, authorization, persistence and audit
  (documented on `VersionStore`); this matches sibling in-memory kernel slices and is not
  reported as covered.
- **Decision — in-memory store and unused V18:** no database migration was added. Tenant RLS and
  uniqueness would belong to the later persistence story. See sibling stories where reserved
  versions are likewise unused (S-G1-04/V14, S-G1-06/V16).
- **Decision — test scope:** the authoritative `TODO.md`/AGENTS.md business-domain paths were used.
  The new tests are in `it/catalog/dictionary` as the dispatch scope requires; no story/task-ID
  package or class name was introduced. The minor reviewer suggestion to tighten the loose
  `RuntimeException` assertion in `MixedScriptSupportTest` was **not** applied because that file
  lives in `it/catalog`, outside this worker's declared test scope; the new in-scope test already
  asserts the specific domain error.
- **Scope/classification:** cross-boundary domain data contract (docs/WORKFLOW.md) — one bounded
  senior-backend review plus the executed gates; no clinical enablement is claimed.

## Specialist review outcome (simulated senior-backend reviewer)

A bounded senior-backend review inspected the new test, the `MixedScriptSupport` change and
cross-referenced sibling `DateTimeException` handling. It executed **static read-only inspection
only** (no Maven/JUnit/SpotBugs). It confirmed the change is correct, preserves the DST spring-
forward/fall-back and civil-date contracts, catches the right exception parent (`DateTimeException`
covers both `DateTimeParseException` and `ZoneRulesException` without swallowing unrelated
failures), keeps the cause, and that all three new handlers are covered. It found **no blocking
defect**. JaCoCo 100% was reasoned by the reviewer and **observed** by the primary agent (table above).

| Finding | Severity | Disposition |
|---|---|---|
| Change correct; DST and civil-date contracts preserved; cause chaining retained | Info | Accept |
| Three new catch handlers covered by the added test; guards/happy paths covered by existing tests | Info | Accept; 100% observed via JaCoCo CSV |
| `@throws IllegalArgumentException` now matches actual behavior for both methods | Info | Accept |
| No story/task-ID package, class or method naming | Info | Accept |
| `MixedScriptSupportTest.java:137` still asserts `RuntimeException.class` for an invalid zone | Minor, non-blocking | Not changed: file is outside declared test scope; the in-scope acceptance test pins the specific domain error |

Earlier review findings from the prior attempt (F-1 pre-existing Javadoc `@return`/`@param`
wording and F-2 `Approval.approvedAt` effective dating) remain deferred by design: those files
predate this change, CODING_STYLE.md keeps unrelated cleanup out of feature diffs, and effective
dating is enforced by the V5 persistence fold, not duplicated in this kernel. F-3 (symmetric
`effective() == null` assertion) was fixed in the prior attempt.

Evidence source for reviews: simulated specialist (static read-only inspection); all gate
results in this note were re-executed locally by the primary agent with tools. No simulated-user
or human clinical evidence.

## Evidence limitations / blockers (honest report)

1. **No database or integration tests run here** by instruction — the integrator runs
   `bash apps/api/check.sh` on the merged SHA with PostgreSQL. The new tests are plain JUnit
   domain tests and need no database, so they ran in the fast tier. JaCoCo evidence above is from
   the fast tier with the integration group excluded; the integrator's full-tier coverage is
   authoritative.
2. **Doxygen is not installed locally** (`command -v doxygen` → not installed), so
   `bash scripts/docs.sh` cannot be run here. `python3 -I scripts/check-docs.py` (Markdown links,
   requirement IDs) passes; the integrator's `integrator-gate.sh` runs Doxygen.
3. **`opencode.json` worktree artifact:** `git ls-files -v opencode.json` shows `S` (skip-worktree)
   and the worktree file differs from `git show HEAD:opencode.json`, so `gen-opencode.py --check`
   and `check-pack.py` would judge the CI profile, not HEAD truth. This is the documented
   CI-profile artifact (BUGS OPS-CW-003-01, MEMORY.md), not a change made here; the file was not
   edited.
4. **No simulation or human evidence:** the specialist review is a static, simulated review; no
   simulated user exercise and no human or clinical participant evidence was produced. Site
   clinical/privacy approval for real use remains a G5 gate.
5. All evidence above is tool-generated and synthetic; no PHI, real identifiers or credentials
   were used.

---

## Attempt 4 (this worker) — fail-closed approval and null-entry boundary

This worker resumed the `[-]` entry on branch `swarm/S-G1-08-123` at squashed base
`e5665c8` (0 files changed vs `main`). All work from attempts 1–3 above was inherited
in that base and reused unchanged; no package, class or test was duplicated. The
kernel was already complete and green, so this attempt looked for a remaining real
defect at the same trust-boundary defect class the earlier attempts fixed.

### Additional acceptance mapping (new in this attempt)

| Acceptance clause | Implementation | Executed evidence |
|---|---|---|
| A governance decision must be a **named approval**: a missing approval must never silently produce an "approved-looking" version (CLN-04, ADM-01, OPS-02 fail-closed) | `CatalogVersion.approve` now rejects a null `Approval` with `IllegalArgumentException("approval is required")` | `ClinicalCatalogAcceptanceTest.approvingAVersionRequiresANamedApprovalAndFailsClosed` (red → green) |
| A saved version must have entries; a null entries list must be rejected with the domain error contract rather than a leaked `NullPointerException` (CLN-04 historical-meaning preservation) | `CatalogVersion` compact constructor now checks `entries == null` before `List.copyOf` | `ClinicalCatalogAcceptanceTest.versionRejectsNullEntriesWithoutLeakingANullPointerException` (red → green) |

### Scope and files changed in this attempt

- `apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/CatalogVersion.java`
  — null-entry guard in the compact constructor and null-approval guard in `approve`;
  Javadoc `@throws` updated for both. Happy paths unchanged.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/catalog/dictionary/ClinicalCatalogAcceptanceTest.java`
  — two new in-scope acceptance tests (12 tests total).
- `.agent/pr-notes/S-G1-08.md` — this appended section.
- No other file, shared tracker, generated config or migration was edited. Reserved
  **V18 remains unused**: this is a deterministic in-memory domain kernel with no
  persistence adapter; database RLS/uniqueness belong to a persistence story.

### Entry-point-to-test trace (this attempt)

`ClinicalCatalogAcceptanceTest` → `new CatalogVersion<>(...)` (public constructor) →
null-entry guard → `IllegalArgumentException`; → `CatalogVersion.approve(null)` →
null-approval guard → `IllegalArgumentException`. Both assertions live in the same
in-scope test class, so each failure path is traceable from public entry point to
assertion. The success path (`approve(namedApproval)` → `isApproved()` and unchanged
draft) is covered by `ClinicalDictionaryTest.catalogVersionIsValidatedAndImmutable`
and `ClinicalCatalogAcceptanceTest.governanceApprovalRequiresADistinctNamedReviewer...`.

### Test-first sequence (tool evidence, this attempt)

1. Baseline `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ClinicalDictionaryTest,DiagnosisCatalogTest,LocalizationTest,MixedScriptSupportTest,ClinicalCatalogAcceptanceTest' test`
   → BUILD SUCCESS, 36 tests, 0 failures/errors.
2. Added the two failing tests before touching production code.
3. Same targeted command with `-Dtest=ClinicalCatalogAcceptanceTest` → **BUILD FAILURE,
   Tests run: 12, Failures: 2**:
   `approvingAVersionRequiresANamedApprovalAndFailsClosed` — "Expected
   java.lang.IllegalArgumentException to be thrown, but nothing was thrown"
   (silent unapproved copy); `versionRejectsNullEntriesWithoutLeakingANullPointerException`
   — "expected: <java.lang.IllegalArgumentException> but was: <java.lang.NullPointerException>"
   from `List.copyOf`. Both are genuine missing behaviors, not test defects.
4. Added the two guards in `CatalogVersion`.
5. Same targeted suite → BUILD SUCCESS, 38 tests (12 acceptance), 0 failures/errors.

### Exact commands run and results (this attempt)

Evidence source: executed tool commands by the primary agent.

| Command | Result |
|---|---|
| `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ClinicalDictionaryTest,DiagnosisCatalogTest,LocalizationTest,MixedScriptSupportTest,ClinicalCatalogAcceptanceTest' test` (baseline) | BUILD SUCCESS; 36 tests, 0 failures/errors |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ClinicalCatalogAcceptanceTest test` (red) | BUILD FAILURE; 12 tests, 2 failures — causes above |
| Same targeted five-class command (green) | BUILD SUCCESS; 38 tests, 0 failures/errors |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast clean test jacoco:report` | BUILD SUCCESS; **1782 tests**, 0 failures/errors/skipped |
| JaCoCo CSV inspection — all 16 classes in `com.aurevia.smartclinic.catalog.dictionary` | 0 missed instructions, branches, lines, complexity and methods. `CatalogVersion`: 72 instructions / 12 branches / 14 lines / 10 complexity / 4 methods covered, 0 missed — **100%** |
| `mvn -B -f apps/api/pom.xml spotbugs:check` (default security profile) | BUILD SUCCESS; BugInstance 0, Error 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests OK |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 each (V18 unused; no migration added) |
| `git diff --check` | exit 0 |

### Defects found and decisions (this attempt)

- **Defect fixed — silent approval no-op:** `CatalogVersion.approve(null)` returned a
  copy with no approval. Any caller treating the return as a completed governance
  decision would have an unapproved draft that looks approved. It now fails closed.
  This is a CLN-04/ADM-01 named-approval integrity issue, not cosmetic cleanup.
- **Defect fixed — boundary NPE leak:** `CatalogVersion`'s compact constructor
  called `List.copyOf(entries)` with no null guard, leaking `NullPointerException`
  instead of the `IllegalArgumentException` used by every sibling value object in
  this package (`VersionStore`, `DiagnosisCode`, `DictionaryField`,
  `MixedScriptSupport`). Now consistent.
- **Decision — no effective-dating change:** `Approval.approvedAt` remains data and
  the fold still selects the latest approved version by allocation order, matching
  attempts 1–3. Effective windows are owned by the V5 persistence fold
  (`ConfigurationService`, `S-G0-03`) and were deliberately not duplicated here:
  existing fixtures pass opaque approval instants such as `"t1"`, so parsing them
  in this kernel would be a breaking, out-of-scope change. Recorded as a deferred
  design decision, not a covered claim.
- **Decision — classification:** cross-boundary domain data contract
  (docs/WORKFLOW.md); one bounded simulated senior-backend review plus the executed
  fast gates. No clinical enablement is claimed; real site approval remains G5.

### Specialist review outcome (this attempt, simulated senior-backend reviewer)

A bounded read-only review of the two changed files plus sibling domain classes,
`pom.xml` and `apps/api/check.sh` found **no findings with impact**. It confirmed the
two guards fail closed, match the `"<thing> is/are required"` `IllegalArgumentException`
contract used by every sibling, preserve the immutable record pattern (freeze in the
compact constructor plus defensive copy accessor, so no EI_EXPOSE_REP regression),
that both new branches are necessary for the branch gate, and that no existing
caller or test passes null here (defense-in-depth). Business-domain naming and
Javadoc requirement IDs were confirmed. Coverage and SpotBugs conclusions from the
reviewer were reasoned from source/config; they were **observed** by the primary
agent with the executed commands above. Evidence source: simulated specialist,
static read-only inspection; no files edited by the reviewer.

### Evidence limitations / blockers (this attempt)

1. Database and integration gates were **not** run here by instruction; the
   integrator runs `bash apps/api/check.sh` on the merged SHA with PostgreSQL.
   Both new tests are plain JUnit domain tests and need no database, so they ran
   in the fast tier. Full-tier JaCoCo from the integrator is authoritative.
2. Doxygen/`scripts/docs.sh` was not rerun in this attempt: no public contract,
   maintainer guide or documentation hook changed, and `scripts/check-docs.py`
   passes. The integrator's `integrator-gate.sh` remains authoritative.
3. `opencode.json` is a skip-worktree CI-profile artifact in workers (BUGS
   OPS-CW-003-01); it was not edited or measured here.
4. No simulated-user or human clinical evidence; the specialist review is a
   static simulated review. Site clinical/privacy approval for real use is a G5 gate.
5. All evidence above is tool-generated and synthetic; no PHI, real identifiers or
   credentials were used.
