package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.instructions.InstructionActions;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.DefineTemplateRequest;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.Kind;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.State;
import com.aurevia.smartclinic.messaging.instructions.InstructionController;
import com.aurevia.smartclinic.messaging.instructions.InstructionService;
import com.aurevia.smartclinic.messaging.instructions.InstructionStore.InMemory;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-04 clause 4 (API evidence): a sender may use only a template approved for
 * the sending unit or clinician-scoped free text.
 *
 * <p>The approved-template path copies the approved content rather than trusting
 * caller text, and the free-text path requires clinician authorization and a
 * bounded kind. Templates must belong to the sender's unit and tenant, a
 * clinical template needs clinician scope, and a sender must be assigned to the
 * unit over an active patient connection. Every request enters through the
 * {@link com.aurevia.smartclinic.messaging.instructions.InstructionController} HTTP adapter methods with an injected service and
 * a synthetic tenant-keyed store and synthetic members. No production code,
 * contract or migration is changed.</p>
 */
class UseUnitApprovedTemplatesApprovedFreeTextTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "conn-lab-1";
    private static final String OTHER_CONNECTION = "conn-radiology-1";
    private static final String UNIT = "unit-lab";
    private static final String OTHER_UNIT = "unit-radiology";

    private InMemory store;
    private InstructionController controller;

    /**
     * Clear the thread-bound tenant context so no test can leak identity into
     * another test in the same worker thread.
     */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /**
     * Build a fresh controller, a fixed clock and synthetic staff for one tenant.
     */
    private void fixture() {
        store = new InMemory();
        // The fixed-clock constructor is package-private to instructions; this
        // clause test only needs the public adapter and a synthetic identity.
        controller = new InstructionController(new InstructionService(store, new InstructionActions()));
        // Seeding is tenant-scoped, so the context must be bound before fixtures.
        bind(ORG_A, "admin-a");
        store.seedMember("admin-a", List.of("tenant_admin"));
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedMember("assistant-a", List.of("clinical_assistant"));
        store.seedMember("reception-a", List.of("reception"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedUnitStaff("assistant-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
    }

    /**
     * Bind a synthetic request identity to an organization.
     *
     * @param org     active organization identifier
     * @param subject authenticated synthetic subject
     */
    private void bind(String org, String subject) {
        TenantContext.set(new TenantContext(org, subject, "MSG04", "corr-1"));
    }

    /**
     * Build an approval command for a unit-owned template.
     *
     * @param templateId  tenant-local template key
     * @param unitId      owning care unit
     * @param kind        instruction family
     * @param clinical    whether the content needs clinician scope to send
     * @param contextType required care relationship, or null when unrestricted
     * @return the synthetic approval command
     */
    private DefineTemplateRequest template(String templateId, String unitId, Kind kind, boolean clinical,
                                           ContextType contextType) {
        return new DefineTemplateRequest(unitId, templateId, kind, "Approved instruction",
                "Approved instruction body.", "en", clinical, contextType);
    }

    /**
     * Build a send command that names an approved template.
     *
     * @param templateId  tenant-local template key
     * @param connectionId synthetic patient-unit connection
     * @param unitId      sending care unit
     * @param contextType care relationship bound to the send
     * @return the synthetic send command with no caller-supplied content
     */
    private SendRequest fromTemplate(String templateId, String connectionId, String unitId,
                                     ContextType contextType) {
        return new SendRequest(PATIENT, connectionId, unitId, templateId, null, null, null, null,
                null, contextType, contextType == null ? null : "order-1", List.of());
    }

    /**
     * Build a clinician free-text send command with valid bounded content.
     *
     * @param kind  instruction family
     * @param title short heading
     * @param text  instruction body
     * @return the synthetic free-text command
     */
    private SendRequest freeText(Kind kind, String title, String text) {
        return new SendRequest(PATIENT, CONNECTION, UNIT, null, kind, title, text, "en", null,
                ContextType.ORDER, "order-1", List.of());
    }

    /**
     * An administrator approves a unit template and an in-scope clinician sends
     * it: the published revision carries the approved content and its author.
     */
    @Test
    void adminApprovedUnitTemplateIsSentByAClinicianWithItsApprovedContent() {
        fixture();
        var approval = controller.defineTemplate(template("collection-v1", UNIT, Kind.COLLECTION,
                false, ContextType.ORDER));
        assertEquals(HttpStatus.CREATED, approval.getStatusCode());
        assertEquals("admin-a", approval.getBody().approvedBy());
        assertNotNull(approval.getBody().approvedAt());

        bind(ORG_A, "clinician-a");
        var response = controller.send(fromTemplate("collection-v1", CONNECTION, UNIT, ContextType.ORDER));
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        var sent = response.getBody();
        assertEquals(State.SENT, sent.state());
        assertEquals("collection-v1", sent.templateId());
        assertEquals(Kind.COLLECTION, sent.kind());
        assertEquals("Approved instruction", sent.title());
        assertEquals("Approved instruction body.", sent.text());
        assertEquals("en", sent.language());
        assertEquals("clinician-a", sent.authorSubject());
        assertEquals("clinician", sent.authorRole());
    }

    /**
     * A non-clinical staff member may send an approved non-clinical template
     * because the approved content, not the sender, sets its scope.
     */
    @Test
    void approvedNonClinicalTemplateIsSendableByAnInScopeAssistant() {
        fixture();
        controller.defineTemplate(template("collection-v1", UNIT, Kind.COLLECTION, false,
                ContextType.ORDER));

        bind(ORG_A, "assistant-a");
        var sent = controller.send(fromTemplate("collection-v1", CONNECTION, UNIT, ContextType.ORDER))
                .getBody();
        assertEquals(State.SENT, sent.state());
        assertEquals("clinical_assistant", sent.authorRole());
        assertEquals("Approved instruction", sent.title());
    }

    /**
     * A clinician may send an approved clinical template: the clinical content
     * is inside that sender's professional scope.
     */
    @Test
    void clinicianMaySendAnApprovedClinicalTemplate() {
        fixture();
        controller.defineTemplate(template("clinical-v1", UNIT, Kind.THERAPY_HOME, true, null));

        bind(ORG_A, "clinician-a");
        var response = controller.send(fromTemplate("clinical-v1", CONNECTION, UNIT, null));
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        var sent = response.getBody();
        assertEquals(State.SENT, sent.state());
        assertEquals(Kind.THERAPY_HOME, sent.kind());
        assertEquals("Approved instruction", sent.title());
        assertEquals("clinician-a", sent.authorSubject());
        assertEquals("clinician", sent.authorRole());
    }

    /**
     * A clinician sends bounded free text: the typed content, not a template,
     * is bound into a sent revision.
     */
    @Test
    void clinicianSendsBoundedFreeTextWithinProfessionalScope() {
        fixture();
        bind(ORG_A, "clinician-a");
        var response = controller.send(freeText(Kind.GENERAL, "Visit follow-up",
                "Call the clinic after 2pm."));
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        var sent = response.getBody();
        assertEquals(State.SENT, sent.state());
        assertNull(sent.templateId());
        assertEquals(Kind.GENERAL, sent.kind());
        assertEquals("Visit follow-up", sent.title());
        assertEquals("Call the clinic after 2pm.", sent.text());
        assertEquals("clinician-a", sent.authorSubject());
        assertEquals("clinician", sent.authorRole());
    }

    /**
     * Free text is outside a clinical assistant's professional scope and is
     * refused before any revision is stored.
     */
    @Test
    void clinicalAssistantCannotSendFreeTextOutsideProfessionalScope() {
        fixture();
        bind(ORG_A, "assistant-a");
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.send(freeText(Kind.GENERAL, "Unapproved", "Free text body")));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("free text requires clinician authorization"));
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    /**
     * A clinical template is outside a clinical assistant's professional scope
     * even when the administrator approved it for the unit.
     */
    @Test
    void clinicalAssistantCannotSendAClinicalTemplate() {
        fixture();
        controller.defineTemplate(template("clinical-v1", UNIT, Kind.THERAPY_HOME, true, null));

        bind(ORG_A, "assistant-a");
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.send(fromTemplate("clinical-v1", CONNECTION, UNIT, null)));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("clinical template requires clinician authorization"));
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    /**
     * An unknown template and a template approved for a different unit are
     * both refused: approval is scoped to the sending unit.
     */
    @Test
    void unknownAndOtherUnitTemplatesAreRefused() {
        fixture();
        controller.defineTemplate(template("collection-v1", UNIT, Kind.COLLECTION, false,
                ContextType.ORDER));

        bind(ORG_A, "clinician-a");
        ApiException unknown = assertThrows(ApiException.class,
                () -> controller.send(fromTemplate("missing-v1", CONNECTION, UNIT, ContextType.ORDER)));
        assertEquals(404, unknown.status());
        assertEquals("NOT_FOUND", unknown.code());

        store.seedConnection(OTHER_CONNECTION, PATIENT, OTHER_UNIT);
        store.seedUnitStaff("clinician-a", OTHER_UNIT);
        ApiException otherUnit = assertThrows(ApiException.class, () -> controller.send(
                fromTemplate("collection-v1", OTHER_CONNECTION, OTHER_UNIT, ContextType.ORDER)));
        assertEquals(404, otherUnit.status());
        assertEquals("NOT_FOUND", otherUnit.code());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    /**
     * A template bound to one care relationship cannot be sent under another
     * relationship, including when the caller omits the relationship.
     */
    @Test
    void templateContextMismatchIsRefused() {
        fixture();
        controller.defineTemplate(template("order-v1", UNIT, Kind.COLLECTION, false,
                ContextType.ORDER));

        bind(ORG_A, "clinician-a");
        ApiException wrongContext = assertThrows(ApiException.class,
                () -> controller.send(fromTemplate("order-v1", CONNECTION, UNIT, ContextType.APPOINTMENT)));
        assertEquals(422, wrongContext.status());
        assertEquals("CONTEXT_REQUIRED", wrongContext.code());

        ApiException missingContext = assertThrows(ApiException.class,
                () -> controller.send(fromTemplate("order-v1", CONNECTION, UNIT, null)));
        assertEquals(422, missingContext.status());
        assertEquals("CONTEXT_REQUIRED", missingContext.code());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    /**
     * Clinician-scoped free text still needs a typed instruction family.
     */
    @Test
    void freeTextWithoutAKindIsRefused() {
        fixture();
        bind(ORG_A, "clinician-a");
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.send(new SendRequest(PATIENT, CONNECTION, UNIT, null, null,
                        "Untyped", "Body", "en", null, null, null, List.of())));
        assertEquals(422, denial.status());
        assertEquals("INVALID_KIND", denial.code());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    /**
     * Approving a template is an administrative act, not a reception duty.
     */
    @Test
    void receptionCannotApproveATemplate() {
        fixture();
        bind(ORG_A, "reception-a");
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.defineTemplate(template("collection-v1", UNIT, Kind.COLLECTION,
                        false, ContextType.ORDER)));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(store.findTemplateOrg(ORG_A, "collection-v1").isEmpty());
    }

    /**
     * Clinical authorization alone is not a care relationship: a clinician who
     * is not assigned to the unit cannot send there.
     */
    @Test
    void senderNotAssignedToTheUnitCannotSend() {
        fixture();
        store.seedMember("visitor-a", List.of("clinician"));
        bind(ORG_A, "visitor-a");
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.send(freeText(Kind.GENERAL, "Out of unit", "Body")));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("not assigned to the care unit"));
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    /**
     * A template approved in another tenant is not an approved template for
     * this sender, even with identical local unit and connection identifiers.
     */
    @Test
    void templateApprovedInAnotherTenantIsRefused() {
        fixture();
        controller.defineTemplate(template("shared-v1", UNIT, Kind.COLLECTION, false,
                ContextType.ORDER));

        bind(ORG_B, "clinician-b");
        store.seedMember("clinician-b", List.of("clinician"));
        store.seedUnitStaff("clinician-b", UNIT);
        store.seedConnectionOrg(ORG_B, CONNECTION, PATIENT, UNIT);
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.send(fromTemplate("shared-v1", CONNECTION, UNIT, ContextType.ORDER)));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertTrue(store.findTemplateOrg(ORG_A, "shared-v1").isPresent());
        assertTrue(store.findTemplateOrg(ORG_B, "shared-v1").isEmpty());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }
}
