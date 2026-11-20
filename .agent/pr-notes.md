# S-G1-05 — CLN-05: draft → signed, amendments, signed addenda and entered-in-error with preserved originals

## Scope and routing (inherited from prior attempts, re-verified here)

- Dispatch text names `.../chart/signing` and `.../it/chart/signing`, but the repository-wide package refactor (`.agent/pr-notes/REFACTOR-PACKAGES-20261119-201815.md`) maps this story to the business-domain package `com.aurevia.smartclinic.clinical.notes` with tests in `it/clinical`. AGENTS.md requires business-domain names, so all edits stay in those two paths; no duplicate `chart/signing` package was created. Same stale-path handling as `.agent/pr-notes/S-G2-02.md`.
- Reserved migration **V15** (`db/migrations/V15__clinical_note_signing.sql`, `Change: expand`) already exists at the base and is **unchanged**. No other migration, tracker, config, generated file or contract was edited.
- Classification (`docs/WORKFLOW.md`): safety-critical — signed clinical records, patient context, authorization and tenant isolation. Supporting IDs: ARC-01/ARC-02/ARC-03, OPS-02, IAM-01/02, PRD-06. Clinical approval remains a G5 gate; no human or real-patient evidence is claimed.

## State at resume

The CLN-05 slice was already fully implemented and green at the branch base (`48a6e39`, equal to `main`): 9 production classes in `clinical/notes`, 3 fast test classes plus `ClinicalNoteRevisionIntegrationTest` in `it/clinical`, and V15. Baseline fast run confirmed: `ClinicalNoteServiceTest` 29 tests, `ClinicalNoteControllerTest` 5 tests, 0 failures. This attempt resumes by fixing one real, test-first defect in that slice (below) rather than rewriting it.

## Requirement → acceptance mapping (implementation → entry point → test)

| CLN-05 clause | Implementation | Evidence |
|---|---|---|
| Draft → signed with sign permission, attestation, correct patient context, draft version and required fields | `ClinicalNoteService.createDraft/updateDraft/sign`, `IfMatch.parse`, `NoteInputs`, V15 state/succession guards | `ClinicalNoteServiceTest.sign*`, `signingAppendsImmutableSignedRevisionWithProvenance`, `signPersistsTheExactSignedRevisionInput`; `ClinicalNoteRevisionIntegrationTest.signingRecordsMetadataAndPreservesTheDraft`, `signedRevisionsCannotBeEditedOrReSigned`, `signValidatesAttestationPatientAndVersion` (DB, integrator) |
| Separate amendment drafts and signed addenda; signed originals preserved | `createAmendmentDraft`, `signAddendum`, V15 append-only ledger + `supersedes_version` guard | `amendmentAndAddendumPersistThePriorVersionLink`; `amendmentDraftAndAddendumPreserveTheOriginal` (DB, integrator) |
| Entered-in-error preserves prior revision and records actor, reason and recipients | `markEnteredInError`, V15 `CHECK` + trigger-derived actor | `enteredInErrorPreservesPriorRevisionAndRecordsReasonAndRecipients`, `enteredInErrorPersistsReasonRecipientsAndPreservedContent`; `enteredInErrorPreservesPriorAndRecordsActorReasonAndRecipients` (DB, integrator) |
| Save signer, effective time, signed time, checksum, template version | DB-trigger-owned signer/checksum; service-owned signed/effective instants; immutable `ClinicalNoteRevision` | `signingAppendsImmutableSignedRevisionWithProvenance`, `signPersistsTheExactSignedRevisionInput`; `signingRecordsMetadataAndPreservesTheDraft` (DB, integrator) |
| Signed revisions cannot be edited | `requireEditable`, V15 no-update/delete triggers | `updateDraftRejectsStaleVersionsImmutableStatesAndInvalidContent`; `signedRevisionsCannotBeEditedOrReSigned`, `revisionRowsAreAppendOnlyForEveryRole` (DB, integrator) |
| Signing differs from patient release and PDF rendering | No release/render fields, methods or routes in the signing slice | `signingIntroducesNoReleaseOrRenderingState`; `noReleaseOrRenderingStateIsIntroduced` (DB, integrator) |
| Future-dated effective instant rejected before the irreversible sign (integrity hardening) | `NoteInputs.instantOrNow` rejects instants after the clock; V15 `signed_at >= effective_at` | `inputsRejectMalformedValuesAtTheTrustBoundary`, `createDraftRejectsFutureEffectiveAtBeforeAnyWrite` |
| OPS-02 audit names the requested resource | `ClinicalNoteService.get` passes `id` to `authorize` | `readDenialAuditsTheRequestedNoteId` |
| **This attempt — failure audit survives transaction rollback (OPS-02)** | `ClinicalNoteService.audited` re-appends `AuditedFailure` audits in a committed second transaction; `load`/`requirePatient`/`append` throw `AuditedFailure` instead of auditing inline then throwing | `missingNoteFailureAuditsInASecondCommittedTransaction` (unit, red→green); `failedCommandAuditIsCommittedAfterRollback` (DB, integrator) |

