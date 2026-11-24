package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.instructions.InstructionActions;
import com.aurevia.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.messaging.instructions.InstructionContract.DefineTemplateRequest;
import com.aurevia.messaging.instructions.InstructionContract.Fact;
import com.aurevia.messaging.instructions.InstructionContract.FactType;
import com.aurevia.messaging.instructions.InstructionContract.Kind;
import com.aurevia.messaging.instructions.InstructionContract.ReplaceRequest;
import com.aurevia.messaging.instructions.InstructionContract.Revision;
import com.aurevia.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.messaging.instructions.InstructionContract.State;
import com.aurevia.messaging.instructions.InstructionContract.Template;
import com.aurevia.messaging.instructions.InstructionContract.WithdrawRequest;
import com.aurevia.messaging.instructions.InstructionController;
import com.aurevia.messaging.instructions.InstructionService;
import com.aurevia.messaging.instructions.InstructionStore.InMemory;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-04 clause 5 proof: clinical instruction content can only be issued by a
 * subject whose current tenant membership carries the configured clinician
 * role. Every command enters through the thin HTTP adapter with an injected
 * service, a tenant-keyed synthetic store and a bound tenant context. Authority
 * is never taken from the request body; it is re-derived from live membership on
 * each call. Only synthetic fixtures are used.
 */
