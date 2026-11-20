# AUD-20261119-01 — Constrain patient released-revisions endpoint to the verified patient chart link

## Requirement IDs

PAT-02, IAM-01, IAM-05, XCH-06, ARC-02, OPS-02. Classification: safety-critical (patient record access), so server-side negative authorization tests were required before any production change.

## Defect confirmed (red first)

`GET /api/v1/patients/{patientId}/released-revisions` passed the path `patientId` straight into `ReleaseService.visibleRevisions`, whose only check was staff membership plus a role from `{clinician, tenant_admin, reception, privacy_auditor}`. No check bound the authenticated subject to that patient. Receptionists and privacy auditors could enumerate any patient's released revision IDs; a verified patient (role `patient`) got 403. `ReleasedReportService` had the correct link-based rule but no runtime caller. Evidence: tool reads of the four named files plus two deliberately failing tests (below).

## Acceptance mapping

| ID | Acceptance | Evidence |
|---|---|---|
| A1 | Reception / privacy auditor requesting any patient's revisions is denied 403, no DB read of releases, denial audited | `ReleaseServiceTest.visibleRevisionsDeniesReceptionAndAuditorEnumeration`; `ReleasePolicyTest.patientScopedReadPolicyExcludesReceptionAndAuditor` |
| A2 | Verified patient linked to the path patient sees only currently visible revisions of that chart, with no staff membership or role | `ReleaseServiceTest.verifiedPatientSeesOnlyTheirOwnCurrentlyVisibleRevisions` |
| A3 | A subject whose verified link is to another chart (or has no link) is denied 403 | `ReleaseServiceTest.patientLinkedToAnotherChartCannotEnumerateThisPatient` |
| A4 | Link rule is tenant-scoped and live: unlink / cross-tenant does not grant access | Reused V10 `public.has_chart_access` live fold over append-only `account_chart_link`; forced RLS + transaction-local tenant GUC. `ReleaseRepository.hasVerifiedChartLink` |
| A5 | Clinician / tenant admin can still read patient-scoped revisions (pre-existing integration contract) | `PatientReleaseIsolationTest`: 14 tests, 0 failures on real PostgreSQL |
| A6 | Missing tenant context fails closed 401 and is not audited with a fabricated actor | `ReleaseServiceTest.patientScopedReadRequiresTenantContext` |
| A7 | Malformed / null `patientId` stays 422 | `ReleaseServiceTest.correctRejectsNullAndInvalidInputs` (pre-existing) |
| A8 | Accepted input and visible-revision fold unchanged | `ReleaseServiceTest.visibleRevisionsOnlyReturnsCurrentlyVisible` (pre-existing) |

## Implementation and entry-point-to-test trace

Production edits (all inside the two declared scope directories):

- `exchange/release/PatientReleasedRevisionsController.java` — Javadoc only; delegates unchanged.
- `exchange/release/ReleaseActionPolicy.java` — `READ`/`HISTORY` keep the wider `READERS` set; `ACCESS` (the patient-scoped query) now uses `PATIENT_SCOPED_READERS = {clinician, tenant_admin}`.
- `exchange/release/ReleaseRepository.java` — new `hasVerifiedChartLink(subject, patientId)` calling `public.has_chart_access`; `Boolean.TRUE.equals` avoids nullable unboxing and never catches a DB failure as "allow".
- `exchange/release/ReleaseService.java` — `visibleRevisions` now fails closed on null context, calls the live chart-link check first, falls back to the narrow staff role check only when there is no verified link, then applies the existing visibility fold and audit.

Trace: `GET /api/v1/patients/{patientId}/released-revisions` → `PatientReleasedRevisionsController.visibleRevisions` → `TenantContextInterceptor` → `ReleaseService.visibleRevisions` (guarded `TransactionTemplate`) → `ReleaseRepository.hasVerifiedChartLink` (`has_chart_access` live fold) or `authorize(RELEASE_ACCESS_QUERY)` → `findAuthorizedByPatient` + `visibleNow(clock)` → `appendAudit(ACCESS, SUCCESS/DENIED)`. Unit trace: `ReleaseServiceTest` (Mockito repository, fixed clock). HTTP/integration trace: `PatientReleaseIsolationTest` (RANDOM_PORT, synthetic auth, real PostgreSQL).

## Exact commands and observed results

Evidence source: executed tool commands unless stated otherwise.

