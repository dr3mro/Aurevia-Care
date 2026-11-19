# S-G1-05 — CLN-05: draft → signed, amendments, signed addenda and entered-in-error with preserved originals

## Scope decision (important)

The dispatch text and the `TODO.md` entry name `apps/api/src/main/java/com/aurevia/smartclinic/chart/signing` and `apps/api/src/test/java/com/aurevia/smartclinic/it/chart/signing`. That path is stale: the repository-wide package refactor recorded in `.agent/pr-notes/REFACTOR-PACKAGES-20261119-201815.md` maps `g1/s_g1_05` → `clinical/notes`, with its tests in `it/clinical`. This is the same class of stale dispatch path handled in `.agent/pr-notes/S-G2-02.md`; AGENTS.md requires business-domain package names, so this attempt stays in `clinical/notes` / `it/clinical` and does not create a duplicate `chart/signing` package.

The reserved migration `V15__clinical_note_signing.sql` was already present at the base commit (`f3087b7`) and is unchanged by this attempt. It is `Change: expand`, so `scripts/check-migrations.py --require-baseline` remains green. No other migration version was touched.

## Requirement IDs and acceptance mapping

| CLN-05 clause | Implementation (inherited + this attempt) | Executed evidence |
|---|---|---|
| Draft → signed with required sign permission, signer attestation, correct patient context, draft version and required fields | `ClinicalNoteService.createDraft/updateDraft/sign`, `IfMatch.parse`, `NoteInputs`, `NoteChecksum`, V15 state CHECK and succession guard | `ClinicalNoteServiceTest.sign*`, `signingAppendsImmutableSignedRevisionWithProvenance`, `signPersistsTheExactSignedRevisionInput`; `ClinicalNoteRevisionIntegrationTest.signingRecordsMetadataAndPreservesTheDraft`, `signedRevisionsCannotBeEditedOrReSigned`, `signValidatesAttestationPatientAndVersion` (DB, integrator) |
| Separate amendment drafts and signed addenda; signed originals preserved | `createAmendmentDraft`, `signAddendum`, V15 append-only ledger and `supersedes_version` guard | `amendmentAndAddendumPersistThePriorVersionLink`; `amendmentDraftAndAddendumPreserveTheOriginal` (DB, integrator) |
| Entered-in-error preserves the prior revision and records actor, reason and affected recipients | `markEnteredInError`, V15 `entered_in_error` CHECK and trigger-derived actor | `enteredInErrorPreservesPriorRevisionAndRecordsReasonAndRecipients`, `enteredInErrorPersistsReasonRecipientsAndPreservedContent`; `enteredInErrorPreservesPriorAndRecordsActorReasonAndRecipients` (DB, integrator) |
| Save signer, effective time, signed time, content checksum and template version | DB-trigger-owned signer/checksum; service-owned signed/effective instants; immutable `ClinicalNoteRevision` | `signingAppendsImmutableSignedRevisionWithProvenance`, `signingIntroducesNoReleaseOrRenderingState` |
| Signing differs from patient release and PDF rendering | No release/render fields, methods or routes in the signing slice | `signingIntroducesNoReleaseOrRenderingState`; `noReleaseOrRenderingStateIsIntroduced` (DB, integrator) |
| **This attempt A — future-dated `effectiveAt` must be a 422 at the trust boundary, not a 500 on the irreversible sign** | `NoteInputs.instantOrNow` rejects instants after the injected clock; V15 `CHECK (signed_at IS NULL OR effective_at IS NULL OR signed_at >= effective_at)` is therefore never violated by an accepted draft | New red-first `inputsRejectMalformedValuesAtTheTrustBoundary` assertion and `createDraftRejectsFutureEffectiveAtBeforeAnyWrite` |
| **This attempt B — an audited denial must carry the requested resource id (OPS-02)** | `ClinicalNoteService.get` passes `id` to `authorize` instead of the literal `"READ"` | New red-first `readDenialAuditsTheRequestedNoteId` |

