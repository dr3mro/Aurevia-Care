package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.acknowledgment.InMemoryInstructionFactStore;
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.messaging.acknowledgment.InstructionFactController;
import com.aurevia.messaging.acknowledgment.InstructionFactService;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-05 clause 4 (API test): an explicit acknowledgment confirms receipt only.
 *
 * <p>The receipt is a distinct RECEIPT-scoped fact bound to the exact instruction
 * revision. It is refused for the wrong role, another tenant, a superseded
 * revision and an instruction that did not request one. Every case enters
 * through the real acknowledge route with the tenant interceptor and API error
 * handler in the MVC pipeline, so route binding and the error code are proven
 * together. The store, service, action matrix and clock are real; all subjects,
 * charts, units and identifiers are synthetic.</p>
 */
class AcknowledgmentConfirmsReceiptTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_USER = "patient-user";
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
        store.linkPatient(ORG, PATIENT_USER, PATIENT);
        store.addConnection(ORG, PATIENT, UNIT, true);
        store.assignStaffUnit(ORG, STAFF_USER, UNIT);
    }

    /** Clear the thread-bound tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A patient receipt is a separate RECEIPT fact bound to the published revision. */
    @Test
    void acknowledgmentRecordsASeparateReceiptScopedFactBoundToTheExactRevision() throws Exception {
        publishRevision(1, true);

        acknowledgeRevision(1, "ack-receipt").andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("RECEIPT"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.actor").value(PATIENT_USER));

        // The receipt is appended as its own fact next to publication and dispatch,
        // never merged into a view, and it carries the requested revision.
        mvc.perform(headers(get("/api/v1/instructions/{id}/facts", INSTRUCTION), PATIENT_USER, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(3))
                .andExpect(jsonPath("$.facts[2].scope").value("RECEIPT"))
                .andExpect(jsonPath("$.facts[2].revision").value(1))
                .andExpect(jsonPath("$.facts[2].actor").value(PATIENT_USER));

        // Reinforce the existing domain test: this acknowledgment can never mean
        // clinical consent, task completion or a critical-result acknowledgment.
        assertEquals(1, AcknowledgmentScope.values().length);
        assertThrows(IllegalArgumentException.class, () -> AcknowledgmentScope.from("consent"));
        assertThrows(IllegalArgumentException.class, () -> AcknowledgmentScope.from("critical_result"));
    }

    /** Only the patient may acknowledge; staff cannot do it on the patient's behalf. */
    @Test
    void staffCannotAcknowledgeOnBehalfOfThePatient() throws Exception {
        publishRevision(1, true);

        acknowledgeRevision(1, "ack-staff", STAFF_USER, ORG)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** A valid patient of another tenant cannot see or acknowledge this instruction. */
    @Test
    void acknowledgmentOfAnotherTenantsInstructionIsConcealed() throws Exception {
        publishRevision(1, true);
        // Make the caller a fully authorized patient in the second tenant, so a
        // 404 can only come from tenant isolation and not from a role or link check.
        store.addMember(OTHER_ORG, PATIENT_USER, "patient", true);
        store.linkPatient(OTHER_ORG, PATIENT_USER, PATIENT);
        store.addConnection(OTHER_ORG, PATIENT, UNIT, true);

        acknowledgeRevision(1, "ack-other-tenant", PATIENT_USER, OTHER_ORG)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** The receipt of an older revision cannot satisfy a newer published revision. */
    @Test
    void acknowledgmentOfASupersededRevisionIsRefused() throws Exception {
        publishRevision(1, true);
        publishRevision(2, true);

        acknowledgeRevision(1, "ack-stale")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"));
    }

    /** An instruction that did not request acknowledgment refuses a receipt. */
    @Test
    void acknowledgmentIsRefusedWhenNotRequested() throws Exception {
        publishRevision(1, false);

        acknowledgeRevision(1, "ack-unrequested")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACKNOWLEDGMENT_NOT_REQUESTED"));
    }

    /** Publish one synthetic revision, optionally requesting a receipt. */
    private void publishRevision(int revision, boolean requiresAcknowledgment) throws Exception {
        PublishCommand command = new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision,
                "artifact-" + revision, requiresAcknowledgment,
                requiresAcknowledgment ? NOW.plusSeconds(60).toString() : null,
                true, "publish-" + revision);
        mvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(command)), STAFF_USER, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision))
                .andExpect(jsonPath("$.instructionId").value(INSTRUCTION));
    }

    /** Acknowledge one revision as the synthetic patient in the primary tenant. */
    private ResultActions acknowledgeRevision(int revision, String clientId) throws Exception {
        return acknowledgeRevision(revision, clientId, PATIENT_USER, ORG);
    }

    /** Acknowledge one revision with an explicit synthetic subject and active organization. */
    private ResultActions acknowledgeRevision(int revision, String clientId, String subject, String org)
            throws Exception {
        return mvc.perform(headers(post("/api/v1/instructions/acknowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AcknowledgeCommand(INSTRUCTION, revision, clientId))),
                        subject, org));
    }

    /** Add the synthetic authentication and active-tenant headers to one request. */
    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder, String subject,
                                                  String org) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org);
    }
}
