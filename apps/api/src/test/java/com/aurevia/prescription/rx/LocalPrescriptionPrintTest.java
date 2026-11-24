package com.aurevia.prescription.rx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.rx.RxContract.Event;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxContract.View;

/** BUILD-10 (RX-02): the printable local prescription and its prescriber attestation. */
class LocalPrescriptionPrintTest {

    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final Instant ATTESTED_AT = Instant.parse("2026-11-20T10:15:30Z");

    private final LocalPrescriptionPrint printer = new LocalPrescriptionPrint();

    @Test
    void producesPrintableDocumentWithAttestationAndTheRecordedChannel() {
        Prescription printed = prescription(State.PRINTED, RxChannel.PRINTABLE_LOCAL.wire());
        Event attestation = attestation("synthetic attestation", ATTESTED_AT);

        PrintablePrescription document = printer.issue(new View(printed, List.of(attestation)));

        assertEquals(ID, document.prescriptionId());
        assertEquals(PATIENT, document.patientId());
        assertEquals("amoxicillin", document.medication());
        assertEquals("500 mg", document.strength());
        assertEquals("1 capsule", document.dose());
        assertEquals("oral", document.route());
        assertEquals("twice daily", document.frequency());
        assertEquals(30, document.quantity());
        assertEquals("clinician-1", document.prescriberSubject());
        assertEquals("pharmacy-x", document.destinationPharmacy());
        assertEquals("synthetic attestation", document.attestationNote());
        assertEquals(ATTESTED_AT, document.attestedAt());
        assertEquals("PRINTABLE_LOCAL", document.channel(), "the channel is recorded exactly as used");
        assertEquals(document, printer.issue(new View(printed, List.of(attestation))));
        assertEquals(document.hashCode(), printer.issue(new View(printed, List.of(attestation))).hashCode());
        assertEquals(true, document.toString().contains("PRINTABLE_LOCAL"));
    }

    @Test
    void refusesADocumentWhenThePrescriptionWasNotPrinted() {
        Prescription attested = prescription(State.ATTESTED, RxChannel.PRINTABLE_LOCAL.wire());

        ApiException failure = assertThrows(ApiException.class,
                () -> printer.issue(new View(attested, List.of(attestation("synthetic", ATTESTED_AT)))));

        assertEquals(409, failure.status());
        assertEquals("PRINT_NOT_AVAILABLE", failure.code());
    }

    @Test
    void refusesADocumentWhenTheRecordedChannelWasElectronic() {
        Prescription electronic = prescription(State.PRINTED, RxChannel.E_PRESCRIBE.wire());

        ApiException failure = assertThrows(ApiException.class,
                () -> printer.issue(new View(electronic, List.of(attestation("synthetic", ATTESTED_AT)))));

        assertEquals(422, failure.status());
        assertEquals("CHANNEL_MISMATCH", failure.code());
    }

    @Test
    void refusesADocumentWhenNoChannelWasRecorded() {
        Prescription unrouted = prescription(State.PRINTED, null);

        ApiException failure = assertThrows(ApiException.class,
                () -> printer.issue(new View(unrouted, List.of(attestation("synthetic", ATTESTED_AT)))));

        assertEquals(422, failure.status());
        assertEquals("CHANNEL_MISMATCH", failure.code());
    }

    @Test
    void selectsTheAttestationFactAndIgnoresOtherTrailEvents() {
        Prescription printed = prescription(State.PRINTED, RxChannel.PRINTABLE_LOCAL.wire());
        Event submission = new Event("dddddddd-0000-0000-0000-000000000001", ID, 3,
                EventType.SUBMITTED, "clinician-1", RxChannel.PRINTABLE_LOCAL.wire(),
                "submission-1", "printed", null, Instant.parse("2026-11-20T10:10:00Z"));

        PrintablePrescription document = printer.issue(new View(printed,
                List.of(submission, attestation("synthetic attestation", ATTESTED_AT))));

        assertEquals("synthetic attestation", document.attestationNote());
        assertEquals(ATTESTED_AT, document.attestedAt(), "non-attestation facts are not the attestation");
    }

    @Test
    void refusesADocumentWithoutThePrescriberAttestation() {
        Prescription printed = prescription(State.PRINTED, RxChannel.PRINTABLE_LOCAL.wire());

        ApiException failure = assertThrows(ApiException.class,
                () -> printer.issue(new View(printed, List.of())));

        assertEquals(409, failure.status());
        assertEquals("ATTESTATION_REQUIRED", failure.code());
    }

    @Test
    void carriesNoAttestationNoteWhenThePrescriberOnlyRecordedTheFact() {
        Prescription printed = prescription(State.PRINTED, RxChannel.PRINTABLE_LOCAL.wire());

        PrintablePrescription document = printer.issue(
                new View(printed, List.of(attestation(null, ATTESTED_AT))));

        assertNull(document.attestationNote());
        assertEquals(ATTESTED_AT, document.attestedAt(), "the attestation fact still carries its time");
    }

    private static Prescription prescription(State state, String channel) {
        return new Prescription(ID, 3, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, "clinician-1", "pharmacy-x", state, channel, null,
                false, Instant.parse("2026-11-20T10:00:00Z"));
    }

    private static Event attestation(String note, Instant at) {
        return new Event("cccccccc-0000-0000-0000-000000000001", ID, 2, EventType.ATTESTED,
                "clinician-1", null, null, "market=US", note, at);
    }
}
