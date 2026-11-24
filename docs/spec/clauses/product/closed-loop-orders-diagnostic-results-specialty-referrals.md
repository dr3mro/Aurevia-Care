# PRD-02 — Closed-loop orders, diagnostic results, specialty referrals and ordered prescription communication

Evidence note for clause 1 of PRD-02. Requirement text and boundary are in
[PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) (sections 1, 4 and 5; gates G2/G4 in section 9; the partner-interface decision in section 10).

## Clause

Closed-loop orders, diagnostic results, specialty referrals and ordered prescription communication.

## Status

Partly built

The in-repository software implementation of the four flows exists and is covered by
tests, Flyway migrations, OpenAPI entries and synthetic partner fixtures. The clause
boundary is not satisfied: "Partner-specific interfaces require approval" and "Manual
workflows must clearly identify delivery and verification evidence" are not backed by
any approval, conformance report, credential set, reconciliation record, outage drill
or signed delivery/verification evidence in this repository (the only decision records
are tenant isolation, migration change-safety and in-memory-store persistence; none
covers a lab, PACS or pharmacy interface contract). Per section 10, the accountable
roles for that missing evidence are the Integration lead and partner owner (lab, PACS
and pharmacy interface contracts) and the Clinical safety lead and site medical
director (clinical dictionaries, escalation and coverage). G5 site approval is a human
gate and is not present here.

## Evidence

Implementation, by flow (all paths are relative to this file):

- Closed-loop orders (XCH-01, XCH-03): [OrderController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/order/OrderController.java), [OrderService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/order/OrderService.java), and the accept/reject/version flow in [ExchangeResponseService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/response/ExchangeResponseService.java).
- Separate workflow / transmission / clinical-review states (XCH-02): [ExchangeStateMachine.java](../../../../apps/api/src/main/java/com/aurevia/exchange/task/ExchangeStateMachine.java), [WorkflowState.java](../../../../apps/api/src/main/java/com/aurevia/exchange/task/WorkflowState.java), [TransmissionState.java](../../../../apps/api/src/main/java/com/aurevia/exchange/task/TransmissionState.java), [ClinicalReviewState.java](../../../../apps/api/src/main/java/com/aurevia/exchange/task/ClinicalReviewState.java), [ExchangeTaskService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/task/ExchangeTaskService.java).
- Diagnostic result routing and reconciliation (XCH-04): [ResultRoutingService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRoutingService.java), [ResultRules.java](../../../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRules.java).
- Critical-result escalation and human acknowledgment (XCH-05): [CriticalResultService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/critical/CriticalResultService.java), [CriticalResultController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/critical/CriticalResultController.java).
- Patient release and correction/withdrawal (XCH-06): [ReleaseService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseService.java), [PatientReleaseController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/PatientReleaseController.java).
- Observation trending with unit/qualifier comparability (XCH-07): [TrendAnalyzer.java](../../../../apps/api/src/main/java/com/aurevia/exchange/trend/TrendAnalyzer.java), [ComparabilityRule.java](../../../../apps/api/src/main/java/com/aurevia/exchange/trend/ComparabilityRule.java).
- Laboratory referral and reported results (LAB-01, LAB-02): [LabAccessionService.java](../../../../apps/api/src/main/java/com/aurevia/lab/accession/LabAccessionService.java), [LabResultService.java](../../../../apps/api/src/main/java/com/aurevia/lab/result/LabResultService.java).
- Radiology referral and signed interpretation (RAD-01): [ImagingService.java](../../../../apps/api/src/main/java/com/aurevia/radiology/ImagingService.java), [StudyLinkReview.java](../../../../apps/api/src/main/java/com/aurevia/radiology/StudyLinkReview.java).
- Physical-therapy referral (PT-01): [TherapyService.java](../../../../apps/api/src/main/java/com/aurevia/therapy/TherapyService.java), [TherapyController.java](../../../../apps/api/src/main/java/com/aurevia/therapy/TherapyController.java).
- Ordered prescription drafting and transmission (RX-01, RX-02): [PrescriptionService.java](../../../../apps/api/src/main/java/com/aurevia/prescription/PrescriptionService.java), [AllergyChecker.java](../../../../apps/api/src/main/java/com/aurevia/prescription/AllergyChecker.java), [RxService.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxService.java), [RxChannel.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxChannel.java).
- Specialty authorization/attestation: [SpecialtyAttestationService.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyAttestationService.java), [SpecialtyController.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyController.java).
- Durable delivery and synthetic partner conformance: [ConformanceLedger.java](../../../../apps/api/src/main/java/com/aurevia/exchange/conformance/ConformanceLedger.java), [partner-events.json](../../../../contracts/events/fixtures/partner-events.json), [contracts/events/README.md](../../../../contracts/events/README.md). These are synthetic adapters, not partner approval.

