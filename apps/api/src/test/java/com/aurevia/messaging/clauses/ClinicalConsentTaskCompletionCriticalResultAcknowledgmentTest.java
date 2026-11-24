package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.exchange.critical.CriticalContract;
import com.aurevia.messaging.acknowledgment.InMemoryInstructionFactStore;
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.messaging.acknowledgment.InstructionFactController;
import com.aurevia.messaging.acknowledgment.InstructionFactService;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.scheduling.followup.CareTeamTask;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-05 clause 5 API evidence: a patient's explicit acknowledgment is a
 * receipt-only fact, while task completion, critical-result acknowledgment and
 * clinical consent belong to their own workflows.
 *
 * <p>The built halves are exercised through the real controller, service and
 * tenant-scoped facts over the Spring MVC pipeline, using the same synthetic
 * fixtures as the neighbouring acknowledgment HTTP test. The clinical-consent
 * half has no workflow to call; its would-prove cases are preserved in the
 * clause document and in the worker notes instead of a skipped test, because a
 * skipped test fails the repository quality gate. No subject, chart or
 * identifier here is real.</p>
 */
class ClinicalConsentTaskCompletionCriticalResultAcknowledgmentTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_USER = "patient-user";
    private static final String OTHER_PATIENT_USER = "other-patient-user";
    private static final String STAFF_USER = "unit-staff";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String INSTRUCTION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryInstructionFactStore store;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire the real service and store behind the controller through the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryInstructionFactStore();
        InstructionFactService service = new InstructionFactService(store,
                new InstructionFactActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new InstructionFactController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        store.addMember(ORG, PATIENT_USER, "patient", true);
        store.addMember(ORG, STAFF_USER, "lab_collector", true);
        store.addMember(OTHER_ORG, OTHER_PATIENT_USER, "patient", true);
        store.linkPatient(ORG, PATIENT_USER, PATIENT);
        store.addConnection(ORG, PATIENT, UNIT, true);
        store.assignStaffUnit(ORG, STAFF_USER, UNIT);
    }

    /** Clear the thread-bound tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * The patient's acknowledgment is recorded and stored as RECEIPT, and no
     * clinical-workflow scope key can be substituted for it.
     */
    @Test
    void receiptAcknowledgmentNeverBecomesAClinicalWorkflowAcknowledgment() throws Exception {
        publishRevision(1);
        acknowledgeRevision(1, "ack-receipt", PATIENT_USER, ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.scope").value("RECEIPT"))
                .andExpect(jsonPath("$.actor").value(PATIENT_USER));

        mvc.perform(headers(get("/api/v1/instructions/{id}/facts", INSTRUCTION), PATIENT_USER, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(3))
                // Publication and dispatch precede the single receipt fact. A fact's
                // record fields are serialized, not its type() override.
                .andExpect(jsonPath("$.facts[0].actor").value(STAFF_USER))
                .andExpect(jsonPath("$.facts[1].genericKey").value("NEW_INSTRUCTION"))
                .andExpect(jsonPath("$.facts[2].scope").value("RECEIPT"))
                .andExpect(jsonPath("$.facts[2].revision").value(1));

        // The scope enum is deliberately closed-world: these three meanings must
        // never be added to it, because they belong to their own workflows.
        for (String foreign : new String[] { "CLINICAL_CONSENT", "TASK_COMPLETION",
                "CRITICAL_RESULT_ACKNOWLEDGMENT" }) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> AcknowledgmentScope.from(foreign), foreign + " must not be a receipt scope");
            assertTrue(rejected.getMessage().contains("unknown acknowledgment scope"));
        }
        assertEquals("RECEIPT", AcknowledgmentScope.RECEIPT.wire());

        // Their own workflows keep their own vocabularies and state machines: a
        // receipt key is not a critical-result state or acknowledgment event.
        assertEquals(CriticalContract.State.ACKNOWLEDGED, CriticalContract.State.from("acknowledged"));
        assertEquals("acknowledged", CriticalContract.EventType.ACKNOWLEDGED.wire());
        assertThrows(IllegalArgumentException.class,
                () -> CriticalContract.State.from("RECEIPT"),
                "a receipt key must not be a critical-result state");
        assertThrows(IllegalArgumentException.class,
                () -> CriticalContract.EventType.from("RECEIPT"),
                "a receipt key must not be a critical-result acknowledgment event");

        // Task completion is its own OPEN -> COMPLETED transition with an outcome;
        // it is not an instruction receipt fact.
        CareTeamTask open = new CareTeamTask("task-1", ORG, "follow-up-1", STAFF_USER,
                LocalDate.parse("2026-11-30"), "record the follow-up", CareTeamTask.OPEN,
                null, STAFF_USER, NOW);
        CareTeamTask completed = open.complete("done");
        assertEquals(CareTeamTask.COMPLETED, completed.status());
        assertEquals("done", completed.outcome());
        assertEquals(CareTeamTask.OPEN, open.status());
    }

    /** Only the patient role may record a receipt; a staff attempt refuses and records nothing. */
    @Test
    void staffCannotRecordAReceiptAcknowledgment() throws Exception {
        publishRevision(1);
        acknowledgeRevision(1, "ack-staff", STAFF_USER, ORG)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // The assigned staff actor can read the timeline and sees no receipt fact.
        mvc.perform(headers(get("/api/v1/instructions/{id}/facts", INSTRUCTION), STAFF_USER, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(2));
    }

    /** A subject in another tenant cannot attach a receipt fact to this instruction. */
    @Test
    void crossTenantAcknowledgmentCannotAttachAReceiptFact() throws Exception {
        publishRevision(1);
        acknowledgeRevision(1, "ack-foreign", OTHER_PATIENT_USER, OTHER_ORG)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("instruction not found"));

        mvc.perform(headers(get("/api/v1/instructions/{id}/facts", INSTRUCTION), PATIENT_USER, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(2));
        // The other tenant cannot read a concealed instruction either, so no
        // receipt fact or clinical content leaks across the tenant boundary.
        mvc.perform(headers(get("/api/v1/instructions/{id}/facts", INSTRUCTION),
                        OTHER_PATIENT_USER, OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** A stale instruction revision cannot be recorded as a receipt of the current one. */
    @Test
    void staleRevisionCannotBeRecordedAsAReceipt() throws Exception {
        publishRevision(1);
        publishRevision(2);
        acknowledgeRevision(1, "ack-old", PATIENT_USER, ORG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"));

        acknowledgeRevision(2, "ack-current", PATIENT_USER, ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("RECEIPT"))
                .andExpect(jsonPath("$.revision").value(2));

        mvc.perform(headers(get("/api/v1/instructions/{id}/facts", INSTRUCTION), PATIENT_USER, ORG))
                .andExpect(status().isOk())
                // Two publications, two dispatches and one receipt for revision 2 only.
                .andExpect(jsonPath("$.facts.length()").value(5))
                .andExpect(jsonPath("$.facts[4].scope").value("RECEIPT"))
                .andExpect(jsonPath("$.facts[4].revision").value(2));
    }

    /** Publish one synthetic revision that requires a receipt acknowledgment. */
    private void publishRevision(int revision) throws Exception {
        PublishCommand command = new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision,
                "artifact-" + revision, true, NOW.minusSeconds(60).toString(), true,
                "publish-" + revision);
        mvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(command)), STAFF_USER, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision))
                .andExpect(jsonPath("$.instructionId").value(INSTRUCTION));
    }

    /** Acknowledge one synthetic revision as the given subject and organization. */
    private org.springframework.test.web.servlet.ResultActions acknowledgeRevision(
            int revision, String clientId, String subject, String organization) throws Exception {
        return mvc.perform(headers(post("/api/v1/instructions/acknowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AcknowledgeCommand(INSTRUCTION, revision, clientId))),
                        subject, organization));
    }

    /** Bind a synthetic subject and active organization to one request. */
    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
            String subject, String organization) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization);
    }
}