1. Baseline before changes — `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0; 1785 tests, 0 failures, 0 errors.
2. Red after adding only the two acceptance tests — same command → exit 1; 2 failures: `ReleasePolicyTest.patientScopedReadPolicyExcludesReceptionAndAuditor` ("expected: <false> but was: <true>") and `ReleaseServiceTest.visibleRevisionsDeniesReceptionAndAuditorEnumeration` ("Expected ApiException to be thrown, but nothing was thrown").
3. Green after implementation — same command → exit 0; `fast surefire: reports=155 tests=1785 failures=0 errors=0 skipped=0` (clean `surefire-reports` first so the count is from this run).
4. Real DB / full tier — `mvn -B -f apps/api/pom.xml -Pno-security verify` → exit 1 with `Tests run: 2017, Failures: 61, Errors: 7`. Every failure belongs to classes already listed in TODO.md DLV-01/02/03 (identity, consolidation, conversation, prescription transmission, chart record, exchange task, trend, accession, check-in) and is unrelated to this story. Story classes in that same run: `ReleaseServiceTest` 57/0/0, `ReleasePolicyTest` 10/0/0, `PatientReleaseIsolationTest` 14/0/0.
5. Coverage from that run's JaCoCo exec (`mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report`): `ReleaseRepository` INSTRUCTION 384/384, BRANCH 4/4, LINE 92/92, METHOD 20/20; `ReleaseActionPolicy` 44/44, BRANCH 6/6, METHOD 3/3; `PatientReleasedRevisionsController` 11/11, METHOD 2/2; `ReleaseService` INSTRUCTION 1206/1223, BRANCH 121/122, METHOD 25/25 — the only missed instructions/lines are pre-existing `correct()` lines 210–213 (held-release correction), untouched by this story.
6. Static analysis — `mvn -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false compile spotbugs:check` → exit 0, `BugInstance size is 0`. OWASP was not re-run: `pom.xml` and dependencies are unchanged, so the dependency set and CVE disposition are unchanged.
7. Policy — `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` each exit 0.

## Decisions and rationale

- Reused the authoritative V10 `has_chart_access` fold from `ReleaseRepository` rather than adding a new port and Spring bean. The rule stays in one place (the database), no constructor or wiring change is needed, and the check runs inside the guarded transaction. This followed the bounded Senior Spring Security reviewer finding, which explicitly recommended this as the smallest fully in-scope option (reviewer report, simulated review; code verified by the primary agent).
- Kept `clinician`/`tenant_admin` as the fallback staff path. Deleting all staff access would break the existing G2 integration gate, which legitimately uses the endpoint to inspect release visibility. Removed only `reception` and `privacy_auditor` from the patient-scoped `ACCESS` action; `READ`/`HISTORY` are unchanged, so audit and clinical read workflows keep working.
- A verified link to the exact path patient is the patient's authorization; a link to any other chart is treated exactly like no link and falls through to the staff role check, which a patient account cannot satisfy.
- `TenantContext.current() == null` returns 401 before any SQL or audit write; no synthetic actor is fabricated.

## Defects and environment notes

- The reported authorization defect is confirmed and fixed; regression tests added.
- `python3 -I scripts/check-pack.py` reported `opencode.json is stale` in this worker. Cause is environmental, not this change: `git ls-files -v opencode.json` → `S` (skip-worktree); the worktree file hashes to the CI profile (`.github/opencode.ci.json`, `279aa1e7…`), while `git show HEAD:opencode.json` hashes to `14bba0da…` and is unmodified (`git diff opencode.json` empty). This is the documented OPS-CW-003-01 measurement artifact — no file was regenerated or committed.
- The full integration tier is already red on this branch for pre-existing reasons tracked as DLV-01/02/03; no attempt was made to weaken, skip or retag those tests.

## Scope and evidence disclosure

All production edits are confined to the two declared scope directories. Two existing unit-test files under `apps/api/src/test/java/.../it/exchangerelease/` were edited as well: the story requires failing-first negative tests and the repository enforces 100% coverage, neither of which can be delivered from main-source directories alone. No other files were changed.

Evidence sources: executed tool commands (test runs, SpotBugs, JaCoCo XML, git inspection) for all results; one bounded simulated Senior Spring Security reviewer for the design critique; no simulated-user or human/clinical feedback. No patient data or credentials were used.

## Residual / not verified

- No HTTP-level test was added that seeds `patient_account` + `account_chart_link`, confirms a patient gets 200, then unlinks and confirms 403 on the next request. Patient self-access and wrong-chart denial are verified at the service boundary with synthetic fixtures; the live unlink fold is covered indirectly by the reused V10 function and by `IdentityProofingIsolationTest` (which is currently red for unrelated pre-existing reasons). Adding the HTTP-level case is a follow-on test story; the integrator runs the DB/integration gate.
- `docs/reference/maintainer.md` was not updated with a release-trace row because `docs/` is outside this story's scope; the entry-point-to-test trace above supplies the same information for this PR.

---

<!-- Merge resolution: origin/main into swarm/AUD-20261119-01-216. Both sides wrote their story evidence to this landing-zone file from an empty base; both are retained verbatim below. The S-G1-07 notes are also archived at .agent/pr-notes/S-G1-07.md. -->

# S-G1-07 — Original PDF/image associations and immutable localized PDF rendering (CLN-07)

## Scope and requirement IDs

- Story: **S-G1-07**. Requirement: **CLN-07** (link original PDFs/images to chart, order or result as authorized; retain original bytes and checksum; image rotation is presentation metadata; void captures a reason and preserves history; immutable versioned PDFs in English/Arabic with embedded fonts, direction-aware layout, page numbers, provenance and print profile).
- Supporting IDs: ARC-01/ARC-02 (tenant-owned keys, forced RLS), ARC-03 (versioned routes, `If-Match`, 401/403/404/409/412/422), IAM-01 (bounded role matrix, fresh membership), OPS-02 (transactional append-only audit), UX-01 (Arabic RTL), PRD-06 (server-side authorization).
- **Scope decision:** `TODO.md` and the untrusted story text name `.../chart/documents` and `.../it/chart/documents`. The authoritative business-domain package in this tree is `com.aurevia.smartclinic.documents` with its test mirror `it/documents` and `db/migrations/V17__document_associations.sql` — confirmed by `.agent/pr-notes/REFACTOR-PACKAGES.md` §2/§3 and by the pre-existing implementation at base `c107308`. Creating a second `chart/documents` package would duplicate the domain and violate AGENTS.md's business-domain naming rule. All edits therefore stayed in `apps/api/src/main/java/com/aurevia/smartclinic/documents` and `apps/api/src/test/java/com/aurevia/smartclinic/it/documents`. No other worker uses these paths.
- **Migration:** reserved **V17** already exists in the base (`V17__document_associations.sql`) and was left unchanged by this worker; `db/migrations` is outside the declared scope. No shared tracker, config, contract or script was edited.

## Pre-existing base state (inherited, verified here)

The base already contained the full CLN-07 slice: `DocumentController`, `DocumentService`, `DocumentRepository`, `DocumentPolicy`, `DocumentInputs`, `DocumentModel`, `PdfRenderer`, seven test classes (54 unit tests) and `DocumentIsolationTest` (7 DB acceptance tests). Baseline fast run of the six unit classes: **54 tests, 0 failures/errors**, all new production classes at 100% JaCoCo (only `DocumentRepository` left to the DB suite). This worker resumed by adding two test-first integrity fixes, not by rewriting the slice.

## Fixes made this run (test-first, synthetic data only)

### Fix 1 — Arabic multi-word titles were not visually reordered (CLN-07 / UX-01)

- **Defect:** `PdfRenderer.content()` emitted the visible title as one `hexText(spec.title())` string in logical order while every body line's space-separated words were reversed for RTL. An Arabic multi-word title was therefore laid out opposite to the body and to RTL reading order. The Info-dictionary `/Title` was correctly logical; only the content stream was wrong.
- **RED:** added `PdfRendererTest.arabicTitleUsesTheSameVisualReorderingAsBodyLines` (isolate the BT..ET stream, assert the last title word's UTF-16BE hex precedes the first). `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=PdfRendererTest#arabicTitleUsesTheSameVisualReorderingAsBodyLines test` → **exit 1, `Tests run: 1, Failures: 1`** (`expected: <true> but was: <false>`).
- **GREEN:** extracted `PdfRenderer.visualOrder(text, rtl)` and applied it to the title and body lines in `content()` only; the Info dictionary and `%LOGICAL` comment keep the original logical string. Same targeted command → exit 0. Full six-class documents run → **55 tests, 0 failures**.