Persistence (tenant-owned Flyway migrations): [V19__exchange_orders.sql](../../../../db/migrations/V19__exchange_orders.sql), [V20__exchange_task_states.sql](../../../../db/migrations/V20__exchange_task_states.sql), [V22__result_routing.sql](../../../../db/migrations/V22__result_routing.sql), [V23__critical_results.sql](../../../../db/migrations/V23__critical_results.sql), [V24__patient_release.sql](../../../../db/migrations/V24__patient_release.sql), [V26__laboratory_accession.sql](../../../../db/migrations/V26__laboratory_accession.sql), [V43__prescription_drafts.sql](../../../../db/migrations/V43__prescription_drafts.sql), [V44__rx02_prescription_transmission.sql](../../../../db/migrations/V44__rx02_prescription_transmission.sql), [V46__specialty_attestation.sql](../../../../db/migrations/V46__specialty_attestation.sql), [V61__flow_policies.sql](../../../../db/migrations/V61__flow_policies.sql).

Public contract entries: [contracts/openapi.yaml](../../../../contracts/openapi.yaml) exposes `/api/v1/orders` and `/api/v1/lab/accessions`; the regenerated [generated/openapi.yaml](../../../../contracts/openapi/generated/openapi.yaml) additionally covers the accept/reject, release authorize/correct/withdraw, critical acknowledge, radiology sign and therapy accept entries.

Representative tests (tool evidence from file reads; not re-executed for this note): [ExchangeStateMachineTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangetask/ExchangeStateMachineTest.java), [ResultRoutingIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangeresult/ResultRoutingIntegrationTest.java), [LabSliceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/lab/LabSliceTest.java), [ImagingServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/radiology/ImagingServiceTest.java), [TherapyEpisodeFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/therapy/TherapyEpisodeFlowTest.java), [RxTransmissionFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/prescription/RxTransmissionFlowTest.java), [SpecialtyAuthorizationFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/authz/SpecialtyAuthorizationFlowTest.java), [PartnerConformanceFixturesTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchange/PartnerConformanceFixturesTest.java). CI runs these via [delivery.yml](../../../../.github/workflows/delivery.yml).

## Gap

Missing is not the core code but the approval/manual-evidence boundary:

1. No approved lab, PACS or pharmacy interface contract: no conformance report, approved credentials, reconciliation record or outage drill for a real partner. The repository holds only synthetic fixtures and conformance tests. Supply by: Integration lead and partner owner (section 10 decision "Lab, PACS and pharmacy interface contracts"). The synthetic fixture `contract` field is `x-02-arc-05` (durable delivery), not a signed partner contract.
2. No manual-workflow documentation that identifies delivery evidence and verification evidence for any partner-specific or manual step. Supply by: site clinical/operations owner at G5.
3. No clinical sign-off for dictionaries, interpreting privileges, result escalation and coverage, and no G4/G5 acceptance evidence (wrong-patient study refusal, addendum to referrer, PT discharge/referral closure, ambiguous-transmission reconciliation, site launch sign-offs). Supply by: Clinical safety lead, site medical director and product/clinical/privacy owners.

Files that would change when the gap is closed (none changed by this note): a new decision record under `docs/decisions/` for the interface approvals; the affected `contracts/` fixtures and conformance reports; `docs/operations/` evidence artifacts for delivery/verification; and the G4/G5 acceptance matrix in tasks/ / the gate evidence docs. No code or migration change is implied by this note.

Separate tasks needed: at least three — (a) partner interface approvals and conformance evidence per interface, (b) manual-workflow delivery/verification evidence documentation, and (c) the G4/G5 clinical and site acceptance sign-offs. Each is human/partner-gated and cannot be closed by code alone.

## How to verify

Code/persistence gates a reviewer can run locally (no partner or cloud account needed):

- `python3 -I scripts/check-docs.py` — validates the links and requirement IDs in this note.
- `bash apps/api/check.sh` — module test/coverage gate for the Java flows named above.
- `bash db/check.sh` — migration, policy and integrity checks for the Flyway files named above.
- `python3 -I scripts/gen-opencode.py --check` and `bash scripts/check.sh` — policy/tooling gate (the integrator/Delivery run the full gate including JaCoCo and integration tests).
- Confirm in `contracts/openapi/generated/openapi.yaml` that the PRD-02 entries listed above are present and that the generated client is in sync.

Human evidence a site must produce before G5 (not present in this repository; a reviewer must obtain it from the named owners):

- Approved lab, PACS and pharmacy interface contracts with conformance results, approved credentials, reconciliation and outage-drill records (Integration lead and partner owner).
- Approved clinical dictionaries, interpreting privileges, escalation and coverage, plus timed workflow drills (Clinical safety lead and site medical director).
- Documented manual workflows that name the delivery evidence and the verification evidence for each manual step (site clinical/operations owner), and the signed G5 site approval of workflow, release policy, market constraints, integrations, identity procedures, staffing and downtime drills.