Supporting IDs: ARC-01 (tenant-owned `(organization_id, id, version)` revisions), ARC-02 (forced RLS, non-owner role), ARC-03/04 (`If-Match` 428/422/412), OPS-02 (transactional append-only audit), PRD-06 (server-side authorization), IAM-01/02 (membership and roles re-derived per request).

## Files changed this attempt

- `apps/api/src/main/java/com/aurevia/smartclinic/clinical/notes/NoteInputs.java` — `instantOrNow` now parses the instant once and rejects a value after the authoritative clock with `422 INVALID_EFFECTIVE_AT`; Javadoc updated with the V15 rationale.
- `apps/api/src/main/java/com/aurevia/smartclinic/clinical/notes/ClinicalNoteService.java` — `get` passes the note id to `authorize` so a committed `NOTE_READ/DENIED` audit names the resource.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/clinical/ClinicalNoteServiceTest.java` — three test changes: future-instant boundary assertion, a service-level draft-creation test, and a denial-audit resource-id test; the one pre-existing assertion that used a 2026 instant with a 2020 clock now uses a consistent clock.
- `.agent/pr-notes/S-G1-05.md` — this file. No tracker, shared config, generated file or migration was edited.

## Entry-point-to-test trace

`POST /api/v1/clinical-notes` → `ClinicalNoteController.create` → `ClinicalNoteService.createDraft` → `NoteInputs.instantOrNow` → guarded `TransactionTemplate` → `NoteRepository.insert` → V15 triggers → 201. `GET /api/v1/clinical-notes/{id}` → `ClinicalNoteController.get` → `ClinicalNoteService.get` → `authorize(context, READ, id)` → `AuditAppender.append` → 200/404/403. `ClinicalNoteServiceTest` drives both entry points with a mocked `TransactionTemplate` and a fixed clock; `ClinicalNoteRevisionIntegrationTest` drives the HTTP + PostgreSQL path (integrator).

## Test-first sequence (tool evidence)

1. Added the future-instant assertion, `createDraftRejectsFutureEffectiveAtBeforeAnyWrite` and `readDenialAuditsTheRequestedNoteId` to `ClinicalNoteServiceTest`.
2. RED: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ClinicalNoteServiceTest test` → **exit 1**, `Tests run: 29, Failures: 3`. Failures: future instant returned instead of throwing; draft creation returned 412 instead of 422; denial audit wanted resource id `NOTE` but got `READ`. One pre-existing test then failed because it paired a 2026 instant with a 2020 clock — its assertion was corrected to use a consistent clock without changing its intent.
3. Applied the two production fixes above.
4. GREEN: same targeted command → **exit 0**, `Tests run: 29, Failures: 0, Errors: 0`.

## Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ClinicalNoteServiceTest test` | RED exit 1 (29 tests, 3 failures) → GREEN exit 0 (29 tests, 0 failures/errors) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast clean test` | **exit 0**; Surefire aggregate **1687 tests, 0 failures, 0 errors, 0 skipped** (baseline 1685; +2 new test methods) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.xml` inspection | `ClinicalNoteService` 0 missed/216 instructions, 0/60 branches, 0/216 lines, 0/28 methods; `NoteInputs` 0/47 lines, 0/48 branches, 0/10 methods; controller, state, records, checksum and view all 0 missed. `NoteRepository` is exercised only by the PostgreSQL suite run by the integrator. |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -DskipTests spotbugs:check` | **exit 0** |
| `python3 -I scripts/check-migrations.py --require-baseline` | **exit 0** (V15 unchanged, `Change: expand`) |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py` | **exit 0** each |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests OK |
| `git diff --check` | **exit 0** |
| `bash scripts/quick-check.sh` | **exit 1 at the first gate**: `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Pre-existing cloud-worker artifact (see BUGS `OPS-CW-003-01`, MEMORY): `opencode.json` is under `skip-worktree` and the checked-out file is the CI profile. It stops the script before Java tests, which were therefore run directly as above. No `opencode.json` change was committed. |