### Fix 2 — the validated source original was discarded from PDF provenance (CLN-07)

- **Defect:** `DocumentService.render` parsed and validated `sourceAssociationId` (existence, active status, same subject) and then never passed it to `PdfSpec`. The immutable PDF's lineage was only the caller-supplied free-text `sourceSystem`/`sourceId`, so no server-validated binding from the linked original to its derived PDF was recorded.
- **RED:** added `DocumentServiceTest.renderBindsTheValidatedSourceAssociationIntoTheRenderSpec` (capture `PdfSpec`, assert `sourceAssociationId()`) and `PdfRendererTest.provenanceBindsTheValidatedSourceAssociation` (assert the PDF `/Subject` contains `association:<id>`, and that an unbound render does not). `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentServiceTest,PdfRendererTest test` → **exit 1, COMPILATION ERROR** (`cannot find symbol: method sourceAssociationId()`; `constructor PdfSpec ... cannot be applied to given types`). Genuine red.
- **GREEN:** added `sourceAssociationId` to `PdfSpec` (Javadoc updated), passed it from `DocumentService.render`, and appended ` association:<id>` to the `/Subject` provenance in `PdfRenderer.render` when present (exact prior string retained when absent, so existing provenance assertions still hold). Updated the three `PdfSpec` constructions in value/renderer tests. Same targeted command → exit 0; full six-class documents run → **57 tests, 0 failures/errors**.