## This attempt's change (test-first, synthetic data only)

**Defect (OPS-02, medium, real):** `load()` (missing note), `requirePatient()` (invalid patient context) and `append()` (lost append race) wrote their `CONFLICT`/`NOT_FOUND` audit row with `conflict()` and then threw *inside* the guarded `TransactionTemplate`. The throw rolls the transaction back, so the audit row never committed — a failed clinical read or sign left no durable trace. `authorize()` denials and `get()` NOT_FOUND already used the commit-safe `failure.set` pattern; these three paths did not.

- **RED:** added `ClinicalNoteServiceTest.missingNoteFailureAuditsInASecondCommittedTransaction`, asserting two `TransactionTemplate.execute` calls (one command, one committed audit) and a `NOTE_SIGN/NOT_FOUND` audit naming the requested note id. `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ClinicalNoteServiceTest#missingNoteFailureAuditsInASecondCommittedTransaction' test` → **exit 1**, `Tests run: 1, Failures: 1`, `TooFewActualInvocations: transactionTemplate.execute(<any>); Wanted 2 times … But was 1 time` at `ClinicalNoteService.sign`. Genuine red caused by missing behavior.
- **Implementation:** added `ClinicalNoteService.audited(TransactionCallback<T>)` which catches the new private `AuditedFailure` signal, re-appends the carried audit facts in a second guarded transaction, then rethrows the public `ApiException`. All 7 guarded commands now run through `audited`. `load`, `requirePatient` and the `written == 0` branch of `append` throw `auditFailure(...)` instead of auditing inline. Authorization denials and successful commands keep their existing single-transaction path.
- **GREEN:** same targeted class → exit 0, `Tests run: 30, Failures: 0, Errors: 0` (was 29).
- **DB regression test added:** `ClinicalNoteRevisionIntegrationTest.failedCommandAuditIsCommittedAfterRollback` asserts a cross-tenant sign (404) increments `NOTE_SIGN/NOT_FOUND` audit in the caller's tenant. Compiled here; executed by the integrator.

## Entry-point-to-test trace

`POST /api/v1/clinical-notes/{id}/sign` → `ClinicalNoteController.sign` → `ClinicalNoteService.sign` → `audited(...)` → guarded tx → `load` (throws `AuditedFailure` when invisible) → catch re-appends `NOTE_SIGN/NOT_FOUND` in a second committed tx → 404. Asserted at unit level in `ClinicalNoteServiceTest.missingNoteFailureAuditsInASecondCommittedTransaction` and at HTTP+PostgreSQL level in `ClinicalNoteRevisionIntegrationTest.failedCommandAuditIsCommittedAfterRollback`.

