# G1 clinic gate — evidence note

- Date: 2026-11-22 · Requirements: PRD-01, CLN-01 · Gate: G1 · Class: safety-critical ([risk routing](../WORKFLOW.md))
- Evidence source: executed tool commands (file reads and `git log` on the checked-out branch at HEAD `5c5e308e`) plus bounded simulated review. No human, clinical, privacy or Product Owner evidence is recorded here.
- Scope: this note only. No production code, migration, dependency, threshold or test was changed.

## Purpose

Section 9 of `SMARTCLINIC_V1_SPEC.md` (G1 — clinic) lists five required evidence sentences. This note
maps each sentence to a merged test, document or story that exists on `main` today and records its
status. It prepares evidence for a person to accept; it never records acceptance. Acceptance of G1
is a human Product Owner decision, and the clinical and privacy sign-offs that gate real use stay
human decisions (G5). A merged test proves an engineering behaviour, not that a person has accepted
it.

Only evidence merged on `main` is linked. A design document, simulated UAT script or naming a story
title alone is not treated as proof of a behaviour.

## Evidence matrix

| Criterion (spec §9, G1 row) | Merged evidence on `main` | Status |
|---|---|---|
| Simultaneous competing bookings enforce a single protected slot | `apps/api/src/test/java/com/aurevia/smartclinic/it/SchedulingBookingTest.java`: `concurrentBookingsLeaveExactlyOneWinner`, `rejectsSlotConflictAndKeepsSingleWinner`, `blocksBothClinicianAndRoomOverlapAndAllowsIndependentResources`. Store constraint: `db/migrations/V8__scheduling.sql` partial GiST `EXCLUDE` constraints `appointment_clinician_slot_excl` and `appointment_room_slot_excl`. Merged story S-G1-01 (CLN-01). | Proven |
| Stale draft cannot overwrite a signed record | `apps/api/src/test/java/com/aurevia/smartclinic/it/clinical/ClinicalNoteRevisionIntegrationTest.java`: `signedRevisionsCannotBeEditedOrReSigned`, `editDraftUsesVersionPreconditionAndKeepsOldRevision`, `staleVersionAuditIsCommittedAfterRollback`. Unit level: `apps/api/src/test/java/com/aurevia/smartclinic/it/clinical/ClinicalNoteServiceTest.java`: `updateDraftRejectsStaleVersionsImmutableStatesAndInvalidContent`, `staleVersionAttemptAuditsConflictInASecondCommittedTransaction`. Merged story S-G1-05 (CLN-05), PR #165. | Proven |
| Corrections preserve the signed original (and authorship) | `apps/api/src/test/java/com/aurevia/smartclinic/it/clinical/ClinicalNoteRevisionIntegrationTest.java`: `amendmentDraftAndAddendumPreserveTheOriginal`, `enteredInErrorPreservesPriorAndRecordsActorReasonAndRecipients`, `revisionRowsAreAppendOnlyForEveryRole`. Unit level: `ClinicalNoteServiceTest.amendmentAndAddendumPersistThePriorVersionLink`. Merged story S-G1-05 (CLN-05), PR #165. | Proven |
| English and Arabic PDFs validated | HTTP level: `apps/api/src/test/java/com/aurevia/smartclinic/it/documents/DocumentIsolationTest.java`: `rendersImmutableVersionedEnglishAndArabicPdfs` (LTR/RTL metadata, PDF header, distinct bytes, immutable stored revision, version history). Rendering level: `apps/api/src/test/java/com/aurevia/smartclinic/it/documents/PdfRendererTest.java`: `arabicIsRightToLeftWithVisuallyReorderedWords`, `englishIsLeftToRightAndDiffersFromArabic`, `arabicLineKeepsNumericValueAndUnitAsOneLtrRun`, `provenanceBindsTheValidatedSourceAssociation`, `identicalSpecsProduceIdenticalBytes`. Merged story S-G1-07 (CLN-07), PR #166. | Proven |
| Consolidation is all-or-nothing and blocked on unresolved conflicts | `apps/api/src/test/java/com/aurevia/smartclinic/it/patientidentity/ConsolidationServiceTest.java`: `consolidateBlocksAmbiguousDependenciesWithoutPartialWrite`, `consolidateFailsClosedWhenLockIsIncomplete`, `consolidateRequiresTwoIdentifiersConfirmedFromBothRecords`, `concurrentConsolidateConflictStaysA409UnderARealTransaction`. HTTP level: `apps/api/src/test/java/com/aurevia/smartclinic/it/patientidentity/ConsolidationIsolationTest.java`: `consolidationNeedsSecondReviewerAndUnblockedDependencies`, `reviewShowsConflictsDependenciesDryRunAndVisibility`, `staleVersionAndUnauthorizedRoleAreRejected`. Merged story S-G0-06 (IAM-04), PR #160. Related: `SchedulingBookingTest.seriesIsAllOrNothing` proves the recurring-booking series rolls back as one unit. | Proven |