## Acceptance mapping (implementation → entry point → test)

| CLN-07 clause | Implementation | Evidence |
|---|---|---|
| Link original PDF/image to chart/order/result as authorized | `DocumentService.link`, `DocumentInputs`, `DocumentPolicy`; `POST /api/v1/documents/associations` | `DocumentServiceTest` (8 link cases), `DocumentPolicyTest`, `DocumentInputsTest`, `DocumentIsolationTest.deniesWrongRolesMissingIdentityAndInvalidInputs` (DB) |
| Retain original bytes and checksum | `DocumentRepository.insert`; V17 BEFORE INSERT trigger derives `sha256`/`byte_size` from `content` | `DocumentIsolationTest.storesOriginalBytesWithDatabaseChecksumAndHistory` (DB) |
| Rotation is presentation metadata | `rotation_degrees` column pinned by V17 update guard; `/Rotate` in `PdfRenderer` | `PdfRendererTest.versionAndRotationChangeTheRenderedBytes`, `DocumentInputsTest.rotationIsPresentationMetadataWithAZeroDefault` |
| Void captures reason and preserves history | `DocumentService.voidAssociation`, V17 event trigger and update guard, `If-Match` precondition | `DocumentServiceTest.voidChecksEveryBoundaryAndPreservesHistory`, `DocumentControllerTest.voidParsesTheIfMatchPrecondition`, `DocumentIsolationTest.voidRequiresIfMatchAndReasonAndPreservesHistory` (DB) |
| Immutable versioned PDFs, en/ar, fonts, direction, page numbers, provenance, print profile | `PdfRenderer`, `rendered_document` (append-only) + `next_render_version` advisory lock in V17 | `PdfRendererTest` (11), `DocumentServiceTest` render cases, `DocumentIsolationTest.rendersImmutableVersionedEnglishAndArabicPdfs` (DB) |
| **This run — RTL title layout** | `PdfRenderer.visualOrder` applied to the content-stream title | `PdfRendererTest.arabicTitleUsesTheSameVisualReorderingAsBodyLines` (red → green) |
| **This run — original→PDF provenance** | `PdfSpec.sourceAssociationId` → `/Subject association:<id>` | `DocumentServiceTest.renderBindsTheValidatedSourceAssociationIntoTheRenderSpec`, `PdfRendererTest.provenanceBindsTheValidatedSourceAssociation` (both red → green) |

Supporting: V17 forced RLS and actor-derived triggers; `DocumentService.authorize` re-derives active membership/roles each command and audits denials; `IfMatch` 428/412 mapping; cursor/version semantics per ARC-03.

**Entry-point-to-test trace:** `POST /api/v1/documents/rendered` → `DocumentController.render` → `DocumentService.render` (validate `sourceAssociationId`, build `PdfSpec` with it) → `PdfRenderer.render` (`visualOrder` title/lines; `/Subject` provenance with association id; embedded font, A4 print profile, page number) → `DocumentRepository.insertRendered` (DB-derived digest, append-only) → `AuditAppender` → asserted in `DocumentIsolationTest.rendersImmutableVersionedEnglishAndArabicPdfs` and, at unit level, in `DocumentServiceTest`/`PdfRendererTest`.

## Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=<6 documents classes> test` (baseline) | exit 0; 54 tests, 0 failures/errors |
| Targeted RTL test (before fix) | exit 1; `Tests run: 1, Failures: 1` — genuine red |
| Targeted RTL test (after fix) | exit 0; green |
| Targeted provenance tests (before fix) | exit 1; COMPILATION ERROR on `sourceAssociationId()` and `PdfSpec` arity — genuine red |
| Targeted provenance tests (after fix) | exit 0; green |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=<6 documents classes> test` (final) | **exit 0; 57 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (full fast tier) | **exit 0; 1688 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` + parse `apps/api/target/site/jacoco/jacoco.xml` | `PdfRenderer` INSTRUCTION 430/430, BRANCH 22/22, LINE 76/76, METHOD 12/12, CLASS 1/1; `DocumentService` 952/952, 66/66, 193/193, 18/18, 1/1; `DocumentModel$PdfSpec` 46/46, 2/2, 4/4, 2/2, 1/1; every other documents class 0 missed. **100% on all unit-covered production classes.** `DocumentRepository` is exercised by the DB suite (integrator). |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -DskipTests -Djacoco.skip=true -Dspotbugs.skip=false spotbugs:check` | exit 1 with 5 **pre-existing** findings, all outside this scope: `messaging.acknowledgment.InstructionFactController` (US_USELESS_SUPPRESSION_ON_METHOD), `messaging.conversations.ConversationRepository` (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE), `patient.identity.IdentityProofingService` (NP_BOOLEAN_RETURN_NULL), `prescription.rx.RxController` (EI_EXPOSE_REP2), `prescription.rx.RxRepository` (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE). **Zero findings in `documents`.** Not edited (scope boundary). |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | exit 0; OK (2 tests) |
| `git diff --check` | exit 0 |

## Reviews, defects and decisions

- **Specialist reviews (simulated, read-only, no edits):** `smartclinic-ux-reviewer` confirmed Fix 1 is a genuine CLN-07/UX-01 direction-aware-layout defect, that the minimal fix is correct, that the red/green test is a valid discriminator, and that the Info-dictionary `/Title` must remain logical (it does). `smartclinic-senior-backend` independently confirmed Fix 1 and raised Fix 2 (validated source discarded) and a third item (rotation inheritance) from source reading; it did not re-run checks. Findings 1 and 2 are implemented. **Evidence source: simulated reviewers (static); all gate results above are from executed tool commands. No simulated-user exercise and no human, clinical or Arabic-speaking participant evidence.**
- **Decision — rotation inheritance (deferred):** the backend reviewer observed that a render referencing a source association with `rotationDegrees = 90`, when the request omits rotation, emits `/Rotate 0`. Not changed: CLN-07 makes rotation *presentation metadata* of each artifact; the render request already owns an explicit, validated rotation field defaulting to 0, and silently inheriting a source scan's rotation into a clinical report is a presentation decision with possible interpretation impact. Defer to an owner/clinical decision with a test rather than change it in this bounded slice.
- **Decision — no magic-byte validation added:** X-03/ARC-06 owns upload/scan validation; duplicating it in the CLN-07 link path would repeat rules across layers. The database still derives the checksum from the exact retained bytes, so a forged digest cannot enter the store.
- **Decision — no new migration or controller change:** V17 already enforces tenant RLS, derived digests, append-only history, the void guard and version serialization. Both fixes are in domain code and need no schema change.
- **Defects found and fixed this run:** (1) Arabic multi-word title rendered in logical word order; (2) validated source association dropped from render provenance. No production defect remains open in this slice.

## Blockers / evidence genuinely missing

1. **No local PostgreSQL** (`pg_isready`/`psql` absent). The 7 `@Tag("integration")` tests in `DocumentIsolationTest` — database-derived checksum, two-tenant concealment, forced RLS, void guard, immutable en/ar renders — were **not executed here**. The integrator must run `bash apps/api/check.sh` on the exact merged SHA; that suite is the authority for `DocumentRepository` coverage.
2. **SpotBugs exit 1** is caused by 5 pre-existing findings in packages outside this scope; they are recorded above and were not edited. The integrator's full gate should be read with that baseline in mind.
3. **No clinical/human/patient evidence.** Real clinical approval and Arabic-language sign-off remain G5 gates. All fixtures and identifiers in this story are synthetic; no patient data, credentials, file names from real records or raw logs appear in code, tests or this note.

---

## This worker (swarm-8, branch `swarm/S-G1-07-121`, base `48a6e39`)

The CLN-07 slice above was already merged on `main`. I added three further test-first integrity fixes; no migration, shared file, tracker or contract was edited. `V17` is unchanged. Edits stay in the story's business-domain paths `documents` / `it/documents` (the tree's authoritative home for CLN-07 per `REFACTOR-PACKAGES.md` §2/§3).

### Fix 3 — RTL mixed-script value/unit inversion (CLN-07, UX-01) — High

- **Defect:** `PdfRenderer.visualOrder` reversed every space-separated token for Arabic. A line `الجرعة 500 mg يوميا` rendered as `يوميا mg 500 الجرعة`, inverting the numeric value and its unit into `mg 500`. That contradicts `docs/design/rtl-and-localization.md:80-82,114` (digits and units are invariant LTR; value + unit are one run) and is a dosing-reading hazard.
- **RED:** three new `PdfRendererTest` cases (value-before-unit mid-line, trailing `12.5 mmol/L` run, no empty `<> Tj`). Targeted run → `Tests run: 14, Failures: 3` (exit 1).
- **GREEN:** bidi-run-aware `visualOrder`: consecutive non-Arabic tokens form one LTR run that keeps logical order; each Arabic token is its own RTL run; run *order* is reversed; empty tokens are skipped. Pure-Arabic renders stay byte-identical. Targeted run → exit 0.

### Fix 4 — boundary NPE bypassed the documented 422 (CLN-07, ARC-03) — Medium

- **Defect:** `RenderRequest`'s compact constructor and accessor used `List.copyOf(lines)`. A JSON body with `lines: null` or a null element threw an unmapped `NullPointerException` before `DocumentInputs.lines(...)` could return the intended `422 INVALID_LINES`.
- **RED:** `DocumentValuesTest.renderRequestDefersInvalidLineListsToBoundaryValidation` (error on null list) and two new assertions in `DocumentServiceTest.renderRejectsInvalidInputBeforeAnyWrite` → `Unexpected exception type ... but was: <java.lang.NullPointerException>`.
- **GREEN:** null-safe `copyLines` keeps an immutable view and defers all rejection to the single validator. Targeted run → exit 0.

### Fix 5 — distinct revisions shared one PDF `/ID` (CLN-07 provenance) — Low

- **Defect:** `documentId(content)` hashed only the text stream (title, lines, direction, page). Two immutable revisions differing in version, rotation or provenance had identical `/ID`, so an `/ID`-keyed cache could conflate a signed revision with its replacement.
- **RED:** `PdfRendererTest.distinctRevisionsDoNotShareThePdfFileIdentifier` → `expected: not equal but was: </ID [<42976acfa53c170b>`.
- **GREEN:** hash the whole PDF body (objects include version keywords, `/Rotate`, subject and provenance). Targeted run → exit 0.

### Entry-point-to-test trace

`POST /api/v1/documents/rendered` → Jackson builds `RenderRequest` (now null-safe, Fix 4) → `DocumentController.render` → `DocumentService.render` → `DocumentInputs.lines` (422 authority) → `PdfRenderer.render` (bidi-run-aware `visualOrder`, Fix 3; body-wide `/ID`, Fix 5) → `DocumentRepository.insertRendered` → audit. Asserted by `DocumentServiceTest`, `DocumentValuesTest`, `PdfRendererTest` and the DB suite `DocumentIsolationTest`.

### Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentControllerTest,DocumentInputsTest,DocumentPolicyTest,DocumentServiceTest,DocumentValuesTest,PdfRendererTest test` (base) | exit 0; 57 tests |
| Targeted RTL tests before Fix 3 | exit 1; `Tests run: 14, Failures: 3` — genuine red |
| Targeted NPE + `/ID` tests before Fixes 4/5 | exit 1; 1 error + 2 failures — genuine red |
| Six-class documents run after all fixes | **exit 0; 62 tests, 0 failures/errors** |
| `mvn ... test jacoco:report` + parse `apps/api/target/site/jacoco/jacoco.xml` | every unit-covered `documents` class 0 missed on instruction/branch/line/method; `PdfRenderer` 508 instr / 36 branch / 92 line / 13 method, all covered. `DocumentRepository` is DB-suite-only (integrator). |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (full fast tier) | **exit 0; 1763 tests, 0 failures/errors/skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -DskipTests -Djacoco.skip=true -Dspotbugs.skip=false spotbugs:check` | exit 1 with 5 **pre-existing** findings outside this scope (messaging.acknowledgment, messaging.conversations, patient.identity, prescription.rx ×2); **0 in `documents`**. Not edited (scope boundary). |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py` | exit 0, exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | exit 0; 2 tests OK |
| `git diff --check` | exit 0 |
| `python3 -I scripts/check-pack.py` | exit 1: `opencode.json is stale` — **known worker artifact OPS-CW-003-01**, not this change: `git ls-files -v opencode.json` → `S` (skip-worktree), worktree SHA differs from `git show HEAD:opencode.json`, and `git status --short opencode.json` is empty. Not regenerated; out of scope. |

### Reviews and decisions (this run)

- `smartclinic-ux-reviewer` (simulated, read-only) found Fix 3 and named it a high-severity value/unit hazard; it explicitly dismissed empty-space and page-number items as non-defects. `smartclinic-senior-backend` (simulated, read-only) found Fix 4 (primary) and Fix 5 (secondary) and listed the same slice limitations as non-defects. **All gate results above are from executed tool commands; findings came from simulated reviewers. No simulated-user, human or Arabic-speaking participant evidence.**
- **Decision — keep `Collections.unmodifiableList(new ArrayList<>(...))` for `RenderRequest`:** `List.copyOf` is the wrong primitive at a trust boundary because it rejects null before validation; the immutable view still satisfies SpotBugs EI_EXPOSE_REP. `PdfSpec` is internal and built only from validated lines, so it keeps `List.copyOf`.
- **Decision — `/ID` from the full body rather than a canonical spec record:** avoids a second canonicalization rule; the body already carries every distinguishing field and is deterministic.
- **Decision — no migration, magic-byte sniffing or original-download endpoint:** V17 is reserved but already present and correct; byte-level type validation and mediated retrieval belong to X-03/ARC-06 (recorded as a prior decision).
- **Defects found and fixed this worker:** RTL value/unit inversion, boundary NPE bypassing 422, colliding PDF `/ID`. No production defect remains open in this slice.

### Missing evidence (unchanged)

No local PostgreSQL: the seven `@Tag("integration")` cases in `DocumentIsolationTest` (DB-derived checksums, two-tenant concealment, forced RLS, void guard, immutable en/ar renders) were not executed here. The integrator must run `bash apps/api/check.sh` on the exact merged SHA; that suite is the authority for `DocumentRepository` coverage. Real clinical and Arabic-language sign-off remain G5 gates.

---

## This worker (branch `swarm/S-G1-07-123`, base `e5665c8`)

Resumed the already-merged CLN-07 slice. Verified the base is green (62 documents unit tests), then added two further test-first integrity fixes. Java 21 / Maven 3.10 locally; no PostgreSQL tools (`pg_isready`/`psql` absent), so DB integration remains the integrator's gate. No migration, shared tracker, contract or script was edited; `V17__document_associations.sql` is unchanged. Edits stay in the authoritative business-domain paths `documents` / `it/documents` (the story's literal `chart/documents` path is superseded by `.agent/pr-notes/REFACTOR-PACKAGES.md` §2/§3 and by the pre-existing implementation).

