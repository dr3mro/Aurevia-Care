package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.instructions.InstructionActions;
import com.aurevia.messaging.instructions.InstructionController;
import com.aurevia.messaging.instructions.InstructionService;
import com.aurevia.messaging.instructions.InstructionStore.InMemory;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-04 clause 6 HTTP acceptance: after a send, a change is either a linked
 * replacement or a withdrawal. A replacement links a new revision, marks the
 * prior revision superseded and records a patient-notification fact; a
 * withdrawal marks the revision withdrawn and records a withdrawal fact.
 * Stale versions, the wrong clinical role, a withdrawn thread, a missing
 * precondition and a peer tenant are all refused without a partial write.
 *
 * <p>Every fixture is synthetic. The store is the Spring
 * {@link com.aurevia.messaging.instructions.InstructionStore.InMemory}
 * instruction adapter and requests travel through the real controller and the
 * tenant interceptor under the synthetic-auth profile, so the proof observes
 * the same HTTP entry point a client uses. Durable persistence, real push
 * delivery and the published OpenAPI routes are documented as gaps in
 * {@code docs/spec/clauses/messaging/changes-after-sending-create-linked-replacement-withdrawal.md}
 * and are deliberately not fabricated here.</p>
 */
class ChangesAfterSendingCreateLinkedReplacementWithdrawalTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "conn-lab-1";
    private static final String UNIT = "unit-lab";
    private static final String THREAD_PATH = "/api/v1/patient-instructions";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InMemory store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemory();
        InstructionController controller = new InstructionController(
                new InstructionService(store, new InstructionActions()));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();

        // Seeding is itself tenant-scoped, so bind a context while storing the
        // synthetic fixtures, then clear it: each HTTP request re-binds the
        // caller's tenant from the synthetic headers.
        bind(ORG_A, "seeder-a");
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedMember("assistant-a", List.of("clinical_assistant"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedUnitStaff("assistant-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);

        // The peer organization uses the same local connection, patient and
        // unit identifiers so isolation is tested at the tenant boundary.
        bind(ORG_B, "seeder-b");
        store.seedMember("clinician-b", List.of("clinician"));
        store.seedUnitStaff("clinician-b", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
        TenantContext.clear();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static void bind(String org, String subject) {
        TenantContext.set(new TenantContext(org, subject, "MSG04", "corr-1"));
    }

    /** Send one free-text instruction as the synthetic clinician and return its thread id. */
    private String send(String subject, String org) throws Exception {
        MvcResult result = mvc.perform(headers(post(THREAD_PATH), subject, org)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"%s","connectionId":"%s","unitId":"%s",
                                 "templateId":null,"kind":"COLLECTION","title":"Morning collection",
                                 "text":"Please fast before 9am, 5 mL sample.","language":"en",
                                 "actionDueAt":null,"contextType":null,"contextId":null,
                                 "attachmentIds":[]}
                                """.formatted(PATIENT, CONNECTION, UNIT)))
                .andExpect(status().isCreated())
                .andReturn();
        return readJson(result).path("threadId").asText();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder headers(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String subject, String org) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org);
    }

    private static JsonNode readJson(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** Assert that a fact of a given type, revision and recipient exists in a thread view. */
    private static boolean hasFact(JsonNode view, String type, int revision, String recipient) {
        for (JsonNode fact : view.path("facts")) {
            if (type.equals(fact.path("type").asText())
                    && fact.path("revision").asInt() == revision
                    && (recipient == null || recipient.equals(fact.path("recipient").asText()))) {
                return true;
            }
        }
        return false;
    }

    /** Count facts of a type; the send itself already records one DISPATCHED fact. */
    private static int countFacts(JsonNode view, String type) {
        int count = 0;
        for (JsonNode fact : view.path("facts")) {
            if (type.equals(fact.path("type").asText())) {
                count++;
            }
        }
        return count;
    }

    private JsonNode view(String threadId, String subject, String org) throws Exception {
        return readJson(mvc.perform(headers(get(THREAD_PATH + "/" + threadId), subject, org))
                .andExpect(status().isOk())
                .andReturn());
    }

    private ResultActions replace(String threadId, String subject, String org, String ifMatch,
                                  String title, String text) throws Exception {
        var builder = headers(post(THREAD_PATH + "/" + threadId + "/replace"), subject, org)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"%s","text":"%s","language":"en","actionDueAt":null,
                         "attachmentIds":[],"reason":"site rescheduled the collection"}
                        """.formatted(title, text));
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return mvc.perform(builder);
    }

    private ResultActions withdraw(String threadId, String subject, String org, String ifMatch)
            throws Exception {
        var builder = headers(post(THREAD_PATH + "/" + threadId + "/withdraw"), subject, org)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"ordered in error\"}");
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return mvc.perform(builder);
    }

    @Test
    void replacementLinksNewRevisionSupersedesPriorAndDispatchesNotification() throws Exception {
        String threadId = send("clinician-a", ORG_A);

        JsonNode replacement = readJson(replace(threadId, "clinician-a", ORG_A, "\"1\"",
                "Updated collection", "Please fast after midnight instead.")
                .andExpect(status().isOk()).andReturn());
        assertEquals(2, replacement.path("revision").asInt());
        assertEquals(1, replacement.path("replacesRevision").asInt());
        assertEquals("SENT", replacement.path("state").asText());
        assertEquals("Updated collection", replacement.path("title").asText());

        JsonNode thread = view(threadId, "clinician-a", ORG_A);
        assertEquals(2, thread.path("history").size());
        assertEquals(1, thread.path("history").get(0).path("revision").asInt());
        assertEquals("SUPERSEDED", thread.path("history").get(0).path("state").asText());
        assertEquals(2, thread.path("history").get(0).path("supersededBy").asInt());
        assertEquals(2, thread.path("current").path("revision").asInt());

        // MSG-04 notification is represented by a DISPATCHED fact alongside the
        // REPLACED and SUPERSEDED facts; no real push gateway is wired (see Gap).
        assertTrue(hasFact(thread, "REPLACED", 2, PATIENT), "replacement must record a REPLACED fact");
        assertTrue(hasFact(thread, "SUPERSEDED", 1, null), "prior revision must record a SUPERSEDED fact");
        assertTrue(hasFact(thread, "DISPATCHED", 2, PATIENT),
                "replacement must record a patient-notification fact");
        assertEquals(2, countFacts(thread, "DISPATCHED"),
                "replacement appends a second patient-notification fact");

        JsonNode list = readJson(mvc.perform(headers(get(THREAD_PATH), "clinician-a", ORG_A)
                        .param("patientId", PATIENT))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1, list.size(), "only the current revision is patient-visible");
        assertEquals(2, list.get(0).path("revision").asInt());
    }

    @Test
    void withdrawalMarksRevisionWithdrawnAndRecordsWithdrawalFact() throws Exception {
        String threadId = send("clinician-a", ORG_A);

        JsonNode withdrawn = readJson(withdraw(threadId, "clinician-a", ORG_A, "\"1\"")
                .andExpect(status().isOk()).andReturn());
        assertEquals("WITHDRAWN", withdrawn.path("state").asText());
        assertEquals("ordered in error", withdrawn.path("withdrawalReason").asText());
        assertEquals(1, withdrawn.path("revision").asInt(),
                "withdrawal keeps the revision number; it does not create a new one");

        JsonNode thread = view(threadId, "clinician-a", ORG_A);
        assertEquals(1, thread.path("history").size(), "withdrawal must not append a revision");
        assertTrue(hasFact(thread, "WITHDRAWN", 1, PATIENT),
                "withdrawal must record a patient-visible WITHDRAWN fact");
        assertEquals(1, countFacts(thread, "DISPATCHED"),
                "withdrawal appends no new DISPATCHED fact; the one present came from the send (see Gap)");

        JsonNode list = readJson(mvc.perform(headers(get(THREAD_PATH), "clinician-a", ORG_A)
                        .param("patientId", PATIENT))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1, list.size());
        assertEquals("WITHDRAWN", list.get(0).path("state").asText());
    }

    @Test
    void staleVersionRefusesReplacementAndWritesNoRevision() throws Exception {
        String threadId = send("clinician-a", ORG_A);

        replace(threadId, "clinician-a", ORG_A, "\"9\"", "Stale", "Stale body")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        JsonNode thread = view(threadId, "clinician-a", ORG_A);
        assertEquals(1, thread.path("history").size(), "a refused replacement must write no revision");
        assertEquals("SENT", thread.path("history").get(0).path("state").asText());
        assertFalse(hasFact(thread, "REPLACED", 2, PATIENT));
        assertEquals(1, thread.path("current").path("revision").asInt());
        assertEquals(2, thread.path("facts").size(), "a refused replacement must write no fact");
    }

    @Test
    void clinicalAssistantCannotReplaceFreeTextButCanWithdraw() throws Exception {
        String threadId = send("clinician-a", ORG_A);

        // A clinical assistant holds WITHDRAW but not CLINICAL_SEND, so a
        // free-text replacement is refused at the clinical-authorization gate.
        replace(threadId, "assistant-a", ORG_A, "\"1\"", "New", "Clinical free text")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        JsonNode afterDenial = view(threadId, "clinician-a", ORG_A);
        assertEquals(1, afterDenial.path("history").size());
        assertEquals(2, afterDenial.path("facts").size(), "a refused replacement must write no fact");

        withdraw(threadId, "assistant-a", ORG_A, "\"1\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WITHDRAWN"));
    }

    @Test
    void withdrawnRevisionCannotBeReplaced() throws Exception {
        String threadId = send("clinician-a", ORG_A);
        withdraw(threadId, "clinician-a", ORG_A, "\"1\"").andExpect(status().isOk());

        replace(threadId, "clinician-a", ORG_A, "\"1\"", "Too late", "Terminal thread")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        JsonNode thread = view(threadId, "clinician-a", ORG_A);
        assertEquals(1, thread.path("history").size(), "a refused replacement must write no revision");
        assertEquals("WITHDRAWN", thread.path("current").path("state").asText());
        assertEquals(3, thread.path("facts").size(), "a refused replacement must write no fact");
    }

    @Test
    void peerTenantCannotSeeOrMutateThread() throws Exception {
        String threadId = send("clinician-a", ORG_A);

        mvc.perform(headers(get(THREAD_PATH + "/" + threadId), "clinician-b", ORG_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        replace(threadId, "clinician-b", ORG_B, "\"1\"", "Stolen", "Peer body")
                .andExpect(status().isNotFound());
        withdraw(threadId, "clinician-b", ORG_B, "\"1\"")
                .andExpect(status().isNotFound());

        JsonNode thread = view(threadId, "clinician-a", ORG_A);
        assertEquals(1, thread.path("history").size(), "the peer tenant must write nothing");
        assertEquals("SENT", thread.path("current").path("state").asText());
        assertEquals(2, thread.path("facts").size(), "a peer tenant must write no fact");
    }

    @Test
    void missingIfMatchRefusesWithdrawalAndWritesNoChange() throws Exception {
        String threadId = send("clinician-a", ORG_A);

        withdraw(threadId, "clinician-a", ORG_A, null)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        JsonNode thread = view(threadId, "clinician-a", ORG_A);
        assertEquals(1, thread.path("history").size());
        assertEquals("SENT", thread.path("current").path("state").asText());
        assertEquals(2, thread.path("facts").size(), "a refused withdrawal must write no fact");
    }
}