## Exact commands and results (evidence source: executed tool commands; tool)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ClinicalNoteServiceTest,ClinicalNoteControllerTest,ClinicalNoteRevisionIntegrationTest' test` (baseline) | exit 0; ServiceTest 29, ControllerTest 5, 0 failures; IntegrationTest excluded by `@Tag("integration")` |
| Targeted new test before fix | **exit 1**, 1 failure — `TooFewActualInvocations` (1 tx instead of 2); genuine RED |
| `… -Dtest='ClinicalNoteServiceTest' test` after fix | **exit 0**, 30 tests, 0 failures/errors |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast clean test` | **exit 0**; Surefire aggregate **1761 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + parse `jacoco.xml` | `ClinicalNoteService`: INSTRUCTION 0/1122 missed, BRANCH 0/60, LINE 0/220, COMPLEXITY 0/61, METHOD 0/31, CLASS 0/1 — **100% on all counters**; `NoteInputs`: 0/232 instr, 0/48 branch, 0/47 line, 0/10 method |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -DskipTests -Djacoco.skip=true -Dspotbugs.skip=false spotbugs:check` | exit 1 with **5 pre-existing findings, all outside this scope** (`InstructionFactController` US_USELESS_SUPPRESSION_ON_METHOD, `ConversationRepository` NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE, `IdentityProofingService` NP_BOOLEAN_RETURN_NULL, `RxController` EI_EXPOSE_REP2, `RxRepository` NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE). **Zero findings in `clinical.notes`**; not edited (scope boundary). |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py` | exit 0 each |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | first run FAILED (3): new `{@link}` targets not fully qualified (Doxygen rule); replaced with `{@code}`; re-run **OK (2 tests)** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test-compile` | exit 0; new integration test compiles, excluded from fast tier |
| `git diff --check` | exit 0 |

Local machine: JDK 21.0.13 / Maven 3.10.0. No `opencode.json`, agent or skill file was edited, so no pack regeneration was needed.

## Defects found and decisions

- **Fixed this attempt (OPS-02, medium):** failure audits written before an in-transaction throw were lost to rollback. Audits now commit in a dedicated second transaction through the `AuditedFailure` signal; public status codes and messages are unchanged (404/412/422 as before).
- **Inherited fixes (prior attempts):** future-dated `effectiveAt` rejected at the trust boundary so an unrecoverable draft cannot reach an irreversible sign that would otherwise fail on the V15 `signed_at >= effective_at` check; `NOTE_READ/DENIED` audit now records the requested note id.
- **Deferred (physician reviewer, HIGH, simulated finding):** entered-in-error accepts client-supplied `affectedRecipients` but emits no outbox correction task or delivery/failure evidence. Needs XCH-04/X-02 outbox and a clinical/privacy decision on recipient derivation; separate story with a named clinical owner.
- **Deferred (physician reviewer, MEDIUM-HIGH, simulated):** `clinicalAuthorSubject` is caller-supplied text plus an in-tenant FK and is inherited by amendments/addenda/entered-in-error. Route to the clinical safety lead.
- **Deferred (physician reviewer, MEDIUM, simulated):** no draft-ownership guard for assistant edits, no care-relationship check on sign, and `encounter_id` has no composite FK until V12 lands (V15 documents the follow-up).
- **Deferred (physician reviewer, MEDIUM, simulated):** a signed original cannot be marked entered-in-error while an amendment draft is open (`current_clinical_note_revision()` returns the higher version). Needs a V15 successor-guard change and clinical review.
- **Decision — no skill/agent edits:** the trusted swarm integrator treats `.opencode/` changes in a story PR as `needs-human`, and this worker's scope forbids shared files. Deferred findings are the hand-off.

## Blockers / evidence genuinely missing (stop-and-report)

1. **No local PostgreSQL** (`psql`/`pg_isready` absent, no server on 5432). The `@Tag("integration")` suite — including `failedCommandAuditIsCommittedAfterRollback` — was compiled but **not executed** here. The integrator must run `bash apps/api/check.sh` on the exact merged SHA to confirm V15 apply-and-assert, forced RLS, append-only triggers, two-tenant concealment, the committed failure audit and JaCoCo for `NoteRepository`.
2. **SpotBugs is red locally for 5 pre-existing findings outside this scope.** They are recorded above and were not edited; the integrator's full gate should be read with that baseline (zero findings in `clinical.notes`).
3. **No clinical/human/patient evidence.** Reviewer findings came from simulated specialists who read code and did not re-execute checks. No simulated-user or real-patient flow was run; G5 site approval remains separate.
4. **No patient data, real identifiers or credentials** appear in any fixture or note; all subjects, patients, organizations and recipients are synthetic.
