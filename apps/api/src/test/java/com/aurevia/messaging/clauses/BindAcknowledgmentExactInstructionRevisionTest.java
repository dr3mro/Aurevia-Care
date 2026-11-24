package com.aurevia.messaging.clauses;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
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
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.messaging.acknowledgment.InstructionFactController;
import com.aurevia.messaging.acknowledgment.InstructionFactService;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InMemoryInstructionFactStore;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-05 clause proof: a patient's receipt acknowledgment is bound to one exact
 * instruction revision.
 *
 * <p>The real controller, domain service and tenant-scoped facts are driven
 * through the Spring MVC pipeline with the synthetic-auth profile, so a
 * unreachable route or a revision binding that only holds at the unit layer
 * cannot pass. Every negative case asserts the refusal code and that no receipt
 * was recorded. All subjects, charts and identifiers are synthetic.</p>
 */
class BindAcknowledgmentExactInstructionRevisionTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PEER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_USER = "patient-user";
    private static final String STAFF_USER = "unit-staff";
    private static final String PEER_PATIENT = "peer-patient";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String INSTRUCTION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    /** Only the receipt-only acknowledgment carries a scope, so this isolates receipts. */
    private static final String RECEIPTS = "$.facts[?(@.scope=='RECEIPT')]";

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
        // The peer actor has a genuine patient membership in its own tenant. The
        // request must therefore pass authorization and be concealed at the
        // instruction lookup, proving tenant isolation rather than a missing role.
        store.addMember(PEER_ORG, PEER_PATIENT, "patient", true);
    }

    /** Clear the thread-bound tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void acknowledgingTheLatestRevisionRecordsOneReceiptBoundToThatRevision() throws Exception {
        publishRevision(1);
        publishRevision(2);

        // The patient acknowledges the current revision, not the superseded one.
        acknowledgeRevision(2, "ack-latest").andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.scope").value("RECEIPT"))
                .andExpect(jsonPath("$.actor").value(PATIENT_USER));

        facts().andExpect(status().isOk())
                .andExpect(jsonPath(RECEIPTS).value(hasSize(1)))
                .andExpect(jsonPath(RECEIPTS + ".revision").value(contains(2)))
                .andExpect(jsonPath(RECEIPTS + ".actor").value(contains(PATIENT_USER)))
                .andExpect(jsonPath("$.facts[?(@.scope=='RECEIPT' && @.revision==1)]").doesNotExist());
    }

    @Test
    void acknowledgingASupersededRevisionIsRefusedAsStale() throws Exception {
        publishRevision(1);
        publishRevision(2);

        // A fresh client key is essential: a replayed successful key would skip
        // the latest-revision check and return the original receipt instead.
        acknowledgeRevision(1, "ack-stale").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"));

        facts().andExpect(status().isOk())
                .andExpect(jsonPath(RECEIPTS).doesNotExist());
    }

    @Test
    void aStaffRoleCannotAcknowledgeOnBehalfOfThePatient() throws Exception {
        publishRevision(1);

        acknowledgeRevision(1, "ack-by-staff", STAFF_USER, ORG)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        facts().andExpect(status().isOk())
                .andExpect(jsonPath(RECEIPTS).doesNotExist());
    }

    @Test
    void aPatientCannotAcknowledgeAnotherTenantsInstruction() throws Exception {
        publishRevision(1);

        // The peer patient is a valid patient in its own organization, so the
        // request gets past authorization and is concealed at the org-keyed
        // instruction lookup with 404 rather than rejected with 403.
        acknowledgeRevision(1, "ack-peer", PEER_PATIENT, PEER_ORG)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        facts().andExpect(status().isOk())
                .andExpect(jsonPath(RECEIPTS).doesNotExist());

        // Positive control: the owning tenant can still acknowledge the same
        // local identifier with a fresh key, so the refusal was tenant-scoped.
        acknowledgeRevision(1, "ack-owner", PATIENT_USER, ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.scope").value("RECEIPT"));
    }

    @Test
    void acknowledgingARevisionThatDidNotRequestItIsRefused() throws Exception {
        publishRevision(1, false, null);

        acknowledgeRevision(1, "ack-unrequested").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACKNOWLEDGMENT_NOT_REQUESTED"));

        facts().andExpect(status().isOk())
                .andExpect(jsonPath(RECEIPTS).doesNotExist());
    }

    @Test
    void reusingAnAcknowledgmentClientKeyForAnotherRevisionConflicts() throws Exception {
        publishRevision(1);
        acknowledgeRevision(1, "ack-shared").andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        publishRevision(2);

        // The recorded digest names revision 1, so the same key cannot
        // acknowledge revision 2 under a different payload.
        acknowledgeRevision(2, "ack-shared").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        facts().andExpect(status().isOk())
                .andExpect(jsonPath(RECEIPTS).value(hasSize(1)))
                .andExpect(jsonPath(RECEIPTS + ".revision").value(contains(1)));
    }

    private void publishRevision(int revision) throws Exception {
        publishRevision(revision, true, NOW.minusSeconds(60).toString());
    }

    private void publishRevision(int revision, boolean requiresAcknowledgment, String dueAt)
            throws Exception {
        PublishCommand command = new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision,
                "artifact-" + revision, requiresAcknowledgment, dueAt, true, "publish-" + revision);
        mvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(command)), STAFF_USER, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision))
                .andExpect(jsonPath("$.instructionId").value(INSTRUCTION));
    }

    private ResultActions acknowledgeRevision(int revision, String clientId) throws Exception {
        return acknowledgeRevision(revision, clientId, PATIENT_USER, ORG);
    }

    private ResultActions acknowledgeRevision(int revision, String clientId,
                                              String subject, String organization) throws Exception {
        return mvc.perform(headers(post("/api/v1/instructions/acknowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AcknowledgeCommand(INSTRUCTION, revision, clientId))),
                        subject, organization));
    }

    private ResultActions facts() throws Exception {
        return mvc.perform(headers(get("/api/v1/instructions/{id}/facts", INSTRUCTION), PATIENT_USER, ORG));
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
                                                  String subject) {
        return headers(builder, subject, ORG);
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
                                                  String subject, String organization) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization);
    }
}
