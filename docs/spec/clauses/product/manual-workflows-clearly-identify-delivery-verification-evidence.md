# Manual workflows must clearly identify delivery and verification evidence

## Clause

Manual workflows must clearly identify delivery and verification evidence.

## Status

Partly built

The repository builds and synthetically verifies the *code and documentation* that keep delivery evidence distinct from verification evidence. It does not hold a real site's manual-workflow artifact showing that distinction in practice. Per the task rule, the human/partner portion is **Not built** in this repository and must be supplied by named site owners before G5; the code/documentation portion is built and synthetically verified, hence the overall Partly built status.

## Evidence

The clause is the PRD-02 boundary sentence in [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) (section 1) and belongs to the closed-loop order, result, referral and prescription outcome (sections 1 and 4: XCH-01 through XCH-07). The requirement's concrete evidence rules are XCH-05 (track attempts, recipient, read-back, acknowledgment and follow-up as separate events; delivery and patient release do not satisfy clinician acknowledgment), RX-02 (delivery, acceptance and dispensing evidence are separate; verify partner status before retrying an ambiguous transmission) and OPS-05 (downtime and recovery runbooks).

Code and tests that implement and verify the separation (all paths exist today):

- [CriticalContract.java](../../../../apps/api/src/main/java/com/aurevia/exchange/critical/CriticalContract.java): `EventType` models `VERIFIED`, `ESCALATION_ATTEMPT` (delivered/failed), `READ_BACK`, `ACKNOWLEDGED`, `PATIENT_RELEASED` and `ROSTER_GAP` as separate immutable facts; its Javadoc states "delivery or patient release never advances the clinical acknowledgment state". `Result` carries `primaryChannel`, `backupChannel`, `backupRecipient` and `readBackRequired`.
- [CriticalResultService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/critical/CriticalResultService.java) and [CriticalResultController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/critical/CriticalResultController.java): the XCH-05 commands (verify, escalate, read-back, acknowledge, follow-up, release) are separate routes, each appending its own event.
- [CriticalResultFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangeresult/CriticalResultFlowTest.java): `fullWorkflowKeepsEveryCriticalFactSeparate`, `deliveryAndPatientReleaseNeverAcknowledge` (a delivered attempt plus patient release leaves the state `awaiting_ack`, and acknowledgment stays 409 until read-back), and `overdueAndSystemDownUseApprovedBackupChannel`. [CriticalResultControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangeresult/CriticalResultControllerHttpTest.java) exercises the same routes at HTTP level.
- [ImagingService.java](../../../../apps/api/src/main/java/com/aurevia/radiology/ImagingService.java): `recordCriticalCommunication` rejects a missing read-back (`READ_BACK_REQUIRED`) and appends `CRITICAL_COMMUNICATION` and `READ_BACK` as two events, keeping finding/read-back narratives as digests only (RAD-01, XCH-05).
- [RxContract.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxContract.java): Javadoc states "Acceptance is never dispensing, delivery is never acceptance, and a locally printable prescription is never reported as electronically delivered"; `State.PRINTED` is the manual channel with no submission id. [RxService.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxService.java), [RxController.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxController.java) and [RxActions.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxActions.java) record dispensing evidence as a distinct event. [RxTransmissionFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/prescription/RxTransmissionFlowTest.java) asserts "acceptance must not create dispensing evidence". [V44__prescription_transmission_transmission.sql](../../../../db/migrations/V44__prescription_transmission_transmission.sql) keeps response, acceptance and dispensing evidence separate in storage.
- [LabTypes.java](../../../../apps/api/src/main/java/com/aurevia/lab/result/LabTypes.java): `VerificationState` Javadoc separates verification from patient release and clinician acknowledgment; `TriggerType` events are facts for a later outbox publisher and do not satisfy a clinical acknowledgment (LAB-02, XCH-04). [LabAccessionService.java](../../../../apps/api/src/main/java/com/aurevia/lab/accession/LabAccessionService.java) states a transmission acknowledgment is never a specimen verification.

Documentation that defines the manual workflows and names the evidence fields:

