## Clause

No autonomous care decisions, diagnostic image viewer, in-house PACS, controlled electronic prescribing, pharmacy inventory, full dispensing system, inpatient workflows or offline clinical editing.

## Status

Partly built

The exclusions are enforced in code for the clinical-decision and client/offline parts, with unit and HTTP-level tests. The partner-dependent parts (PACS and pharmacy interface contracts, market prescribing constraints) and the final site sign-off need evidence a repository cannot produce; none of that evidence is present here. Status is therefore not "Built and verified".

## Evidence

Requirement: [PRD-06](../../../../.opencode/config/PROJECT_V1_SPEC.md) (product boundary row, "Clinical users decide and attest to diagnosis, treatment and release"). All links below resolve to files in this repository; no external or invented artifacts are cited.

**1. No autonomous care decisions — code and tests found.**

- [SpecialtyAttestationService.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyAttestationService.java) states in its class Javadoc that "a person decides and attests, and the platform does not make autonomous clinical decisions (PRD-06)"; the decision class is derived from the attested action, never accepted from the caller.
- [SpecialtyDecision.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyDecision.java) defines only DIAGNOSIS, TREATMENT and RELEASE, and documents that a decision "is never caller-supplied on the attestation command".
- [SpecialtyActions.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyActions.java) makes separation of duties structural: radiology staff may associate a study but never sign an interpretation, and pharmacy staff "do not make a diagnosis, treatment or release decision"; only the four clinical decision roles may produce an attestation.
- [CommandCatalog.java](../../../../apps/api/src/main/java/com/aurevia/api/authorization/CommandCatalog.java) binds the `attestClinicalDecision` command to `SpecialtyActions.ATTEST`; [CommandAuthorization.java](../../../../apps/api/src/main/java/com/aurevia/api/authorization/CommandAuthorization.java) is the server-side gate that ignores client role/permission claims (PRD-06).
- [ClinicalNoteService.java](../../../../apps/api/src/main/java/com/aurevia/clinical/notes/ClinicalNoteService.java) requires an explicit signer attestation for `sign`/`signAddendum`; [NoteState.java](../../../../apps/api/src/main/java/com/aurevia/clinical/notes/NoteState.java) makes signed revisions immutable.
- Tests: [SpecialtyPolicyTest.java](../../../../apps/api/src/test/java/com/aurevia/it/authz/SpecialtyPolicyTest.java) (`attestationIsReservedForClinicalDecisionRoles`, `onlyClinicalDecisionActionsAreAttestableAndMapToPrd06Decisions`), [SpecialtyAuthorizationFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/authz/SpecialtyAuthorizationFlowTest.java) (HTTP plus real PostgreSQL), [CommandAuthorizationTest.java](../../../../apps/api/src/test/java/com/aurevia/api/authorization/CommandAuthorizationTest.java) ("PRD-06/IAM-01 server-side command authorization: fail closed, ignore client claims").

**2. No diagnostic image viewer and no in-house PACS — no viewer class found; boundary enforced in the imaging domain.**

- [ImagingService.java](../../../../apps/api/src/main/java/com/aurevia/radiology/ImagingService.java) class Javadoc: "External images are never fetched or copied. Only the Study Instance UID and a partner diagnostic workstation reference are recorded, so images remain under partner access control." Its `associateStudy` Javadoc says image pixels are never retrieved and that binding the Study Instance UID to a patient is the partner PACS/DICOMweb verification step, not this service.
- [ImagingController.java](../../../../apps/api/src/main/java/com/aurevia/radiology/ImagingController.java) exposes only `/api/v1/imaging/orders` intake, get, study association and study-link review commands. There is no image-retrieval, DICOMweb proxy or pixel-serving route (no `@GetMapping` returning image bytes in the radiology package).
- Tests: [StudyLinkReviewTest.java](../../../../apps/api/src/test/java/com/aurevia/radiology/StudyLinkReviewTest.java) ("mismatch never links and opens one open review"), [ImagingServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/radiology/ImagingServiceTest.java) (`LABEL_MISMATCH` and workstation reference assertions), [ImagingControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/radiology/ImagingControllerHttpTest.java) (`mismatchQueuesAReviewAndReLinkAfterReviewThroughHttp`).
- Repo-wide search: no `image viewer` / `imageViewer` identifier or class. `PACS`/`DICOM` occur only as partner references in the two files above (and in synthetic test workstation strings). [contracts/openapi.yaml](../../../../contracts/openapi.yaml) has no imaging or DICOM endpoint.

