# S-G4-03 — Prescription draft, versioned drug catalog, alternatives and verified allergy checks (RX-01)

## Scope honoured

- Branch `swarm/S-G4-03-99`, base/HEAD `40e1668` (worker did not create a new commit before these notes).
- Only `apps/api/src/main/java/com/aurevia/smartclinic/g4/s_g4_03/PrescriptionContract.java` was changed.
- Reserved migration **V43** (`db/migrations/V43__prescription_drafts.sql`) was inspected but **not modified**; no other migration version was touched.
- No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm`) was edited. No push, tag or `gh` call. Synthetic fixtures only; no patient data or credentials.

## Requirement IDs and acceptance mapping

Primary **RX-01** (§5). Supporting **ARC-01/ARC-02/ARC-03/ARC-04**, **IAM-01**, **ADM-01**, **OPS-02**.

| RX-01 acceptance | Implementation | Evidence (synthetic) |
|---|---|---|
| Draft with patient, medication, strength, formulation, dose, route, frequency, quantity, refills, instructions, prescriber, destination | `PrescriptionService.createDraft`/`selectMedication`, `PrescriptionContract.Draft`, `PrescriptionRepository.insertDraft`/`selectMedication`; prescriber/destination bound server-side; catalog entry key + version frozen into the draft | `PrescriptionFlowTest.fullWorkflowKeepsValidationOverrideAndAuditSeparate`, `invalidInputsAreRejected`, `versionPreconditionsAndAllergyConflictAreEnforced` (integrator-run DB suite) |
| Market-approved versioned catalog search by generic/trade name and formulation | `DrugCatalog.search` (newest approved effective version per key only), `PrescriptionRepository.loadCatalog`, `DrugCatalogController.search`, V43 `UNIQUE(org,entry_key,version)` + approval provenance + effective window | `DrugCatalogTest` 8 tests (fast); `PrescriptionFlowTest.searchHidesUnapprovedAndFutureVersionsAndForcesRLS` (integrator) |
| Tagged alternatives for prescriber review | `DrugCatalog.alternativesFor` → `Alternative(kind=SUGGESTION, requiresClinicianSelection=true)`; never mutates the clinician's selection | `DrugCatalogTest.alternativesAreTaggedSuggestionsAndNeverReplaceTheSelectedEntry`, `prescriberDomainTest` action matrix (fast) |
| Check verified allergy ingredients; surface unknowns and mismatches | `AllergyChecker.check`: only `verified` assertions match; exact substance = CONFLICT, approved group = WARNING, unknown/unverified/unmappable = UNRESOLVED; no free-text name matching; absent status folds to UNKNOWN in `PrescriptionRepository.allergyAssessment` | `AllergyCheckerTest` 9 tests (fast), `PrescriptionFlowTest.allergyStatusesAreDistinctAndNeverSilentlySafe` (integrator) |
| Keep override reason, author and matched-data snapshot | `PrescriptionService.selectMedication` → `insertOverride` on a verified exact match without a reason → 409 `ALLERGY_OVERRIDE_REQUIRED`; V43 append-only `prescription_override`, author derived from `app.actor`; deterministic `snapshot()` of catalog key/version, allergy status, outcome and findings | `PrescriptionFlowTest.versionPreconditionsAndAllergyConflictAreEnforced`, `immutableLedgerAndDatabaseVersionGuardHold` (integrator) |

Supporting: forced RLS on all five V43 tables with `(organization_id,id)` keys and `app_org()` policy; non-owner `smartclinic_app` grants; actor-derived authorship; `If-Match` + `FOR UPDATE` version guard (412 on stale); re-derived membership/roles with denial audit (403). See `PrescriptionActions`, `PrescriptionService.authorize`, `PrescriptionRepository`, V43.

Entry-point-to-test trace: `POST /api/v1/prescription-drafts` → `PrescriptionController.create` → `PrescriptionService.createDraft` → guarded `TransactionTemplate` → `PrescriptionRepository.insertDraft` (forced RLS, DB-derived `created_by`) → `AuditAppender.append` → 201. `POST /api/v1/prescription-drafts/{id}/select-medication` → `PrescriptionController.select` (`IfMatch.parse`) → `PrescriptionService.selectMedication` (row lock, version precondition, allergy re-check, optional immutable override) → guarded update → audit → 200/409/412/403. `GET /api/v1/drug-catalog/search` and `POST .../{id}/allergy-check` follow the same guarded path. Fast-tier tests cover controllers' collaborators and all pure domain units; the HTTP/database path is in `@Tag("integration") PrescriptionFlowTest`.

## Change made by this worker (defect fixed)

**Doxygen/developer-reference gate failed on this scope.** Reproduction with the repository `docs/Doxyfile` (doxygen 1.9.4, same as CI) restricted to `g4/s_g4_03` exited 1 with 42 errors: `parameters of member ... are not documented`, `return type of member ... is not documented`, and `argument 'at' of command @param is not found in the argument list of ...DrugEntry(...)`. Root cause: the 14 records in `PrescriptionContract` carried summary Javadoc but no component `@param`/`@return` tags, and `DrugEntry.effectiveAt` repeated `@param at` inside a record body, which Doxygen folds into the record's canonical-constructor documentation entity.

Fix (in scope, no behaviour change): document each record component once with `@param` on the record declaration and add one `@return` per declaration; move the `effectiveAt` parameter description into prose and keep only `@return` on that method; compact constructors still carry only `@throws`. This matches the house pattern used by completed scopes (e.g. `messaging/s_g3_06/AttachmentContract.java`, `g2/s_g2_09/LabPolicyCatalog.java`). Re-running the same focused Doxyfile exits 0 with zero warnings.

## Exact commands run and results

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | EXIT 0; Surefire 1491 tests, 0 failures, 0 errors, 0 skipped; `com.aurevia.smartclinic.it.s_g4_03` fast tests 28 (AllergyCheckerTest 9, ContractValidationTest 8, DrugCatalogTest 8, PrescriptionDomainTest 3). `PrescriptionFlowTest` (7 tests) is `@Tag("integration")` and is intentionally excluded from the fast tier. |
| focused `doxygen <Doxyfile with INPUT=g4/s_g4_03, WARN_AS_ERROR=FAIL_ON_WARNINGS>` | RED before fix: EXIT 1, 42 errors (see above). GREEN after fix: EXIT 0, 0 warnings/errors. |
| `python3 -I scripts/check-migrations.py --require-baseline` | EXIT 0. V43 header is `Change: expand`, no destructive DDL. |
| `python3 -I scripts/check-docs.py` | EXIT 0 |
| `python3 -I scripts/check-secrets.py` | EXIT 0 |
| `python3 -I scripts/check-pack.py` | EXIT 1: `opencode.json is stale`. Cloud-worker CI-profile artifact: `git ls-files -v opencode.json` → `S` (skip-worktree); the worktree file is the CI profile installed by `agent-run.sh`, which `check-pack.py` judges against the committed local profile. Documented as OPS-CW-003-01. Deliberately **not** regenerated (root-level, out of scope). |
| `DOC_PROJECT_NAME="Aurevia Care" doxygen docs/Doxyfile` (full repo) | EXIT 1 with 228 pre-existing errors, **0 in `s_g4_03`**, all outside this scope (other `s_g0_0x`, `s_g1_0x`, `s_g2_0x`, `s_g3_0x`, `s_g4_0x` files with missing record/enum `@return`/`@param`). Reported for the integrator; not modified from this branch. |

I did **not** run `db/check.sh`, `mvn verify`/JaCoCo or `@Tag("integration")` suites (including `PrescriptionFlowTest`) — per the worker contract the integrator runs database, integration and coverage gates. No coverage result is claimed or fabricated here.

## Defects found (for owner / integrator / follow-up)

1. **FIXED — docs gate (Doxygen)**: missing record component `@param`/`@return` and record-method `@param at` folding in `PrescriptionContract`. Evidence: focused Doxygen RED 42 errors → GREEN 0. Fix described above.
2. **MEDIUM, open — false override records**: `PrescriptionService.selectMedication` writes a `prescription_override` whenever `overrideReason != null`, even when `check.outcome()` is `NO_CONFLICT`/`WARNING`/`UNRESOLVED`, producing an immutable record whose snapshot says `outcome=no_conflict`. Suggested fix (Senior Backend reviewer): reject with 422 `NO_OVERRIDE_NEEDED` when no verified CONFLICT exists, and add an integration case. **Not changed here**: behaviour change without an executable local test (DB suite belongs to the integrator) and it needs product/clinical sign-off on the exact status code.
3. **HIGH/MEDIUM, open — clinical safety of non-CONFLICT outcomes** (Physician reviewer, simulated): `WARNING` (verified cross-reactivity group) and `UNRESOLVED` (unknown/unverified assessment or unmappable ingredient) currently allow selection with no documented acknowledgement; the transient `AllergyCheck` is not persisted on the draft, so a reviewer cannot distinguish it from a clean check. Also: group code is matched without group-version reconciliation; group-only matches are labelled as therapeutic "shared ingredient identity" alternatives even for a cross-reactive group; the draft snapshots the generic name but not catalog strength/formulation. These are clinical-policy and RX-01-completeness decisions: route to the clinical safety lead / product owner; **not** unilaterally changed by a simulated reviewer. RX-02 (attestation and routing) is the natural place to bind the check revision to the prescriber's act.
4. **MEDIUM, open — V43 allergy patient FK**: `patient_allergy_status` and `patient_allergy` carry a bare `patient_id`; only `prescription_draft` gets the conditional composite FK to `patient_record` when that table exists. Add the two composite FKs inside the same `to_regclass('public.patient_record')` guard for ARC-01 attribution. **Not changed here**: schema change whose only executable verification is the integrator's DB suite.
5. **LOW, open — `db/check.sh` does not assert the five V43 tables** (FORCE RLS, grant boundaries, immutability/actor/version triggers). `db/check.sh` is outside this worker's scope; integrator/owner to decide.
6. **LOW, open — optional `destination` can never be set after create** because V43 pins it on update; RX-01 lists destination as a draft field. Make it required at create or add a pre-selection command.
7. **MEDIUM, open — no idempotency key on draft create** (ARC-03); a retried `POST` minting a new UUID creates a second draft. X-01 is the cross-cutting owner.

Pre-existing/out of scope (not caused by this story, not modified): full-repo Doxygen 228 errors outside `s_g4_03`; `check-pack.py` stale-profile artifact above.

## Decisions

- Keep the documentation fix scoped to `PrescriptionContract.java`: component docs on the record declaration only; never repeat `@param` on a compact constructor/factory (Doxygen folds them into one canonical-constructor entity); record methods describe their parameter in prose and keep only `@return`.
- Do not change clinical behaviour, V43 schema, `db/check.sh`, trackers, workflows or any other scope without an owner decision and a runnable test; record the findings instead.
- Evidence provenance: all tool results are executed-command evidence from this worker (Maven/Surefire, Doxygen 1.9.4, Python policy checkers) on synthetic fixtures. Findings 2–7 are **simulated specialist reviews** (Senior Backend, Physician/Safety, Senior Database), not human clinical or real-user feedback. No human participant, no real-patient approval; G5 sign-off remains outstanding.

## Full-repo docs gate note for the integrator

`scripts/docs.sh` will fail on the current tree because of 228 pre-existing Doxygen errors in other story scopes; the `s_g4_03` scope itself is clean under the identical Doxyfile settings. Those scopes are owned by other stories/workers and were deliberately left untouched here.