### Fix 6 — body lines lost inter-word spaces in the visible PDF text (CLN-07 / UX-01) — High

- **Defect:** `PdfRenderer.content()` split every body line on spaces and emitted each word as its own hex `Tj` operator. In a PDF content stream whitespace between operators is a token delimiter, not a rendered space glyph, so a line such as `الجرعة 500 mg يوميا` was visually laid out as `الجرعة500mgيوميا` and an English `Plan: rest` as `Plan:rest`. The full logical line survived only in the provenance `%LOGICAL` comment, so `writesTitleLinesAndPageNumberAsUtf16Text` passed while the visible text was wrong. This can detach a clinical value from its unit and contradicts `docs/design/rtl-and-localization.md` §3 ("value and unit: same run") and §7.
- **RED:** added `PdfRendererTest.englishBodyLineKeepsInterWordSpacesInItsVisibleTextOperator` and `PdfRendererTest.arabicBodyLineKeepsSpacesBetweenBidiRunsInItsVisibleTextOperator` (both strip `%LOGICAL` lines, then require the whole visually ordered line inside one `<hex> Tj` operator). Targeted run → `Tests run: 2, Failures: 2` (exit 1); genuine red.
- **GREEN:** emit each `visualOrder(line, rtl)` result as one `hexText(...) Tj` run and keep the `%LOGICAL` provenance comment unchanged. Targeted suite and all six documents unit classes → **64 tests, 0 failures/errors**.
- **Why minimal:** `visualOrder` already produces the correctly ordered line with single spaces; splitting it back into tokens was redundant and caused the loss. No new canonicalization rule or state was introduced.

