package com.aurevia.it.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.rx.RxActions;
import com.aurevia.prescription.rx.RxChannel;
import com.aurevia.prescription.rx.RxContract;
import com.aurevia.prescription.rx.RxInputs;

/**
 * Fast, database-free acceptance for the pure RX-02 contract: transmission
 * state machine, event vocabulary, channel types, bounded inputs and the
 * action/role matrix. Intentionally not tagged {@code integration} so it runs
 * in the DB-less swarm fast gate; HTTP and PostgreSQL behaviour is covered by
 * {@link RxTransmissionFlowTest}.
 */
class RxContractTest {

    @Test
    void stateWireAndParseRoundTrip() {
        for (RxContract.State state : RxContract.State.values()) {
            assertEquals(state, RxContract.State.from(state.wire()));
        }
        assertEquals("draft", RxContract.State.DRAFT.wire());
        assertEquals("ambiguous", RxContract.State.AMBIGUOUS.wire());
        assertEquals("delivery_confirmed", RxContract.State.DELIVERY_CONFIRMED.wire());
        assertThrows(IllegalArgumentException.class, () -> RxContract.State.from("bogus"));
    }

    @Test
    void stateAllowsOnlySafeTransitionsAndNoOp() {
        Map<RxContract.State, Set<RxContract.State>> expected = Map.ofEntries(
                Map.entry(RxContract.State.DRAFT, set(RxContract.State.DRAFT, RxContract.State.ATTESTED,
                        RxContract.State.CANCELLED)),
                Map.entry(RxContract.State.ATTESTED, set(RxContract.State.ATTESTED, RxContract.State.SUBMITTED,
                        RxContract.State.AMBIGUOUS, RxContract.State.PRINTED, RxContract.State.CANCELLED)),
                Map.entry(RxContract.State.SUBMITTED, set(RxContract.State.SUBMITTED, RxContract.State.AMBIGUOUS,
                        RxContract.State.DELIVERY_CONFIRMED, RxContract.State.CANCELLED)),
                Map.entry(RxContract.State.AMBIGUOUS, set(RxContract.State.AMBIGUOUS, RxContract.State.SUBMITTED,
                        RxContract.State.DELIVERY_CONFIRMED, RxContract.State.CANCELLED)),
                Map.entry(RxContract.State.DELIVERY_CONFIRMED, set(RxContract.State.DELIVERY_CONFIRMED,
                        RxContract.State.ACCEPTED, RxContract.State.REJECTED,
                        RxContract.State.CLARIFICATION, RxContract.State.CANCELLED)),
                Map.entry(RxContract.State.CLARIFICATION, set(RxContract.State.CLARIFICATION,
                        RxContract.State.ACCEPTED, RxContract.State.REJECTED, RxContract.State.CANCELLED)),
                Map.entry(RxContract.State.ACCEPTED, set(RxContract.State.ACCEPTED, RxContract.State.DISPENSED,
                        RxContract.State.CANCELLED)),
                Map.entry(RxContract.State.REJECTED, set(RxContract.State.REJECTED)),
                Map.entry(RxContract.State.DISPENSED, set(RxContract.State.DISPENSED)),
                Map.entry(RxContract.State.PRINTED, set(RxContract.State.PRINTED)),
                Map.entry(RxContract.State.CANCELLED, set(RxContract.State.CANCELLED)));
        for (RxContract.State from : RxContract.State.values()) {
            for (RxContract.State to : RxContract.State.values()) {
                assertEquals(expected.get(from).contains(to), from.canTransitionTo(to), from + " -> " + to);
            }
        }
    }

    @Test
    void eventTypeWireAndParseRoundTrip() {
        for (RxContract.EventType type : RxContract.EventType.values()) {
            assertEquals(type, RxContract.EventType.from(type.wire()));
        }
        assertEquals("dispensing_evidence", RxContract.EventType.DISPENSING_EVIDENCE.wire());
        assertEquals("partner_status_verified", RxContract.EventType.PARTNER_STATUS_VERIFIED.wire());
        assertThrows(IllegalArgumentException.class, () -> RxContract.EventType.from("bogus"));
    }

    @Test
    void channelWireParseAndElectronicFlag() {
        for (RxChannel channel : RxChannel.values()) {
            assertEquals(channel, RxChannel.from(channel.wire()));
        }
        assertEquals("E_PRESCRIBE", RxChannel.E_PRESCRIBE.wire());
        assertTrue(RxChannel.E_PRESCRIBE.isElectronic());
        assertFalse(RxChannel.PRINTABLE_LOCAL.isElectronic());
        assertThrows(IllegalArgumentException.class, () -> RxChannel.from("SNEAKER_NET"));
    }

