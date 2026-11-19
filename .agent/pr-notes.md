# S-G4-01 — Imaging order intake, urgency, accession, study association, signed interpretation and addenda (RAD-01)

## Requirement IDs and acceptance mapping

Primary: **RAD-01**. Supporting: **ARC-01, ARC-02, ARC-05, ARC-06, CLN-05, OPS-02, ADM-01, PRD-06, XCH-05** (read-back fact boundary). Source: `SMARTCLINIC_V1_SPEC.md` §5 RAD-01, §7 ARC-01..07, §8 OPS-02, §2 role table, §9 G4 gate evidence.

| RAD-01 acceptance behaviour | Implementation | Synthetic test evidence |
|---|---|---|
| Order intake captures urgency, clinical question, source, destination, referrer; STAT requires a named owner | `ImagingService.intake`, `RadRules.mayIntake/maySchedule`, `RadTypes.Urgency` | `ImagingServiceTest.intakeRequiresUrgencyClinicalQuestionAndNamedStatOwner`, `RadPolicyTest.roleBoundariesMirrorTheSectionTwoRoleTable` |
| Schedule records time and tenant-approved modality; version precondition and legal transitions | `ImagingService.schedule`, `RadPolicyCatalog.modalityApproved`, `OrderState.canTransitionTo` | `ImagingServiceTest.schedulingEnforcesApprovalVersionAndState`, `RadTypesTest.orderStateTransitionsAreExactlyTheImagingLifecycle` |
| Issuer-scoped accession uniqueness within a tenant | `ImagingService.accession` (issuer+accession key), `RadRules.mayAccession` | `ImagingServiceTest.duplicateOrderAndAccessionIdentifiersAreRejectedInOneTenant`, `twoTenantsWithIdenticalLocalIdentifiersRemainIsolated` |
| Verify patient/accession labels before study association; record Study Instance UID + partner workstation, no image pixels | `ImagingService.associateStudy`, `requireMatch`, `ImagingOrder` fields | `ImagingServiceTest.wrongPatientOrAccessionStudyAssociationIsRefused`, `RadDomainRecordsTest.imagingOrderCarriesApprovedModalityAndStudyReferenceWithoutImageBytes` |
| Signed interpretation with technique, comparison, findings, impression, follow-up; effective imaging privilege; immutable revision and checksum | `ImagingService.sign`, `RadPolicyCatalog.mayInterpret`, `Interpretation.checksum` (SHA-256 via `NoteChecksum`) | `ImagingServiceTest.signingEnforcesAssociationVerificationPrivilegeAndRequiredFields`, `fullLifecycle...`, `RadDomainRecordsTest.checksumIsAStableFixedLengthSha256DigestOfCanonicalContent` |
| Linked addenda preserve the signed original and notify the referrer | `ImagingService.addAddendum`, `supersedesVersion`, `ADDENDUM_LINKED` + `REFERRER_NOTIFIED` facts | `ImagingServiceTest.addendaRequireReasonAndCanChainWithoutEditingSignedContent`, `fullLifecycle...` |
| Critical communication and read-back as separate facts; no clinical narrative in operational events | `ImagingService.recordCriticalCommunication`, `RadEventType.CRITICAL_COMMUNICATION` / `READ_BACK`, SHA-256 `payloadDigest` | `ImagingServiceTest.criticalCommunicationRequiresAssociationAndReadBack`, `fullLifecycle...` |
| Tenant isolation, fail-closed authorization, concealment | tenant-keyed stores, role checks on every command, 404 concealment | `ImagingServiceTest.twoTenantsWithIdenticalLocalIdentifiersRemainIsolated`, `concealedAndPendingReadsFailClosed`, `studyCommandsDenyUnauthorizedAndUnprivilegedActors` |