**3. No controlled electronic prescribing — no controlled-substance model or class found.**

- [RxChannel.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxChannel.java) defines exactly two approved channel families, `E_PRESCRIBE` and `PRINTABLE_LOCAL`; a printable channel "produces an immutable document only and never implies electronic delivery or pharmacy acceptance".
- [RxContract.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxContract.java) keeps each clinically significant fact as a separate immutable event: "Acceptance is never dispensing, delivery is never acceptance, and a locally printable prescription is never reported as electronically delivered." (The phrase "controlled prescription transmission slice" in its Javadoc describes the guarded workflow, not controlled substances.)
- [RxActions.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxActions.java): prescription creation, attestation, transmission and verification are clinician-only; pharmacy response and dispensing evidence are pharmacy-staff-only and "can never be recorded by a prescriber".
- Repo-wide search: no controlled-substance, DEA, EPCS, schedule or narcotic type, field or column. Test: [RxTransmissionFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/prescription/RxTransmissionFlowTest.java).

**4. No pharmacy inventory and no full dispensing system — no inventory/stock/warehouse class or table found.**

- [RxService.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxService.java) `recordDispensing` only appends a partner-supplied `DISPENSING_EVIDENCE` event and sets the `DISPENSED` state; it does not manage stock, lots, orders or a dispense queue.
- [RxContract.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxContract.java) `DISPENSED` is defined as "The pharmacy supplied dispensing evidence, distinct from acceptance".
- [SpecialtyActions.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyActions.java) limits pharmacy staff to `PHARMACY_REVIEW` and `PHARMACY_STATUS`. Test: [RxTransmissionFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/prescription/RxTransmissionFlowTest.java) (dispensing evidence and invalid-transition cases).
- Repo-wide search: the only "inventory" hits are unrelated repository/secret inventories in documentation, not pharmacy stock.

**5. No inpatient workflows — no class, table or workflow found.**

- The PRD-01 row of [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) sets the ambulatory-care boundary. A case-insensitive search for `inpatient` / `in-patient` across `apps`, `contracts`, `docs`, `infra`, `db` and `scripts` returns no feature match (the only hit is the local variable `syntheticWalkInPatient` in a scheduling fixture). The `discharge` references in [V77__therapy_episode.sql](../../../../db/migrations/V77__therapy_episode.sql) are physical-therapy episode discharge notes (PT-01), not inpatient admission/discharge; no bed, ward or admission table exists.

**6. No offline clinical editing — no offline editor or local clinical write path found.**

- [transport.ts](../../../../apps/staff-web/src/api/transport.ts) retries only `GET` on offline/timeout; a failed `POST` is surfaced as a `TransportError` and never queued for later editing. Test: [transport.test.ts](../../../../apps/staff-web/src/api/transport.test.ts) ("maps a network failure to offline and does not retry a POST").
- [screenStates.tsx](../../../../apps/patient-mobile/src/app/screenStates.tsx) renders `offline` as an alert with an optional retry control, not an editable form. Test: [screenStates.test.tsx](../../../../apps/patient-mobile/src/app/screenStates.test.tsx).
- [clinicalCache.ts](../../../../apps/patient-mobile/src/api/clinicalCache.ts) holds clinical payloads only in process memory; [clinicalCache.test.ts](../../../../apps/patient-mobile/src/api/clinicalCache.test.ts) asserts clinical data is never written to persistent storage and a new process starts empty.

**Evidence that is NOT in the repository (human/partner/signed).**