No criterion is open for lack of merged evidence, so no task is listed as closing an evidence gap.
The acceptance actions and related open defects below are not evidence gaps and are not acceptance.

## Entry point to test trace

- **Protected slot.** HTTP `POST /api/v1/appointments` (`SchedulingBookingTest` drives a real server and
  disposable PostgreSQL). The database, not the service, is the final authority: V8's two partial GiST
  exclusion constraints make two concurrent live bookings for the same clinician or room unable to
  commit, so one request wins and the other returns a conflict.
- **Signed drafts.** HTTP `PUT /api/v1/clinical-notes/{id}` and `POST /api/v1/clinical-notes/{id}/sign`
  (`ClinicalNoteRevisionIntegrationTest`). The version precondition and the append-only signed
  revision reject a stale edit and keep the prior revision and its author.
- **Corrections.** HTTP `POST /api/v1/clinical-notes/{id}/amend`, `/sign-addendum` and
  `/entered-in-error` (same integration class). Each appends a linked revision; the signed original
  and its authorship are never mutated.
- **Localized PDFs.** HTTP `POST /api/v1/documents/rendered` and
  `GET /api/v1/documents/rendered/{id}` (`DocumentIsolationTest.rendersImmutableVersionedEnglishAndArabicPdfs`).
  `PdfRendererTest` asserts the byte-level bilingual output (RTL visual reordering, English LTR,
  bidi numeric runs, provenance, deterministic bytes).
- **Consolidation.** HTTP `POST /api/v1/duplicate-reviews/{a}/{b}/consolidate`
  (`ConsolidationIsolationTest`). Both records are locked, a second reviewer and unblocked
  dependencies are required, and a failure rolls back the whole unit with no partial write.

## Open items (not evidence gaps, not acceptance)

1. **Human acceptance of this gate remains open.** No Product Owner, clinical or privacy sign-off is
   recorded in this note or anywhere on `main`. `docs/operations/printing/uat.md` is a *simulated*
   clinician walkthrough (synthetic data), and `docs/design/rtl-and-localization.md` explicitly states
   that it is not clinical or human approval of a localization. A person must accept G1 using this
   evidence; a worker cannot. The task that prepares the signable pack is `GATE-PACK-G1`
   (`docs/gates/g1-evidence-pack.md`); the decision itself is a human Product Owner action that no
   worker task can close.
2. **Human validation of the printed English/Arabic PDFs remains a G5 action.** The merged tests prove
   deterministic bilingual rendering and immutable versioned output. They do not record that a person
   inspected a printed handout. Closing this is a human UAT/sign-off, owned by `S-G5-12` and prepared
   by `GATE-PACK-G5`, not a worker task.
3. **Related CLN-01 defects are open and narrow broader scheduling readiness**, but they do not negate
   the protected-slot criterion: `BUGS.md` entries `QA-SOURCE-05` (reschedule omits creation-path
   booking-window checks) and `QA-SOURCE-06` (a slot can span time outside the resource window).
   Reproductions are pending.
4. **CLN-07 render-path reachability.** tasks/ entry `AUD-20261121-19` (PR #504) tracked the
   unreachable `QuarantineService` / `PdfBindingService` command APIs and is marked `[x]` on `main`.
   The bilingual-rendering evidence above is the behaviour proven by the reachable document endpoints;
   audit and quarantine of uploads remain separate concerns.

## Reproduction

The evidence is the source and history of `main` at HEAD `5c5e308e`; each cited class and method was
read directly and its merge commit confirmed with `git log`. The documentation check for this note is:

```
python3 -I scripts/check-docs.py
python3 -I scripts/check-names.py
python3 -I scripts/check-secrets.py
```

The cited integration tests are PostgreSQL-backed (`@Tag("integration")`) and are run by the
integrator/Delivery full gate, not by this documentation worker. Their reproduction commands are the
module's canonical entry points `bash apps/api/check.sh` (full Java gate) and `bash db/check.sh`
(migrations and policies); no full verify was run here.

## Related evidence

- `SMARTCLINIC_V1_SPEC.md` §9 (G1 row) and requirements PRD-01, CLN-01, CLN-05, CLN-07, IAM-04.
- `docs/gates/g0-isolation-surfaces.md` — the G0 pattern this note follows (evidence, not acceptance).
- `docs/operations/printing/uat.md` — simulated bilingual print walkthrough (not a human record).
- `docs/archive/TODO-DONE.md` — merged G1 stories S-G1-01 … S-G1-08 and S-G0-06 with evidence lines.