Entry-point-to-test trace: `IntakeCommand → ImagingService.intake → RadRules/RadPolicyCatalog → ImagingOrder` (append-only revision) `→ RadEvent.ORDER_INTAKED`; `ScheduleCommand/AccessionCommand/AssociateStudyCommand → ImagingService.schedule/accession/associateStudy`; `SignCommand/AddendumCommand → ImagingService.sign/addAddendum → Interpretation.checksum → RadEvent.INTERPRETATION_SIGNED/ADDENDUM_LINKED → REFERRER_NOTIFIED`; `CriticalCommunicationCommand → ImagingService.recordCriticalCommunication → CRITICAL_COMMUNICATION + READ_BACK`. `ImagingServiceTest` calls the kernel directly. Per the established G4 pattern (sibling `s_g4_02` is also an in-memory kernel), this slice has no HTTP controller, migration or DB repository: **reserved V41 is intentionally unused** and `db/migrations` is outside the declared scope. Persistence, RLS, controller and integration checks are the integrator's full gate.

## Commands run and results

Evidence source labels: **tool** = command executed by this worker; **reviewer** = `.opencode` specialist report; **simulated user** = simulated radiology user (not human clinical acceptance).

Baseline (before this worker's edits) — tool:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → EXIT 0; 1463 tests, 0 failures/errors. The pre-existing in-scope kernel was complete and already at 100% coverage.

Test-first RED rounds — tool:
- After adding `RadDomainRecordsTest.checksumIsAStableFixedLengthSha256DigestOfCanonicalContent`: `mvn ... -Dtest=RadDomainRecordsTest` → EXIT 1, `expected: <64> but was: <266>` (the old checksum hex-encoded the full 266-char canonical content).
- After adding READ_BACK assertions: `mvn ... -Dtest=ImagingServiceTest,RadDomainRecordsTest,RadTypesTest` → EXIT 1, compilation error `cannot find symbol: variable READ_BACK` (missing fact behavior).

GREEN — tool:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0; 1464 tests, 0 failures, 0 errors, 0 skipped** (25 in `it.s_g4_01`: ImagingServiceTest 11, RadDomainRecordsTest 6, RadPolicyTest 5, RadTypesTest 4 — the other 3 G4/G1 suites contribute the remainder of the run).
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + XML counter check → **every `com/aurevia/smartclinic/g4/s_g4_01` class at 100% LINE, BRANCH, METHOD and CLASS** (0 missed counters), including `ImagingService` (line 252/252, branch 62/62, method 36/36) and `Interpretation`.
- `mvn -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false spotbugs:check` → EXIT 1 overall with **14 findings, none in `s_g4_01`**; all are pre-existing in other stories (`s_g4_02`, `s_g4_04`, `s_g3_05`, `s_g3_08`, `s_g3_10`, `g0/s_g0_07`).
- `python3 -I scripts/check-docs.py` → EXIT 0; `python3 -I scripts/check-secrets.py` → EXIT 0; `python3 -I scripts/check-migrations.py --require-baseline` → EXIT 0.
- `bash -n` on shell scripts was not re-run; no shell files changed.

Not run locally (worker contract — integrator owns DB/integration and the full gate): `bash apps/api/check.sh` (`mvn verify`, JaCoCo build-failing gate, SpotBugs, OWASP), `bash db/check.sh`, `scripts/check.sh`, image smoke, CI. No coverage result was fabricated; the 100% figure above is the local JaCoCo report for the fast tier only.

## Defects found and fixed (all test-first, all in scope)

1. **Content "checksum" was not a digest** — `Interpretation.checksum()` hex-encoded the entire length-prefixed canonical content (266 chars, reversible, unbounded) while its contract and `RadEvent.payloadDigest` describe a checksum/digest. Fixed to delegate to the repo's existing `NoteChecksum.sha256(String)` (64-char SHA-256), with a known-answer test.
2. **Critical read-back was required but discarded** — `recordCriticalCommunication` validated `readBack()` but stored nothing. Added `RadEventType.READ_BACK` as a separate durable fact with the recipient as destination and a SHA-256 digest of the read-back text.
3. **Raw clinical finding stored in a field documented as a digest** — `payloadDigest` held the literal finding text and a test asserted that literal. Both critical-communication and read-back events now carry SHA-256 digests only; tests assert no event holds either narrative (ARC-06 / OPS-02).
4. **Attestation omitted from the signed-content checksum** — Javadoc promised signature metadata coverage but `attestation` was not in the canonical input. Added it, with a sensitivity assertion and updated known-answer constant.
5. **Overclaiming study-association Javadoc** — "a study of a different patient can never be attached" is not provable by an in-memory kernel; reworded to state that only caller-supplied verified labels are checked and UID→patient binding belongs to upstream PACS/DICOMweb verification.

## Decisions

- Reused `g1.s_g1_05.NoteChecksum` for SHA-256 rather than adding a fourth local copy (`s_g3_12.EvidenceHash` and `PatientAccessService` already have their own). A comment in `Interpretation` records the intentional cross-story dependency; extracting a neutral crypto package would require out-of-scope edits. Failure path surfaces `ApiException CHECKSUM_UNAVAILABLE` from that shared utility.
- Reserved migration **V41 not created**: DB paths are outside the declared scope and the sibling G4 kernel (S-G4-02, V42) likewise has no migration.
- The critical finding narrative is deliberately not persisted in this kernel (only its digest). Residual for a later signed-artifact story: a one-way digest is only checkable if the authoritative original is retained elsewhere.
- `signedAt` remains excluded from the interpretation digest (documented; deterministic re-render), and unkeyed SHA-256 is a tamper/rendering check, not a digital signature.

## Review evidence

- **reviewer** (`smartclinic-senior-backend`): no blocking or functional findings; digest semantics and the independent known-answer test confirmed correct; one low-severity note on cross-story package coupling — addressed with the one-line comment. Reviewer did not re-run commands (no execute tool).
- **simulated user** (`smartclinic-radiology-user`): four findings (read-back discarded; raw finding in digest field; study-association overclaim; attestation omitted). Bounded re-review of the updated files marked **all four resolved**, with residual scope risks: UID→patient binding still requires the PACS/DICOMweb step (G4 launch prerequisite), no authoritative critical-finding artifact in this kernel, read-back digest proves presence not correctness of words, and no XCH-05 owner/due-time/escalation events here. Explicitly simulated review, **not human or clinical acceptance**.

## Out-of-scope / environment notes for the integrator

- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 1 failure: `test_list_bearing_records_copy_on_store_and_read` in `messaging/s_g3_11/PatientFlowContract.java` (1 `List.copyOf` where 2 expected). Not touched — outside this story's scope and pre-existing in the base tree.
- `python3 -I scripts/gen-opencode.py --check` and `python3 -I scripts/check-pack.py` → EXIT 1: worktree `opencode.json` SHA `279aa1e7…` equals `.github/opencode.ci.json` and carries skip-worktree flag `S`, while `git show HEAD:opencode.json` SHA is `14bba0da…`. This is the documented cloud-worker CI-profile trap (OPS-CW-003-01). **Did not regenerate or overwrite `opencode.json`** (root-level, out of scope, and would replace the CI profile with the local one). `bash scripts/quick-check.sh` stops at this check for the same reason.
- `bash scripts/docs.sh` → EXIT 1: "Install Doxygen to build the developer reference" — Doxygen is absent from this worker image; `check-docs.py` (link/ID validation) passes.
- Sibling stories' SpotBugs findings and the s_g3_11 convention failure are pre-existing in the base commit and must be handled by their own owners; none was caused by this story.

## Scope, data and commit

Changed files (all inside `apps/api/src/main/java/com/aurevia/smartclinic/g4/s_g4_01` or `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g4_01`): `ImagingService.java`, `Interpretation.java`, `RadEvent.java`, `RadTypes.java`, `ImagingServiceTest.java`, `RadDomainRecordsTest.java`. No tracker, migration, contract, build, workflow or documentation files were edited. Synthetic fixtures only; no patient data or credentials were used or recorded. All evidence is tool-generated, reviewer, or simulated-user; there is no human pilot evidence. Commit is scoped to the branch; no push, tag or `gh` call was made.
