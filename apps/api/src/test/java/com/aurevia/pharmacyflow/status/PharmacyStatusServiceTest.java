package com.aurevia.pharmacyflow.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.AcceptCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.ClarificationCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.DispenseCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.RejectCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusContract.StatusEvent;
import com.aurevia.pharmacyflow.status.PharmacyStatusContract.StatusView;
import com.aurevia.prescription.rx.RxContract.EventType;

/** RX-02 pharmacy status kernel: separate append-only facts, required reason, idempotent retry. */
class PharmacyStatusServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "pharm-tech";
    private static final String PRESCRIPTION = "rx-1";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Set<String> PHARMACY_ROLES = Set.of(SpecialtyRoles.PHARMACY_STAFF);
    private static final Set<String> OTHER_ROLES = Set.of(SpecialtyRoles.RECEPTION);

    private PharmacyStatusService service;

    @BeforeEach
    void setUp() {
        service = new PharmacyStatusService(Clock.fixed(AT, ZoneOffset.UTC));
    }

    private AcceptCommand accept(int version, String key) {
        return new AcceptCommand(ORG, SUBJECT, PHARMACY_ROLES, PRESCRIPTION, version, key);
    }

    private RejectCommand reject(int version, String key, String reason) {
        return new RejectCommand(ORG, SUBJECT, PHARMACY_ROLES, PRESCRIPTION, version, key, reason);
    }

    private ClarificationCommand clarification(int version, String key, String question) {
        return new ClarificationCommand(ORG, SUBJECT, PHARMACY_ROLES, PRESCRIPTION, version, key, question);
    }

    private DispenseCommand dispense(int version, String key) {
        return new DispenseCommand(ORG, SUBJECT, PHARMACY_ROLES, PRESCRIPTION, version, key,
                "pharmacist-ali", AT, "60 tablets of 500mg paracetamol");
    }

    @Test
    void acceptAppendsEventWithActorAndTime() {
        StatusEvent event = service.accept(accept(1, "key-1"));

        assertEquals(EventType.PHARMACY_ACCEPTED, event.type());
        assertEquals(ORG, event.organizationId());
        assertEquals(PRESCRIPTION, event.prescriptionId());
        assertEquals(SUBJECT, event.actorId());
        assertEquals(AT, event.occurredAt());
        assertEquals(1, event.version());
        assertTrue(service.view(ORG, PRESCRIPTION).accepted());
    }

    @Test
    void rejectNeedsAReason() {
        ApiException missing = assertThrows(ApiException.class, () -> service.reject(reject(1, "r-null", null)));
        assertEquals(422, missing.status());
        assertEquals("REASON_REQUIRED", missing.code());

        ApiException blank = assertThrows(ApiException.class, () -> service.reject(reject(1, "r-blank", "   ")));
        assertEquals(422, blank.status());
        assertEquals("REASON_REQUIRED", blank.code());
    }

    @Test
    void rejectWithReasonAppendsRejectionDetail() {
        StatusEvent event = service.reject(reject(1, "r-1", "out of stock at this site"));

        assertEquals(EventType.PHARMACY_REJECTED, event.type());
        assertEquals("out of stock at this site", event.detail());
        assertFalse(service.view(ORG, PRESCRIPTION).accepted());
    }

    @Test
    void clarificationNeedsAQuestion() {
        assertThrows(ApiException.class, () -> service.askClarification(clarification(1, "c-null", null)));
        assertThrows(ApiException.class, () -> service.askClarification(clarification(1, "c-blank", " ")));
    }

    @Test
    void clarificationAppendsQuestion() {
        StatusEvent event = service.askClarification(clarification(1, "c-1", "confirm the dose"));

        assertEquals(EventType.CLARIFICATION, event.type());
        assertEquals("confirm the dose", event.detail());
    }

    @Test
    void dispensingEvidenceNeedsWhoWhenAndWhat() {
        Set<String> roles = PHARMACY_ROLES;
        assertThrows(ApiException.class, () -> service.recordDispensing(new DispenseCommand(
                ORG, SUBJECT, roles, PRESCRIPTION, 1, "d-null-who", null, AT, "item")));
        assertThrows(ApiException.class, () -> service.recordDispensing(new DispenseCommand(
                ORG, SUBJECT, roles, PRESCRIPTION, 1, "d-blank-who", "  ", AT, "item")));
        assertThrows(ApiException.class, () -> service.recordDispensing(new DispenseCommand(
                ORG, SUBJECT, roles, PRESCRIPTION, 1, "d-null-when", "pharmacist", null, "item")));
        assertThrows(ApiException.class, () -> service.recordDispensing(new DispenseCommand(
                ORG, SUBJECT, roles, PRESCRIPTION, 1, "d-null-what", "pharmacist", AT, null)));
        assertThrows(ApiException.class, () -> service.recordDispensing(new DispenseCommand(
                ORG, SUBJECT, roles, PRESCRIPTION, 1, "d-blank-what", "pharmacist", AT, " ")));
    }

    @Test
    void dispensingEvidenceDoesNotMarkAccepted() {
        service.accept(accept(1, "accept-1"));
        StatusEvent dispensed = service.recordDispensing(dispense(2, "dispense-1"));

        assertEquals(EventType.DISPENSING_EVIDENCE, dispensed.type());
        assertEquals("pharmacist-ali", dispensed.dispensedBy());
        assertEquals(AT, dispensed.dispensedAt());
        assertEquals("60 tablets of 500mg paracetamol", dispensed.handedOver());

        StatusView view = service.view(ORG, PRESCRIPTION);
        assertTrue(view.accepted(), "a real acceptance fact is still present");
        assertTrue(view.dispensed());
    }

    @Test
    void dispensingEvidenceAloneNeverMarksAccepted() {
        service.recordDispensing(dispense(1, "dispense-only"));

        StatusView view = service.view(ORG, PRESCRIPTION);
        assertFalse(view.accepted());
        assertTrue(view.dispensed());
    }

    @Test
    void eventsAreAppendOnly() {
        StatusEvent accepted = service.accept(accept(1, "a-1"));
        service.reject(reject(2, "r-1", "wrong strength"));
        service.askClarification(clarification(3, "c-1", "resend strength"));

        List<StatusEvent> history = service.history(ORG, PRESCRIPTION);
        assertEquals(3, history.size());
        assertEquals(EventType.PHARMACY_ACCEPTED, history.get(0).type());
        assertEquals(EventType.PHARMACY_REJECTED, history.get(1).type());
        assertEquals(EventType.CLARIFICATION, history.get(2).type());
        assertEquals(accepted.id(), history.get(0).id());
    }

    @Test
    void retriedActionRecordsOnce() {
        StatusEvent first = service.accept(accept(1, "same-key"));
        StatusEvent retry = service.accept(accept(1, "same-key"));

        assertSame(first, retry);
        assertEquals(1, service.history(ORG, PRESCRIPTION).size());
    }

    @Test
    void differentIdempotencyKeysRecordSeparateFacts() {
        service.accept(accept(1, "key-a"));
        service.accept(accept(2, "key-b"));

        assertEquals(2, service.history(ORG, PRESCRIPTION).size());
    }

    @Test
    void sameKeyOnDifferentActionIsADifferentFact() {
        service.accept(accept(1, "shared"));
        service.reject(reject(2, "shared", "reason"));

        assertEquals(2, service.history(ORG, PRESCRIPTION).size());
    }

    @Test
    void staleVersionIsRejected() {
        service.accept(accept(1, "key-1"));
        ApiException stale = assertThrows(ApiException.class, () -> service.reject(reject(1, "key-2", "reason")));

        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());
        assertEquals(1, service.history(ORG, PRESCRIPTION).size());
    }

    @Test
    void missingPharmacyRoleIsForbidden() {
        AcceptCommand command = new AcceptCommand(ORG, SUBJECT, OTHER_ROLES, PRESCRIPTION, 1, "key-1");
        ApiException denial = assertThrows(ApiException.class, () -> service.accept(command));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(service.history(ORG, PRESCRIPTION).isEmpty());
    }

    @Test
    void commonFieldsAreValidatedBeforeAnyAppend() {
        assertThrows(ApiException.class, () -> service.accept(new AcceptCommand(
                " ", SUBJECT, PHARMACY_ROLES, PRESCRIPTION, 1, "k")));
        assertThrows(ApiException.class, () -> service.accept(new AcceptCommand(
                ORG, " ", PHARMACY_ROLES, PRESCRIPTION, 1, "k")));
        assertThrows(ApiException.class, () -> service.accept(new AcceptCommand(
                ORG, SUBJECT, PHARMACY_ROLES, " ", 1, "k")));
        assertThrows(ApiException.class, () -> service.accept(new AcceptCommand(
                ORG, SUBJECT, PHARMACY_ROLES, PRESCRIPTION, 1, " ")));
        assertThrows(ApiException.class, () -> service.accept(new AcceptCommand(
                ORG, SUBJECT, PHARMACY_ROLES, PRESCRIPTION, 0, "k")));
        assertTrue(service.history(ORG, PRESCRIPTION).isEmpty());
    }

    @Test
    void viewAndHistoryValidateTheirScope() {
        assertThrows(ApiException.class, () -> service.view(" ", PRESCRIPTION));
        assertThrows(ApiException.class, () -> service.view(ORG, " "));
        assertThrows(ApiException.class, () -> service.history(" ", PRESCRIPTION));
        assertThrows(ApiException.class, () -> service.history(ORG, " "));
    }

    @Test
    void unknownPrescriptionAndTenantHaveEmptyTrails() {
        assertEquals(List.of(), service.history(ORG, "missing-rx"));
        assertEquals(List.of(), service.history("99999999-9999-9999-9999-999999999999", PRESCRIPTION));

        StatusView view = service.view(ORG, "missing-rx");
        assertEquals(1, view.version());
        assertFalse(view.accepted());
        assertFalse(view.dispensed());
        assertEquals(List.of(), view.events());
    }

    @Test
    void roleSetsAreCopiedAtConstruction() {
        java.util.Set<String> mutable = new java.util.HashSet<>(PHARMACY_ROLES);
        AcceptCommand command = new AcceptCommand(ORG, SUBJECT, mutable, PRESCRIPTION, 1, "k");
        mutable.clear();

        assertEquals(Set.of(SpecialtyRoles.PHARMACY_STAFF), command.roles());
        StatusEvent event = service.accept(command);
        assertNotEquals("", event.id());
    }
}