- [launch-checklist.md](../../../../docs/operations/production-readiness/launch-checklist.md) states "Every row is `open`" and "asserts nothing as done"; the per-site Product Owner and clinical sign-off row is `open` with `none yet` as its link.
- [TODO.md](../../../../TODO.md) leaves `S-G5-04` (Laboratory, PACS and pharmacy interface contracts, credentials and outage drills), `S-G5-01` (market policy pack) and `S-G5-12` (per-site launch approvals and final G5 acceptance) as `[ ]`. These are plans, not completion evidence.

## Gap

What is missing is verification evidence, not most of the code:

1. **Partner PACS boundary.** The code records only a Study Instance UID and workstation reference, but there is no partner interface contract, credential record or outage drill proving images stay in the partner PACS. Change/contract: a signed PACS interface conformance artifact and the S-G5-04 decision pack (human/partner). **1 task** (`S-G5-04`, open; owner: Integration lead and partner owner per section 10 of the specification).
2. **Prescribing constraints.** There is no market policy pack or market entitlement/controlled-substance exclusion decision, and no pharmacy interface contract or channel approval. **2 tasks** (`S-G5-01` market policy pack, owner Product owner and privacy lead; `S-G5-04` pharmacy interface contract, owner Integration lead and partner owner).
3. **Site acceptance.** No signed acceptance matrix records that these eight exclusions were reviewed and accepted for a site. **1 task** (`S-G5-12`, owner Product Owner / clinical lead).
4. **Optional code-level regression guard.** No automated check fails the build if a future change adds an image-viewer/DICOM route, a controlled-substance field, an inventory table, an inpatient workflow or an offline edit queue. A bounded static policy check (new file under `scripts/` with its tests) would keep the negative boundary enforceable; this note does not add it. **1 task**, separate from the human evidence above.

Total: **5 separate tasks** — 4 human/partner/signed tasks that cannot be completed by a code worker, and 1 optional code-and-test task. No production code, workflow, contract or migration was changed by this note.

## How to verify

Code-level checks a reviewer can run now (all synthetic; no patient data):

1. Confirm the absence claims with a repository search:
   `grep -RniE 'image viewer|imageViewer|controlled substance|EPCS|DEA|\binpatient\b' apps/api/src/main apps/staff-web/src apps/patient-mobile/src db/migrations contracts` — expect no feature matches. `grep -RniE 'PACS|DICOM' apps/api/src/main/java` — expect only the two partner-reference Javadoc/parameter lines in `ImagingService.java` and `ImagingController.java`.
2. Run the canonical API gate: `bash apps/api/check.sh`, then open the report for `SpecialtyPolicyTest`, `SpecialtyAuthorizationFlowTest`, `CommandAuthorizationTest`, `ImagingServiceTest`, `StudyLinkReviewTest`, `ImagingControllerHttpTest` and `RxTransmissionFlowTest`. These assert human-only attestation, the PRD-06 decision mapping, study-link mismatch refusal and partner-supplied dispensing evidence.
3. Run the frontend gates: `bash apps/staff-web/check.sh` and `bash apps/patient-mobile/check.sh`; confirm `transport.test.ts`, `screenStates.test.tsx` and `clinicalCache.test.ts` pass (offline POST not retried, read-only offline state, no persistent clinical cache).
4. Validate this note: `python3 -I scripts/check-docs.py` (exit 0; links and requirement IDs).

Human evidence a site must produce before G5 (repository currently holds none of it):

- A signed market policy pack stating the prescribing constraints and that controlled-substance e-prescribing is out of scope (`S-G5-01`).
- Signed partner laboratory/PACS/pharmacy interface contracts, approved credentials and outage drills showing images remain in the partner PACS and dispensing evidence is partner-supplied only (`S-G5-04`).
- A signed per-site acceptance matrix and final G5 acceptance record explicitly covering these eight exclusions, with no unresolved patient-safety defect (`S-G5-12`). Each row of `docs/operations/production-readiness/launch-checklist.md` must move from `open` to signed by its named owner; an agent cannot close it.
