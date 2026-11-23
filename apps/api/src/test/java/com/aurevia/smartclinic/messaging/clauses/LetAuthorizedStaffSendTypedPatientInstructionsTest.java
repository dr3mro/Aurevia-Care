package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.instructions.InstructionActions;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.FactType;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.Kind;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.Revision;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.State;
import com.aurevia.smartclinic.messaging.instructions.InstructionController;
import com.aurevia.smartclinic.messaging.instructions.InstructionService;
import com.aurevia.smartclinic.messaging.instructions.InstructionStore.InMemory;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-04 clause 1 proof: authorized staff send typed patient instructions that carry their
 * title, text, language, author, unit, issue time and optional due date and secure attachments.
 *
 * <p>Every case enters through the normal HTTP adapter ({@code InstructionController.send})
 * with a fixed clock and a fresh tenant-keyed in-memory store, so the published revision and
 * each refusal are observed without a database. All identifiers and content are synthetic;
 * clause 3 (replace/withdraw) and MSG-05 facts are deliberately out of scope.</p>
 */
class LetAuthorizedStaffSendTypedPatientInstructionsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "conn-lab-1";
    private static final String UNIT = "unit-lab";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final Instant DUE = Instant.parse("2026-11-20T09:00:00Z");

    private InMemory store;
    private InstructionController controller;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /**
     * Build a fresh controller with a synthetic ORG_A fixture: a clinician and a clinical
     * assistant assigned to the unit, a receptionist without a send permission, and an active
     * patient-unit connection. Seeding runs under a bound context because the store is
     * tenant-keyed. The clock is fixed on the service through Spring's test utility because
     * the clock-taking constructor is package-private in the instructions package.
     */
    private void fixture() {
        store = new InMemory();
        InstructionService service = new InstructionService(store, new InstructionActions());
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        controller = new InstructionController(service);
        bind(ORG_A, "admin-a");
        store.seedMember("admin-a", List.of("tenant_admin"));
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedMember("assistant-a", List.of("clinical_assistant"));
        store.seedMember("reception-a", List.of("reception"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedUnitStaff("assistant-a", UNIT);
        store.seedUnitStaff("reception-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
    }

    /** Bind a synthetic tenant context for one command. */
    private void bind(String organizationId, String subject) {
        TenantContext.set(new TenantContext(organizationId, subject, "MSG04", "corr-clause-1"));
    }

    /** Free-text send: no template, all optional clause-1 fields present. */
    private SendRequest freeText() {
        return new SendRequest(PATIENT, CONNECTION, UNIT, null, Kind.COLLECTION,
                "Morning collection", "Please fast before 9am, 5 mL sample.", "en",
                DUE.toString(), ContextType.ORDER, "order-1", List.of("doc-a"));
    }

    @Test
    void authorizedStaffSendsTypedInstructionWithAllClauseOneFields() {
        fixture();
        bind(ORG_A, "clinician-a");

        var response = controller.send(freeText());

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Revision sent = response.getBody();
        assertNotNull(sent);
        assertNotNull(response.getHeaders().getLocation());
        assertEquals("/api/v1/patient-instructions/" + sent.threadId(),
                response.getHeaders().getLocation().toString());
        assertEquals(1, sent.revision());
        assertEquals(State.SENT, sent.state());
        assertNull(sent.templateId());
        assertEquals(Kind.COLLECTION, sent.kind());
        assertEquals("Morning collection", sent.title());
        assertEquals("Please fast before 9am, 5 mL sample.", sent.text());
        assertEquals("en", sent.language());
        assertEquals("clinician-a", sent.authorSubject());
        assertEquals("clinician", sent.authorRole());
        assertEquals(UNIT, sent.unitId());
        assertEquals(CONNECTION, sent.connectionId());
        assertEquals(PATIENT, sent.patientId());
        assertEquals(NOW, sent.issuedAt());
        assertEquals(DUE, sent.actionDueAt());
        assertEquals(ContextType.ORDER, sent.contextType());
        assertEquals("order-1", sent.contextId());
        assertEquals(List.of("doc-a"), sent.attachmentIds());
        assertNull(sent.supersededBy());
        assertNull(sent.replacesRevision());
        assertNull(sent.withdrawalReason());
        assertTrue(store.facts(sent.threadId()).stream()
                .anyMatch(fact -> fact.type() == FactType.SENT && PATIENT.equals(fact.recipient())));
        assertTrue(store.facts(sent.threadId()).stream()
                .anyMatch(fact -> fact.type() == FactType.DISPATCHED));
    }

    @Test
    void authorizedStaffSendsFreeTextWithoutOptionalDueDateOrAttachments() {
        fixture();
        bind(ORG_A, "clinician-a");
        SendRequest minimal = new SendRequest(PATIENT, CONNECTION, UNIT, null, Kind.GENERAL,
                "Visit follow-up", "Call the unit after your visit.", "en", null, null, null, List.of());

        var response = controller.send(minimal);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Revision sent = response.getBody();
        assertNotNull(sent);
        assertEquals(State.SENT, sent.state());
        assertNull(sent.templateId());
        assertNull(sent.actionDueAt());
        assertTrue(sent.attachmentIds().isEmpty());
    }

    @Test
    void sendRefusesRoleWithoutInstructionSendPermission() {
        fixture();
        bind(ORG_A, "reception-a");

        ApiException denial = assertThrows(ApiException.class, () -> controller.send(freeText()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("missing INSTRUCTION_SEND permission", denial.getMessage());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    @Test
    void sendRefusesAnotherTenantsConnection() {
        fixture();
        // ORG_B has a clinician with the same local unit key but no connection: the
        // ORG_A connection with that local key must not be visible across tenants.
        bind(ORG_B, "clinician-b");
        store.seedMember("clinician-b", List.of("clinician"));
        store.seedUnitStaff("clinician-b", UNIT);

        ApiException denial = assertThrows(ApiException.class, () -> controller.send(freeText()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("the patient-unit connection is not active for this context", denial.getMessage());
        assertTrue(store.forPatient(PATIENT).isEmpty());
        bind(ORG_A, "clinician-a");
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    @Test
    void sendRefusesASubjectNotAssignedToTheUnit() {
        fixture();
        // A clinician with a valid connection but no unit assignment carries the right
        // role and tenant, yet still fails the unit half of the send guard.
        bind(ORG_A, "clinician-unassigned");
        store.seedMember("clinician-unassigned", List.of("clinician"));

        ApiException denial = assertThrows(ApiException.class, () -> controller.send(freeText()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("the sender is not assigned to the care unit", denial.getMessage());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    @Test
    void freeTextSendRefusesClinicalAssistantWithoutClinicalSend() {
        fixture();
        bind(ORG_A, "assistant-a");

        ApiException denial = assertThrows(ApiException.class, () -> controller.send(freeText()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("free text requires clinician authorization", denial.getMessage());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    @Test
    void sendRefusesMissingTitle() {
        fixture();
        bind(ORG_A, "clinician-a");
        SendRequest withoutTitle = new SendRequest(PATIENT, CONNECTION, UNIT, null, Kind.COLLECTION,
                null, "Please fast before 9am.", "en", null, null, null, List.of());

        ApiException denial = assertThrows(ApiException.class, () -> controller.send(withoutTitle));

        assertEquals(422, denial.status());
        assertEquals("INVALID_TITLE", denial.code());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    @Test
    void sendRefusesADueDateInThePast() {
        fixture();
        bind(ORG_A, "clinician-a");
        SendRequest staleDueDate = new SendRequest(PATIENT, CONNECTION, UNIT, null, Kind.COLLECTION,
                "Morning collection", "Please fast before 9am.", "en",
                "2026-11-18T09:00:00Z", ContextType.ORDER, "order-1", List.of("doc-a"));

        ApiException denial = assertThrows(ApiException.class, () -> controller.send(staleDueDate));

        assertEquals(422, denial.status());
        assertEquals("INVALID_ACTIONDUEAT", denial.code());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }
}