Evidence source for all rows above: executed tool commands (tool). Local machine is JDK 21.0.13 / Maven 3.10.0. `git ls-files -v opencode.json` shows `S` (skip-worktree), so any worktree pack check would measure the CI profile, not HEAD; no pack check result is used as evidence here.

## Defects found and decisions

- **Fixed (medium, CLN-05 sign integrity):** `NoteInputs.instantOrNow` accepted any parseable future instant. Because V15 enforces `signed_at >= effective_at`, such a draft could never be signed; the sign command surfaced a database integrity exception instead of a 4xx, and `updateDraft` cannot change `effectiveAt`, so the draft was permanently unrecoverable. Now rejected at the trust boundary with 422.
- **Fixed (low, OPS-02):** a `NOTE_READ/DENIED` audit row recorded `resource_id = "READ"` instead of the requested note id, making the denial untraceable. Now records the note id.
- **Deferred — correction notification evidence (physician reviewer, HIGH, simulated finding):** entered-in-error accepts a client-supplied `affectedRecipients` list and stores it, but emits no outbox correction task or delivery/failure evidence. Fixing this needs the XCH-04/X-02 outbox and a clinical/privacy decision on recipient derivation; it is not a contained change in this slice. Recommend a separate story with a named clinical owner. Not implemented here.
- **Deferred — clinical author attribution (physician reviewer, MEDIUM-HIGH, simulated finding):** `clinicalAuthorSubject` is caller-supplied text validated only as a bounded string plus an in-tenant FK, and is inherited by amendment drafts, addenda and entered-in-error revisions. Route to the clinical safety lead; not changed here.
- **Deferred — author-edit before signature and role-only sign scope (physician reviewer, MEDIUM, simulated):** no draft-ownership guard for assistant edits, no care-relationship check on sign, and `encounter_id` has no composite FK until V12 lands. Slice-wide/spec decisions, not contained changes.
- **Deferred — signed original cannot be marked entered-in-error while an amendment draft is open (physician reviewer, MEDIUM, simulated):** `current_clinical_note_revision()` returns the highest version, so the amendment draft becomes the head and `markEnteredInError` rejects it. Needs a V15 successor-guard change and clinical review; DB tests cannot run locally. Not changed here.
- **Deferred — failure-path audit rollback (senior-backend reviewer, MEDIUM, simulated finding):** `load`, `requirePatient` and `append` write a `CONFLICT`/`NOT_FOUND` audit row and then throw inside `TransactionTemplate`, so the row is rolled back; only `authorize` denials commit. A correct fix is a small refactor across all mutation commands and belongs with an OPS-02 audit story; not changed here.
- **Decision — no skill/agent edits:** AGENTS.md asks for self-improvement on observed gaps, but the trusted swarm integrator treats `.opencode/` changes in a story PR as `needs-human`, and this worker's explicit scope forbids shared files. The deferred findings above are the hand-off for that follow-up.

## Blockers / evidence genuinely missing (stop-and-report)

1. **No local PostgreSQL.** `pg_isready` is absent and no local database is reachable, so the `@Tag("integration")` suites (`ClinicalNoteRevisionIntegrationTest`, `db/check.sh`) were not executed here. The integrator must run `bash apps/api/check.sh` on the exact merged SHA to confirm V15 apply-and-assert, forced RLS, append-only triggers and two-tenant concealment, and to record JaCoCo for `NoteRepository`.
2. **Pack gate is red locally for an environment reason.** `scripts/quick-check.sh` stops at `check-pack: opencode.json is stale` because the cloud worker checks out the CI profile under `skip-worktree`; the committed local profile equals the generator output (BUGS `OPS-CW-003-01`). This is outside this story's scope and must be handled by the owner/integrator, who measure HEAD.
3. **No clinical or human participant evidence.** Reviewer findings came from simulated senior-backend and physician specialists who read code and did not re-execute checks. No simulated-user or real-patient flow was run; G5 site approval remains separate.
4. **No patient data, real identifiers or credentials** appear in any fixture or note; all subjects, patients, organizations and recipients are synthetic.