class ClinicalInstructionsRequireConfiguredClinicianAuthorizationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "conn-lab-1";
    private static final String UNIT = "unit-lab";

    private InMemory store;
    private InstructionController controller;

    /** Build a fresh tenant-scoped store and the normal controller entry point. */
    @BeforeEach
    void setUp() {
        store = new InMemory();
        controller = controllerWith(new InstructionActions());
        // Seeding is itself tenant-scoped: bind a context before storing fixtures.
        bind("admin-a");
        store.seedMember("admin-a", List.of("tenant_admin"));
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedMember("assistant-a", List.of("clinical_assistant"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedUnitStaff("assistant-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
    }

    /**
     * Build the normal HTTP adapter over an injected action matrix. The
     * fixed-clock constructor is package-private in the owning package; these
     * assertions depend on role, state and message, never on a timestamp.
     */
    private InstructionController controllerWith(InstructionActions actions) {
        return new InstructionController(new InstructionService(store, actions));
    }

    /** A test-only matrix that configures a second role for clinical sending. */
    private static final class ConfiguredClinicalActions extends InstructionActions {
        @Override
        public boolean allows(String action, String role) {
            return (InstructionActions.CLINICAL_SEND.equals(action) && "clinical_assistant".equals(role))
                    || super.allows(action, role);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Bind the synthetic tenant identity used by every command. */
    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "MSG04", "corr-1"));
    }

    /** Bounded free-text send request with a clinical context, never real content. */
    private SendRequest freeText() {
        return new SendRequest(PATIENT, CONNECTION, UNIT, null, Kind.COLLECTION,
                "Morning collection", "Please fast before 9am.", "en",
                null, ContextType.ORDER, "order-1", List.of());
    }

    @Test
    void clinicianMaySendFreeTextAndRecordsClinicianAuthorRole() {
        bind("clinician-a");
        ResponseEntity<Revision> response = controller.send(freeText());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Revision sent = response.getBody();
        assertEquals(State.SENT, sent.state());
        assertEquals("clinician", sent.authorRole());
        assertEquals("clinician-a", sent.authorSubject());

        List<Fact> facts = store.facts(sent.threadId());
        assertTrue(facts.stream().anyMatch(fact -> fact.type() == FactType.SENT
                && PATIENT.equals(fact.recipient())));
        assertTrue(facts.stream().anyMatch(fact -> fact.type() == FactType.DISPATCHED));
    }

    @Test
    void clinicalAssistantIsRefusedFreeTextWithoutClinicianAuthorization() {
        bind("assistant-a");
        ApiException denial = assertThrows(ApiException.class, () -> controller.send(freeText()));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("free text requires clinician authorization", denial.getMessage());

        // The clinical guard fires before save/append, so no revision is indexed
        // for the patient; a fact can only exist after a revision was stored.
        // The store check is auth-independent; the controller check is the
        // corroborating entry-point view for the still-authorized reader.
        assertTrue(store.forPatient(PATIENT).isEmpty());
        assertTrue(controller.listForPatient(PATIENT).isEmpty());
    }

    @Test
    void clinicalTemplateRequiresClinicianWhileAdminApprovesIt() {
        // Approving a clinical template is an administrative act, separate from sending it.
        bind("admin-a");
        ResponseEntity<Template> approved = controller.defineTemplate(new DefineTemplateRequest(
                UNIT, "clinical-v1", Kind.THERAPY_HOME, "Home exercises",
                "Clinical instruction body.", "en", true, null));
        assertEquals(HttpStatus.CREATED, approved.getStatusCode());
        assertTrue(approved.getBody().clinical());
        assertEquals("admin-a", approved.getBody().approvedBy());

        bind("clinician-a");
        ResponseEntity<Revision> sent = controller.send(new SendRequest(PATIENT, CONNECTION, UNIT,
                "clinical-v1", null, null, null, null, null, null, null, List.of()));
        assertEquals(HttpStatus.CREATED, sent.getStatusCode());
        assertEquals("clinician", sent.getBody().authorRole());

        bind("assistant-a");
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.send(new SendRequest(PATIENT, CONNECTION, UNIT, "clinical-v1",
                        null, null, null, null, null, null, null, List.of())));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("a clinical template requires clinician authorization", denial.getMessage());

        // The gate is scoped to clinical content: the same assistant may send
        // an approved non-clinical template under its own role.
        bind("admin-a");
        controller.defineTemplate(new DefineTemplateRequest(UNIT, "collection-v1", Kind.COLLECTION,
                "Collection", "Body.", "en", false, ContextType.ORDER));
        bind("assistant-a");
        ResponseEntity<Revision> nonClinical = controller.send(new SendRequest(PATIENT, CONNECTION,
                UNIT, "collection-v1", null, null, null, null, null, ContextType.ORDER,
                "order-1", List.of()));
        assertEquals(HttpStatus.CREATED, nonClinical.getStatusCode());
        assertEquals("clinical_assistant", nonClinical.getBody().authorRole());
    }

    @Test
    void replacementFreeTextStillRequiresClinician() {
        bind("clinician-a");
        ResponseEntity<Revision> created = controller.send(new SendRequest(PATIENT, CONNECTION, UNIT,
                null, Kind.GENERAL, "Original title", "Original body.", "en", null, null, null,
                List.of()));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String threadId = created.getBody().threadId();
        int factsBefore = store.facts(threadId).size();

        // A clinical assistant holds WITHDRAW but not CLINICAL_SEND: the rewrite
        // is refused and the sent revision and its fact trail stay untouched.
        bind("assistant-a");
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.replace(threadId, new ReplaceRequest("New title",
                        "New clinical text", "en", null, List.of(), "site rescheduled"), "\"1\""));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertEquals("replacement text requires clinician authorization", denial.getMessage());

        // All no-mutation checks precede the withdrawal, which legitimately changes state.
        Revision stillCurrent = store.current(threadId).orElseThrow();
        assertEquals(1, stillCurrent.revision());
        assertEquals(State.SENT, stillCurrent.state());
        assertEquals(1, store.history(threadId).size());
        List<Fact> facts = store.facts(threadId);
        assertEquals(factsBefore, facts.size());
        assertFalse(facts.stream().anyMatch(fact -> fact.type() == FactType.REPLACED
                || fact.type() == FactType.SUPERSEDED
                || (fact.type() == FactType.DISPATCHED && fact.revision() == 2)));

        // The same non-clinical role can still withdraw the thread.
        assertEquals(State.WITHDRAWN, controller.withdraw(threadId,
                new WithdrawRequest("cancelled"), "\"1\"").state());
    }

    @Test
    void authorizationIsReDerivedFromCurrentTenantMembership() {
        // The same subject and tenant context are authorized on the first command.
        bind("clinician-a");
        assertEquals(HttpStatus.CREATED, controller.send(freeText()).getStatusCode());

        // Revoking the clinician role for that subject in the same tenant takes
        // effect on the very next command: authority is re-derived, never cached.
        store.seedMember("clinician-a", List.of("clinical_assistant"));
        ApiException downgraded = assertThrows(ApiException.class, () -> controller.send(freeText()));
        assertEquals(403, downgraded.status());
        assertEquals("FORBIDDEN", downgraded.code());
        assertEquals("free text requires clinician authorization", downgraded.getMessage());

        // Losing all membership is refused at the same re-derivation boundary.
        store.seedMember("clinician-a", List.of());
        ApiException noMembership = assertThrows(ApiException.class, () -> controller.send(freeText()));
        assertEquals(403, noMembership.status());
        assertEquals("FORBIDDEN", noMembership.code());
        assertEquals("no active membership", noMembership.getMessage());

        // A missing tenant context is refused at the same execute() boundary.
        TenantContext.clear();
        ApiException noContext = assertThrows(ApiException.class, () -> controller.send(freeText()));
        assertEquals(403, noContext.status());
        assertEquals("FORBIDDEN", noContext.code());
        assertEquals("no active tenant context", noContext.getMessage());
    }

    @Test
    void configuredClinicalAuthorizationIsTheInjectedActionMatrix() {
        // The guard consults the injected matrix rather than a hard-coded role
        // literal: configuring CLINICAL_SEND for another role admits that role
        // without changing the service or the controller.
        bind("assistant-a");
        ResponseEntity<Revision> sent = controllerWith(new ConfiguredClinicalActions()).send(freeText());
        assertEquals(HttpStatus.CREATED, sent.getStatusCode());
        assertEquals("clinical_assistant", sent.getBody().authorRole());
        assertEquals(State.SENT, sent.getBody().state());
    }
}