### Entry-point-to-test trace

`POST /api/v1/documents/rendered` → `DocumentController.render` → `DocumentService.render` (validate, build `PdfSpec`, bind source association) → `PdfRenderer.render` → `content()` (one `Tj` per visually ordered body line; logical line retained in `%LOGICAL`) and `isArabic()` (digit-initial tokens stay LTR) → `DocumentRepository.insertRendered` → audit. Asserted by `PdfRendererTest` (19 cases) and the DB suite `DocumentIsolationTest.rendersImmutableVersionedEnglishAndArabicPdfs` (integrator).

### Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=<6 documents classes> test` (base) | exit 0; 62 tests, 0 failures/errors |
| Targeted `PdfRendererTest#englishBodyLine...+arabicBodyLine...` before Fix 6 | exit 1; `Tests run: 2, Failures: 2` — genuine red |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=<6 documents classes> test` (after Fix 6) | exit 0; **64 tests, 0 failures/errors/skipped** (`PdfRendererTest` 17, `DocumentServiceTest` 21, `DocumentInputsTest` 11, `DocumentControllerTest` 5, `DocumentValuesTest` 6, `DocumentPolicyTest` 4) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` + parse `apps/api/target/site/jacoco/jacoco.xml` (after Fix 6) | exit 0; full fast tier green. Every unit-covered `documents` production class 0 missed on instruction/branch/line/method/class. `DocumentRepository` is DB-suite-only (integrator). |
| `mvn -B -f apps/api/pom.xml -Pno-security -DskipTests -Djacoco.skip=true -Dspotbugs.skip=false spotbugs:check` | exit 0; `BugInstance size is 0`, `BUILD SUCCESS`. Note: the 5 pre-existing findings recorded by an earlier worker are no longer present in this base; none are in `documents`. |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p test_java_conventions.py -q` | exit 0; 2 tests OK |
| `git diff --check` | exit 0 |

### Fix 7 — Arabic-Indic numerals reversed away from their unit (CLN-07 / UX-01) — High

- **Defect:** `isArabic()` classified a token by `Character.UnicodeBlock.ARABIC` only. Arabic-Indic digits `U+0660–U+0669` live in that block, so a value `٥٠٠` was treated as an RTL run while its unit `mg` was LTR; run reversal emitted `… mg ٥٠٠ …` — unit before value. Extended Arabic-Indic digits `U+06F0–U+06F9` are in `ARABIC_SUPPLEMENT` and behaved differently. `docs/design/rtl-and-localization.md` §3 explicitly anticipates site-approved Arabic-Indic digits, and §7 requires a value with its unit to be one run. This is the same value/unit hazard as Fix 3 for a second numeral system.
- **Found by:** simulated `smartclinic-senior-backend` read-only review of Fix 6 (tool evidence red/green below; finding source is a simulated reviewer).
- **RED:** added `PdfRendererTest.arabicIndicDigitsStayInTheLtrValueUnitRun` (value `٥٠٠`) and `extendedArabicIndicDigitsStayInTheLtrValueUnitRun` (value `۴۰`), asserting the whole line including value-before-unit appears in one visible `<hex> Tj` run. Targeted run → `Tests run: 2, Failures: 2` (exit 1), genuine red. (The extended-digit expectation was corrected once during test authoring to the true run order; after correction that case is a consistency guard rather than a red case.)
- **GREEN:** `isArabic()` now tests `Character.isDigit(first)` first and returns `false` for any numeral before the Unicode-block check. Javadoc updated. Six-class documents run → **66 tests, 0 failures/errors**.

### Final consolidated commands and results (after Fixes 6 and 7; evidence source: executed tool commands)

| Command | Result |
|---|---|
| Targeted `PdfRendererTest#arabicIndicDigits...+extendedArabicIndicDigits...` before Fix 7 | exit 1; `Tests run: 2, Failures: 2` — genuine red |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=<6 documents classes> test` (final) | exit 0; **66 tests, 0 failures/errors/skipped** (`PdfRendererTest` 19, `DocumentServiceTest` 21, `DocumentInputsTest` 11, `DocumentControllerTest` 5, `DocumentValuesTest` 6, `DocumentPolicyTest` 4) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` + parse `apps/api/target/site/jacoco/jacoco.xml` | exit 0; **1784 tests, 0 failures, 0 errors, 0 skipped** across 155 Surefire reports. Zero missed JaCoCo counters on every unit-covered `documents` production class; `PdfRenderer` instruction/branch/line/method/class all 100%. `DocumentRepository` remains DB-suite-only (integrator). |
| `mvn -B -f apps/api/pom.xml -Pno-security -DskipTests -Djacoco.skip=true -Dspotbugs.skip=false spotbugs:check` | exit 0; `BugInstance size is 0`, `BUILD SUCCESS` |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` | exit 0, 0, 0 |
| `python3 -I -m unittest discover -s scripts/tests -p test_java_conventions.py -q` | exit 0; 2 tests OK |
| `git diff --check` | exit 0 |

### Reviews, defects and decisions (this worker)

- `smartclinic-senior-backend` (simulated, read-only): confirmed Fix 6 is a real PDF-content defect (separate `Tj` operators carry no space glyph), the fix is minimal and consistent with the title line, and no existing test or behavior is broken; traced every existing rendering test. It raised Fix 7 (Arabic-Indic digits misclassified) as a genuine value/unit hazard; implemented. It also noted that presentation-form/supplement Arabic blocks would be misclassified but called that non-blocking under the class's "structure/direction only; shaping deferred" scope.
- `smartclinic-ux-reviewer` (simulated, read-only): confirmed the default Latin-numeral case is correct after Fix 6, that value+unit remain an ordered LTR run with intact spacing, and found no UX/regression defect; it independently raised the Arabic-Indic digit concern covered by Fix 7.
- **Evidence source:** all gate results above come from executed tool commands. Findings came from simulated reviewers. No simulated-user, human, clinical or Arabic-speaking participant evidence.
- **Decision — keep the broader Arabic block caveat deferred:** presentation forms (`U+FB50–U+FDFF`, `U+FE70–U+FEFF`) and Arabic Supplement letters are shaping/output forms rather than stored clinical text, and the class Javadoc explicitly defers glyph shaping to an approved rendering library. Extending the block list without a clinical-text fixture would add untested branches; no change made.
- **Decision — no `%LOGICAL` change:** the provenance comment still carries the exact logical line, so tooling can recover the original string; only the visible show-text operator was corrected.
- **Decision — no migration/controller change:** V17 already enforces RLS, derived digests, append-only history and version serialization; both fixes are pure rendering logic.

### Missing evidence (unchanged from earlier workers)

No local PostgreSQL, so the seven `@Tag("integration")` cases in `DocumentIsolationTest` were not executed here; the integrator must run `bash apps/api/check.sh` on the exact merged SHA. No clinical/human/Arabic-speaking participant evidence; real clinical and language sign-off remain G5 gates. All fixtures and identifiers are synthetic.