    @Test
    void uuidValidationIsBounded() {
        String valid = "11111111-1111-1111-1111-111111111111";
        assertEquals(valid, RxInputs.uuid(valid, "patientId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.uuid(null, "patientId")).status());
        assertEquals("INVALID_PATIENTID", assertThrows(ApiException.class,
                () -> RxInputs.uuid("nope", "patientId")).code());
    }

    @Test
    void textAndDetailValidationTrimAndBoundLength() {
        assertEquals("amoxicillin", RxInputs.text("  amoxicillin  ", "medication", 64));
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.text(null, "medication", 64)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.text("   ", "medication", 64)).status());
        assertEquals("INVALID_MEDICATION", assertThrows(ApiException.class,
                () -> RxInputs.text("x".repeat(65), "medication", 64)).code());
        assertEquals(null, RxInputs.detail(null, 500));
        assertEquals(null, RxInputs.detail("  ", 500));
        assertEquals("verified", RxInputs.detail(" verified ", 500));
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.detail("x".repeat(501), 500)).status());
    }

    @Test
    void subjectAndChannelKeysAreBounded() {
        assertEquals("clinician-a", RxInputs.subject("clinician-a", "prescriberSubject"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.subject(null, "prescriberSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.subject("A", "prescriberSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.subject("Bad_Case", "prescriberSubject")).status());
        assertEquals("E_PRESCRIBE", RxInputs.channel("E_PRESCRIBE", "channel"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.channel(null, "channel")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.channel("eprescribe", "channel")).status());
    }

    @Test
    void transmissionAndPharmacyOutcomesAreBounded() {
        assertEquals("submitted", RxInputs.transmissionOutcome("submitted"));
        assertEquals("ambiguous", RxInputs.transmissionOutcome("ambiguous"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.transmissionOutcome("delivered")).status());
        assertEquals("accepted", RxInputs.pharmacyOutcome("accepted"));
        assertEquals("rejected", RxInputs.pharmacyOutcome("rejected"));
        assertEquals("clarification", RxInputs.pharmacyOutcome("clarification"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.pharmacyOutcome("dispensed")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.printOutcome("submitted")).status());
        assertEquals("printed", RxInputs.printOutcome("printed"));
        assertEquals("present", RxInputs.partnerStatus("present"));
        assertEquals("absent", RxInputs.partnerStatus("absent"));
        assertEquals("unknown", RxInputs.partnerStatus("unknown"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> RxInputs.partnerStatus("maybe")).status());
    }

    @Test
    void quantityIsPositiveAndBounded() {
        assertEquals(30, RxInputs.quantity(30));
        assertEquals(422, assertThrows(ApiException.class, () -> RxInputs.quantity(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> RxInputs.quantity(0)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> RxInputs.quantity(1000)).status());
    }

    @Test
    void flagAndIdsAreWellFormed() {
        assertFalse(RxInputs.flag(null));
        assertTrue(RxInputs.flag(true));
        assertFalse(RxInputs.flag(false));
        assertNotNull(java.util.UUID.fromString(RxInputs.newId()));
        assertNotNull(java.util.UUID.fromString(RxInputs.newSubmissionId()));
    }

    @Test
    void actionMatrixSeparatesPrescriberAndPharmacyDuties() {
        RxActions actions = new RxActions();
        Map<String, List<String>> allowed = Map.ofEntries(
                Map.entry(RxActions.CREATE, List.of("clinician")),
                Map.entry(RxActions.ATTEST, List.of("clinician")),
                Map.entry(RxActions.TRANSMIT, List.of("clinician")),
                Map.entry(RxActions.CONFIRM, List.of("clinician", "pharmacy_staff")),
                Map.entry(RxActions.VERIFY, List.of("clinician")),
                Map.entry(RxActions.RETRY, List.of("clinician")),
                Map.entry(RxActions.PHARMACY_RESPONSE, List.of("pharmacy_staff")),
                Map.entry(RxActions.DISPENSE, List.of("pharmacy_staff")),
                Map.entry(RxActions.RENEW, List.of("clinician")),
                Map.entry(RxActions.CANCEL, List.of("clinician")),
                Map.entry(RxActions.READ, List.of("clinician", "pharmacy_staff", "tenant_admin", "privacy_auditor")));
        List<String> roles = List.of("clinician", "pharmacy_staff", "tenant_admin",
                "privacy_auditor", "reception");
        for (Map.Entry<String, List<String>> entry : allowed.entrySet()) {
            for (String role : roles) {
                assertEquals(entry.getValue().contains(role), actions.allows(entry.getKey(), role),
                        entry.getKey() + " / " + role);
            }
        }
        assertFalse(actions.allows("UNKNOWN_ACTION", "clinician"));
        assertFalse(actions.allows(RxActions.ATTEST, "reception"));
    }

    @Test
    void recordValueSemanticsAndDefensiveCopiesAreStable() {
        RxContract.Prescription prescription = prescription();
        RxContract.Event event = event();
        assertEquals(prescription, prescription());
        assertEquals(event, event());
        assertFalse(prescription.equals(event));
        assertEquals(prescription.hashCode(), prescription().hashCode());
        assertEquals(event.hashCode(), event().hashCode());
        assertNotNull(prescription.toString());
        assertNotNull(event.toString());

        List<RxContract.Event> events = new ArrayList<>();
        events.add(event);
        RxContract.View view = new RxContract.View(prescription, events);
        events.clear();
        assertEquals(1, view.events().size());
        assertEquals(view, new RxContract.View(prescription, List.of(event)));
        assertNotNull(view.toString());
    }

    private static Set<RxContract.State> set(RxContract.State... states) {
        return Set.of(states);
    }

    private static RxContract.Prescription prescription() {
        return new RxContract.Prescription("id", 1, "patient", "amoxicillin", "500 mg", "1 capsule",
                "oral", "twice daily", 30, "clinician-a", "pharmacy-x", RxContract.State.DRAFT,
                null, null, false, null);
    }

    private static RxContract.Event event() {
        return new RxContract.Event("event-id", "id", 1, RxContract.EventType.SUBMITTED,
                "actor", "E_PRESCRIBE", "submission-1", "submitted", null, null);
    }
}