- [operational-runbooks.md](../../../operations/recovery/operational-runbooks.md), section `critical_contacts`: "Record each attempt, the recipient, the original deadline, any required read-back and the confirmed receipt as separate events." The `paper_manual_capture` and `supervised_back_entry` topics require original timestamps, entering actor and duplicate reconciliation; the runbook also states that delivery and patient release never satisfy the XCH-05 clinician acknowledgment. This is the clearest repository statement of the clause, but it is a declared procedure, not a completed artifact.
- [recovery-evidence.md](../../../operations/recovery/recovery-evidence.md) and [recovery_evidence.py](../../../../infra/recovery/recovery_evidence.py): the OPS-04/OPS-05 artifact contract. The evaluator checks `verification.before_pilot` / `quarterly_cadence` and that `runbooks` names all four topics; it does not validate delivery-versus-verification fields inside a filled manual-workflow record.
- [evidence-contract.md](../../../operations/delivery-safety/evidence-contract.md) and [evidence-contract.md](../../../operations/ops-queues/evidence-contract.md): both state that `final_evidence: true` records a claim, that a synthetic rehearsal is not site operation, and that independent verification is a human/cloud G5 gate. The synthetic fixtures under [infra/delivery-safety/fixtures](../../../../infra/delivery-safety/fixtures) are rehearsals only.
- [launch-checklist.md](../../../operations/production-readiness/launch-checklist.md): the OPS-05 row requires a published rota, the four runbook topics and an actual escalation record; the G5 row requires a signed site acceptance matrix. Every row is `open` and no row is closed by a repository check.
- [delivery.yml](../../../../.github/workflows/delivery.yml) is the delivery pipeline that runs policy, Java/database, documentation and image-smoke verification on each commit. No specific Actions run ID was gathered during this documentation-only task, so no workflow-run evidence is claimed.

## Gap

What is missing:

1. No real site artifact in the repository demonstrates a manual workflow recording delivery evidence and verification evidence as separate fields (attempt, recipient, deadline, read-back, confirmed receipt, actor and timestamp). The runbooks define these fields, but only synthetic rehearsal fixtures exist for OPS-04/OPS-05, OPS-06 and ADM-02; those explicitly flag `final_evidence: false` or record a claim rather than a performed event.
2. The machine-checkable envelope does not validate the distinction. `REQUIRED_RUNBOOKS` in `infra/recovery/recovery_evidence.py` checks topic names only; it does not check that a filled critical-contact record separates delivered/confirmed-receipt from read-back/acknowledgment. The RX-02 `PRINTED` manual channel records that a printable prescription was produced but has no separate field for a manual handover's delivery confirmation and verification.
3. No approved site policy pack in the repository names the approved backup, urgent and downtime channels or who may supply verification evidence. That is a signed ADM-01 configuration decision plus a G5 site clinical/operational acceptance gate, not code.
4. Nobody has produced a conducted downtime drill rehearsing paper/manual capture, patient identification, critical contacts and supervised back-entry with the evidence artifact `recovery-evidence.md` requires.

Files that would change to close the code/schema part: `infra/recovery/recovery_evidence.py` and its `tests/test_recovery_evidence.py` (validate delivery/verification fields in the artifact), `docs/operations/recovery/recovery-evidence.md`, the RX-02 contract and migration under `apps/api/src/main/java/com/aurevia/prescription/rx` and `db/migrations`, plus their HTTP-level tests under `apps/api/src/test/java/com/aurevia/it/prescription`.

Separate tasks needed: **three**. (1) Define and validate a manual-workflow evidence artifact schema whose delivery fields and verification fields are distinct and fail closed (code/tests). (2) Extend the RX-02 printable/manual path and the XCH-05 backup-contact path with explicit delivery-confirmation and verification evidence fields and HTTP-level tests (code/tests). (3) A human/partner task for the approved ADM-01 channel policy, a real downtime drill artifact and the G5 signed acceptance record; owner: site clinical lead and operations owner, with the Product Owner / Clinical lead signing G5.

## How to verify

Commands a reviewer runs now (repository evidence only):

```
python3 -I scripts/check-docs.py
python3 -I -m unittest discover -s scripts/tests -q
bash infra/delivery-safety/check.sh
python3 -I -m unittest discover -s infra/delivery-safety/tests -q
mvn -q -B -f apps/api/pom.xml test -Dtest=CriticalResultFlowTest,CriticalResultControllerHttpTest,RxTransmissionFlowTest
```

The last command needs the disposable PostgreSQL fixture the API integration tests use; the integrator and Delivery run the full `bash apps/api/check.sh` gate. The targeted tests assert that a delivered attempt and a patient release do not acknowledge (`deliveryAndPatientReleaseNeverAcknowledge`) and that acceptance does not create dispensing evidence (`RxTransmissionFlowTest`). These commands verify the *harness*, not a performed manual workflow.

Human evidence a site must produce before G5 (not in this repository):

- Site clinical lead and operations owner: a published on-call rota with named operational and clinical coverage owners and a recorded critical-alert escalation trail (launch-checklist OPS-05 row), and a conducted downtime drill artifact covering the four runbook topics that records each attempt, recipient, original deadline, required read-back and confirmed receipt as separate events (recovery-evidence.md, operational-runbooks.md).
- Tenant admin / privacy owner: the approved, effective-dated ADM-01 policy pack naming the approved backup, urgent and downtime channels and who may record verification evidence.
- Pharmacy partner: the RX-02 partner-status verification record for any ambiguous transmission and the manual handover receipt for a printable prescription.
- Product Owner / Clinical lead: the signed G5 site acceptance matrix with no unresolved patient-safety, tenant-isolation, record-integrity or critical-escalation defect (launch-checklist G5 row).
