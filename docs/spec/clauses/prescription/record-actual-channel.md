# RX-02 clause 3 — record the actual channel

## Clause

Record the actual channel.

## Status

Proven by tests

## Evidence

The clause is requirement `RX-02` in [SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md). It is implemented today and proven at two levels: the new service-boundary test below and the existing real-PostgreSQL HTTP flow test.

Production code that records the channel:

- [RxService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxService.java): `transmit` parses the requested channel, re-derives authorization, ownership, version and state, re-checks entitlement and channel approval, then stamps the channel through `repository.insertSubmission` + `repository.transmit` (electronic) or `repository.transmitPrinted` (local print) and appends a channel-bearing `SUBMITTED` or `PRINTED` event. `printable` re-checks entitlement and local-channel approval before copying the recorded channel onto the document. `requireApprovedChannel` and `parseChannel` refuse a channel that is not approved or not known.
- [RxRepository.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxRepository.java): `insertSubmission`, `transmit`, `transmitPrinted` and `appendEvent` are the single SQL boundary that writes the channel and the stable submission id; `events` reads the immutable trail.
- [RxContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxContract.java): `Prescription.channel`, `Event.channel` and `TransmitRequest` are the value types; [RxChannel.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxChannel.java) defines the two approved wire keys `E_PRESCRIBE` and `PRINTABLE_LOCAL`; [RxInputs.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxInputs.java) bounds channel and outcome values; [RxActions.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxActions.java) limits routing to the clinician role.
- [LocalPrescriptionPrint.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/LocalPrescriptionPrint.java) and [PrintablePrescription.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/PrintablePrescription.java): the printable document copies the stored channel verbatim and never invents an electronic delivery.
- [RxController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxController.java): HTTP entries `POST /api/v1/prescriptions/{id}/transmit` and `GET /api/v1/prescriptions/{id}/printable`.
- [V44__rx02_prescription_transmission.sql](../../../../db/migrations/V44__rx02_prescription_transmission.sql) creates the `channel` columns on the prescription, submission and event tables with forced row level security, and the update guard that makes a stamped channel immutable. [V59__rx02_ambiguous_transition.sql](../../../../db/migrations/V59__rx02_ambiguous_transition.sql) re-states that immutable-channel guard while widening one legal transition.

Tests that prove the clause:

- [RecordActualChannelTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/prescription/clauses/RecordActualChannelTest.java) (new, nine tests). Positives: `recordsTheApprovedChannelOnElectronicTransmit` captures the submission id actually written and asserts that the prescription row, the submission ledger and the `SUBMITTED` event all carry `E_PRESCRIBE` and one stable submission id; `recordsTheApprovedPrintableChannelOnPrintTransmit` asserts `transmitPrinted` and the `PRINTED` event carry `PRINTABLE_LOCAL` and create no submission; `copiesTheRecordedChannelOntoThePrintableDocument` asserts the document copies the channel actually stamped by the print write. Negatives assert status and code and no write: `refusesAnUnapprovedChannel` (422 `UNAPPROVED_CHANNEL`), `refusesAnUnknownChannelBeforeAnyTransaction` (422 `INVALID_CHANNEL`), `refusesAPharmacyRoleRouting` (403 `FORBIDDEN`), `refusesTheWrongPrescriber` (403 `FORBIDDEN`, ownership refusal distinct from authorization), `refusesAStaleVersion` (412 `STALE_VERSION`) and `refusesAPrescriptionThatIsNotAttested` (409 `INVALID_TRANSITION`).
- End-to-end corroboration on real PostgreSQL: [RxTransmissionFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/it/prescription/RxTransmissionFlowTest.java) `electronicWorkflowTracksEveryFactSeparately` asserts the HTTP response channel is `E_PRESCRIBE`, `printablePrescriptionNeverBecomesElectronicallyDelivered` transmits `PRINTABLE_LOCAL`, and `authorizationEntitlementAndChannelPoliciesFailClosed` asserts `UNAPPROVED_CHANNEL` and `INVALID_CHANNEL`. [RxPrintableHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/prescription/rx/RxPrintableHttpTest.java) asserts `$.channel == PRINTABLE_LOCAL` through the controller.

Entry-point-to-test trace: `RxController.transmit` → `RxService.transmit` → `RxRepository.transmit` / `insertSubmission` / `appendEvent` → the V44 prescription, submission and event tables; `RecordActualChannelTest.recordsTheApprovedChannelOnElectronicTransmit` asserts the channel and stable submission id at the service boundary, and `RxTransmissionFlowTest.electronicWorkflowTracksEveryFactSeparately` reaches the same behaviour through HTTP against the migrated database.

The source `contracts/openapi.yaml` does not publish the RX-02 prescription routes, and its `printChannel` fields belong to the patient-handout schema rather than this clause. Contract publication is a separate cross-boundary concern and is not changed by this test-only story.

## Gap

None
