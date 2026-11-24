package com.aurevia.messaging.acknowledgment;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-05 HTTP acceptance for instruction acknowledgment.
 *
 * <p>The real controller, service and tenant-scoped facts are driven through the
 * Spring MVC pipeline (tenant interceptor and API error handler), so an
 * unreachable route or a revision binding that only holds at the unit layer
 * cannot pass. All subjects, charts and identifiers are synthetic.</p>
 */
class InstructionFactControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
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

    @Test
    void anOlderAcknowledgmentDoesNotSatisfyANewerRevisionOverHttp() throws Exception {
        publishRevision(1);
        acknowledgeRevision(1, "ack-first").andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.scope").value("RECEIPT"))
                .andExpect(jsonPath("$.actor").value(PATIENT_USER));

        // A newer revision that requests acknowledgment supersedes the receipt.
        publishRevision(2);
        mvc.perform(headers(get("/api/v1/instructions/overdue-follow-ups")
                        .param("unitId", UNIT), STAFF_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].revision").value(2));

        // Acknowledging the old revision fails closed and leaves the new one open.
        acknowledgeRevision(1, "ack-stale").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"));
        acknowledgeRevision(2, "ack-second").andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        mvc.perform(headers(get("/api/v1/instructions/overdue-follow-ups")
                        .param("unitId", UNIT), STAFF_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void publicationFirstViewAndAcknowledgmentAreSeparateFactsOverHttp() throws Exception {
        publishRevision(1);
        mvc.perform(headers(post("/api/v1/instructions/first-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new InstructionFacts.ViewCommand(INSTRUCTION, 1, "view-1"))),
                        PATIENT_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value(PATIENT_USER));
        acknowledgeRevision(1, "ack-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("RECEIPT"));

        mvc.perform(headers(get("/api/v1/instructions/{id}/facts", INSTRUCTION), PATIENT_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(4))
                // Publication is attributed to the publishing staff actor.
                .andExpect(jsonPath("$.facts[0].actor").value(STAFF_USER))
                // The dispatch carries only a generic key, never clinical content.
                .andExpect(jsonPath("$.facts[1].genericKey").value("NEW_INSTRUCTION"))
                // The first view is attributed to the patient, not the staff actor.
                .andExpect(jsonPath("$.facts[2].actor").value(PATIENT_USER))
                // The receipt-only acknowledgment is its own final fact.
                .andExpect(jsonPath("$.facts[3].scope").value("RECEIPT"));
    }

    private void publishRevision(int revision) throws Exception {
        PublishCommand command = new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision,
                "artifact-" + revision, true, NOW.minusSeconds(60).toString(), true,
                "publish-" + revision);
        mvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(command)), STAFF_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision))
                .andExpect(jsonPath("$.instructionId").value(INSTRUCTION));
    }

    private org.springframework.test.web.servlet.ResultActions acknowledgeRevision(
            int revision, String clientId) throws Exception {
        return mvc.perform(headers(post("/api/v1/instructions/acknowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AcknowledgeCommand(INSTRUCTION, revision, clientId))),
                        PATIENT_USER));
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder, String subject) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", ORG);
    }
}
