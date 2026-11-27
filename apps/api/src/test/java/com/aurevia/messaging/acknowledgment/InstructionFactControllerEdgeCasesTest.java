package com.aurevia.messaging.acknowledgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ResolveCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.RetryCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ViewCommand;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * Hostile and boundary inputs driven through the real MSG-05 HTTP adapter.
 *
 * <p>Each request travels through the controller, the domain service and the
 * tenant interceptor with an injected fixed clock and an in-memory fact store.
 * The assertions pin the behaviour that exists today: bounded validation before
 * any store access, role and tenant concealment, immutable revisions and
 * idempotent client keys. Every identifier, chart and text value below is
 * synthetic; no clinical payload appears in a submission or an error body.</p>
 */
class InstructionFactControllerEdgeCasesTest {

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

    /** Wire the real controller and service through the production MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryInstructionFactStore();
        seed(store);
        mvc = mvcFor(store);
    }

    /** Clear the thread-bound tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Null and whitespace-only content references fail before any store access. */
    @Test
    void nullAndBlankContentReferencesAreRejected() throws Exception {
        assertInvalid(publish(command(1, null, false, null, false, "client-1")), "INVALID_CONTENTREF");
        assertInvalid(publish(command(1, "   ", false, null, false, "client-2")), "INVALID_CONTENTREF");
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    /** The opaque content reference and the client key are both bounded to 128 UTF-16 units. */
    @Test
    void contentReferenceAndClientKeyLengthBoundariesAreEnforced() throws Exception {
        publish(command(1, "a".repeat(128), false, null, false, "k".repeat(128)))
                .andExpect(status().isOk());
        assertInvalid(publish(command(1, "a".repeat(129), false, null, false, "client-2")),
                "INVALID_CONTENTREF");
        assertInvalid(publish(command(2, "artifact-2", false, null, false, "k".repeat(129))),
                "INVALID_CLIENTID");
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
    }

    /** Emoji consume two UTF-16 units each, so 64 pass the 128-unit boundary and 65 do not. */
    @Test
    void emojiLengthIsCountedInUtf16CodeUnits() throws Exception {
        String emoji = "\uD83D\uDE00";
        publish(command(1, emoji.repeat(64), false, null, false, "client-1"))
                .andExpect(status().isOk());
        assertInvalid(publish(command(2, emoji.repeat(65), false, null, false, "client-2")),
                "INVALID_CONTENTREF");
        assertInvalid(publish(command(2, emoji.repeat(128), false, null, false, "client-3")),
                "INVALID_CONTENTREF");
        assertEquals(emoji.repeat(64), store.instruction(ORG, INSTRUCTION, 1).orElseThrow().contentRef());
    }

    /** Arabic script, a right-to-left mark and an emoji survive trimming unchanged. */
    @Test
    void arabicRightToLeftMarkAndEmojiContentReferencesPassThroughUnchanged() throws Exception {
        String contentRef = "\u0627\u0644\u0639\u0644\u0627\u062c \u200F \uD83D\uDE00";
        publish(command(1, contentRef, false, null, false, "client-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentRef").doesNotExist())
                .andExpect(jsonPath("$.patientId").doesNotExist());
        assertEquals(contentRef, store.instruction(ORG, INSTRUCTION, 1).orElseThrow().contentRef());
    }

    /** Null and malformed identifiers are rejected with the field-specific stable code. */
    @Test
    void nullAndMalformedIdentifiersAreRejected() throws Exception {
        assertInvalid(publish(new PublishCommand(null, PATIENT, UNIT, 1,
                "artifact-1", false, null, false, "client-1")), "INVALID_INSTRUCTIONID");
        assertInvalid(publish(new PublishCommand("not-a-uuid", PATIENT, UNIT, 1,
                "artifact-1", false, null, false, "client-1")), "INVALID_INSTRUCTIONID");
        assertInvalid(publish(new PublishCommand(INSTRUCTION, null, UNIT, 1,
                "artifact-1", false, null, false, "client-1")), "INVALID_PATIENTID");
        assertInvalid(publish(new PublishCommand(INSTRUCTION, PATIENT, "not-a-uuid", 1,
                "artifact-1", false, null, false, "client-1")), "INVALID_UNITID");
        mvc.perform(headers(get("/api/v1/instructions/overdue-follow-ups").param("unitId", "not-a-uuid"),
                STAFF_USER, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UNITID"));
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    /** Non-positive revisions cannot describe an immutable instruction revision. */
    @Test
    void revisionZeroAndNegativeAreRejected() throws Exception {
        assertInvalid(publish(command(0, "artifact-0", false, null, false, "client-1")), "INVALID_REVISION");
        assertInvalid(publish(command(-1, "artifact-1", false, null, false, "client-2")), "INVALID_REVISION");
    }

    /** The client key is validated on publish, retry, first view and acknowledgment. */
    @Test
    void nullBlankAndOverLengthClientKeysAreRejectedOnEveryKeyedRoute() throws Exception {
        assertInvalid(publish(command(1, "artifact-1", false, null, false, null)), "INVALID_CLIENTID");
        assertInvalid(publish(command(1, "artifact-1", false, null, false, "  ")), "INVALID_CLIENTID");

        mvc.perform(headers(post("/api/v1/instructions/dispatch/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RetryCommand(INSTRUCTION, 1, "  "))),
                STAFF_USER, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CLIENTID"));

        mvc.perform(headers(post("/api/v1/instructions/first-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ViewCommand(INSTRUCTION, 1, "k".repeat(129)))),
                PATIENT_USER, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CLIENTID"));

        assertInvalid(acknowledge(1, null, PATIENT_USER, ORG), "INVALID_CLIENTID");
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    /** A resolution reason is required and bounded to 256 characters; the boundary itself passes validation. */
    @Test
    void nullBlankAndOverLengthReasonsAreRejectedOnResolve() throws Exception {
        assertInvalid(resolve(null), "INVALID_REASON");
        assertInvalid(resolve("   "), "INVALID_REASON");
        assertInvalid(resolve("r".repeat(257)), "INVALID_REASON");
        resolve("r".repeat(256))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("follow-up not found"));
    }

    /** An unparseable due instant fails closed even when no acknowledgment was requested. */
    @Test
    void unparseableDueAtIsRejectedBeforeAnyStoreAccess() throws Exception {
        assertInvalid(publish(command(1, "artifact-1", false, "not-a-date", false, "client-1")),
                "INVALID_ACKNOWLEDGMENTDUEAT");
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    /** A requested acknowledgment cannot be published without a due instant for overdue routing. */
    @Test
    void requestedAcknowledgmentWithoutDueAtIsRejected() throws Exception {
        assertInvalid(publish(command(1, "artifact-1", true, null, false, "client-1")),
                "ACKNOWLEDGMENT_DUE_DATE_REQUIRED");
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    /** Midnight Zulu, an explicit UTC offset and a DST-gap instant with an offset are normalised to UTC. */
    @Test
    void midnightOffsetAndDstGapInstantsAreNormalisedToUtc() throws Exception {
        publish(command(1, "artifact-1", true, "2026-11-19T00:00:00Z", false, "client-1"))
                .andExpect(status().isOk());
        publish(command(2, "artifact-2", true, "2026-11-19T15:00:00+03:00", false, "client-2"))
                .andExpect(status().isOk());
        publish(command(3, "artifact-3", true, "2026-03-08T02:30:00-05:00", false, "client-3"))
                .andExpect(status().isOk());
        assertEquals(Instant.parse("2026-11-19T00:00:00Z"), dueAt(1));
        assertEquals(Instant.parse("2026-11-19T12:00:00Z"), dueAt(2));
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), dueAt(3));
    }

    /** A local wall-clock string and a DST-gap local string without an offset are rejected. */
    @Test
    void wallClockAndDstGapLocalDueAtStringsAreRejected() throws Exception {
        assertInvalid(publish(command(1, "artifact-1", true, "2026-11-19T12:00:00", false, "client-1")),
                "INVALID_ACKNOWLEDGMENTDUEAT");
        assertInvalid(publish(command(1, "artifact-1", true, "2026-03-08T02:30:00", false, "client-2")),
                "INVALID_ACKNOWLEDGMENTDUEAT");
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    /** An unreadable body maps to a fixed client error that cannot echo submitted text. */
    @Test
    void truncatedJsonBodyIsRejectedWithoutEchoingThePayload() throws Exception {
        mvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instructionId\":\"" + INSTRUCTION + "\",\"contentRef\":\"secret-artifact-"),
                STAFF_USER, ORG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value("request body could not be read"))
                .andExpect(jsonPath("$.contentRef").doesNotExist());
    }

    /** Patient credentials cannot publish, retry, resolve or read the staff follow-up queue. */
    @Test
    void patientRoleIsForbiddenOnStaffActions() throws Exception {
        assertForbidden(publish(command(1, "artifact-1", false, null, false, "client-1"), PATIENT_USER, ORG));
        assertForbidden(retry(1, "retry-1", PATIENT_USER, ORG));
        assertForbidden(resolve("owner contacted", PATIENT_USER, ORG));
        mvc.perform(headers(get("/api/v1/instructions/overdue-follow-ups").param("unitId", UNIT),
                PATIENT_USER, ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertTrue(store.publications(ORG, INSTRUCTION, 1).isEmpty());
    }

    /** Staff credentials cannot acknowledge or record a first view on the patient's behalf. */
    @Test
    void staffRoleIsForbiddenOnPatientActions() throws Exception {
        assertForbidden(acknowledge(1, "ack-1", STAFF_USER, ORG));
        mvc.perform(headers(post("/api/v1/instructions/first-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ViewCommand(INSTRUCTION, 1, "view-1"))),
                STAFF_USER, ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertTrue(store.auditEvents(ORG).stream().allMatch(event -> "DENIED".equals(event.outcome())));
    }

    /** With active membership in another organization, the same local identifiers still look absent. */
    @Test
    void otherTenantsInstructionIsConcealedOverHttp() throws Exception {
        publishRevision(1);
        // Route the follow-up in ORG first: a cross-tenant resolve must fail because
        // the lookup is tenant-scoped, not because no follow-up exists anywhere.
        mvc.perform(headers(get("/api/v1/instructions/overdue-follow-ups").param("unitId", UNIT),
                STAFF_USER, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        int orgAudits = store.auditEvents(ORG).size();
        store.addMember(OTHER_ORG, PATIENT_USER, "patient", true);
        store.linkPatient(OTHER_ORG, PATIENT_USER, PATIENT);
        store.addConnection(OTHER_ORG, PATIENT, UNIT, true);
        store.addMember(OTHER_ORG, STAFF_USER, "lab_collector", true);
        store.assignStaffUnit(OTHER_ORG, STAFF_USER, UNIT);

        assertConcealed(facts(INSTRUCTION, PATIENT_USER, OTHER_ORG), "instruction not found");
        assertConcealed(facts(INSTRUCTION, STAFF_USER, OTHER_ORG), "instruction not found");
        assertConcealed(acknowledge(1, "ack-other-org", PATIENT_USER, OTHER_ORG), "instruction not found");
        assertConcealed(resolve("owner contacted", STAFF_USER, OTHER_ORG), "follow-up not found");

        assertEquals(orgAudits, store.auditEvents(ORG).size());
        assertEquals(4, store.auditEvents(OTHER_ORG).size());
        assertTrue(store.auditEvents(OTHER_ORG).stream()
                .allMatch(event -> "NOT_FOUND".equals(event.outcome())
                        && OTHER_ORG.equals(event.organizationId())));
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
    }

    /** A publish revision older than the latest stored revision fails closed with a fresh key. */
    @Test
    void publishingAnOlderRevisionOverHttpReturnsStaleRevision() throws Exception {
        publishRevision(2);
        ResultActions result = publish(command(1, "artifact-1", true,
                NOW.minusSeconds(60).toString(), true, "publish-stale"));
        assertConflict(result, "STALE_REVISION");
        assertTrue(store.instruction(ORG, INSTRUCTION, 1).isEmpty());
    }

    /** An acknowledgment of an older revision cannot satisfy a newer published revision. */
    @Test
    void acknowledgingAnOlderRevisionOverHttpReturnsStaleRevision() throws Exception {
        publishRevision(1);
        publishRevision(2);
        assertConflict(acknowledge(1, "ack-stale", PATIENT_USER, ORG), "STALE_REVISION");
        acknowledge(2, "ack-current", PATIENT_USER, ORG).andExpect(status().isOk());
    }

    /** Replaying an identical publish key returns the original fact and never a second publication. */
    @Test
    void replayingAnIdenticalPublishKeyReturnsTheOriginalFact() throws Exception {
        PublishCommand command = command(1, "artifact-1", true, NOW.minusSeconds(60).toString(),
                true, "publish-replay");
        publish(command).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        publish(command).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.actor").value(STAFF_USER));
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
        assertEquals(1, store.clientDigestCount(ORG, STAFF_USER, "PUBLISH"));
    }

    /** Reusing a key with changed content conflicts instead of overwriting the recorded fact. */
    @Test
    void reusingAPublishKeyWithChangedPayloadReturnsIdempotencyConflict() throws Exception {
        publish(command(1, "artifact-1", false, null, false, "publish-key"))
                .andExpect(status().isOk());
        assertConflict(publish(command(1, "artifact-altered", false, null, false, "publish-key")),
                "IDEMPOTENCY_CONFLICT");
        assertEquals("artifact-1", store.instruction(ORG, INSTRUCTION, 1).orElseThrow().contentRef());
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
    }

    /** A failed client-key reservation writes a conflict and no fact, while a normal call succeeds. */
    @Test
    void aLostClientKeyReservationReturnsConflictWithoutWritingAFact() throws Exception {
        publish(command(1, "artifact-1", false, null, false, "winner-key"))
                .andExpect(status().isOk());

        LostReservationStore lostStore = new LostReservationStore();
        seed(lostStore);
        MockMvc lostMvc = mvcFor(lostStore);
        lostMvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                command(1, "artifact-1", false, null, false, "loser-key"))),
                STAFF_USER, ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.contentRef").doesNotExist());
        assertTrue(lostStore.publications(ORG, INSTRUCTION, 1).isEmpty());
        TenantContext.clear();
    }

    /** Missing or malformed tenant headers fail closed before the controller is reached. */
    @Test
    void missingAndMalformedTenantHeadersFailClosed() throws Exception {
        mvc.perform(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                command(1, "artifact-1", false, null, false, "client-1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                command(1, "artifact-1", false, null, false, "client-1"))),
                STAFF_USER, "not-a-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_SELECTION"));
        mvc.perform(headers(post("/api/v1/instructions/publish")
                        .header("X-Correlation-Id", "bad\ncorrelation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                command(1, "artifact-1", false, null, false, "client-1"))),
                STAFF_USER, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CORRELATION_ID"));
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    /** Build the MVC pipeline for one store, so a custom store can be injected for the race loser. */
    private MockMvc mvcFor(InMemoryInstructionFactStore factStore) {
        InstructionFactService service = new InstructionFactService(factStore,
                new InstructionFactActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        return MockMvcBuilders.standaloneSetup(new InstructionFactController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Seed one synthetic patient, one owning-unit staff member and their verified connection. */
    private void seed(InMemoryInstructionFactStore factStore) {
        factStore.addMember(ORG, PATIENT_USER, "patient", true);
        factStore.addMember(ORG, STAFF_USER, "lab_collector", true);
        factStore.linkPatient(ORG, PATIENT_USER, PATIENT);
        factStore.addConnection(ORG, PATIENT, UNIT, true);
        factStore.assignStaffUnit(ORG, STAFF_USER, UNIT);
    }

    /** Bind the synthetic subject and active organization headers on one request. */
    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
                                                  String subject, String organization) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization);
    }

    /** Build a synthetic publish command with only the bounded fields varied. */
    private PublishCommand command(int revision, String contentRef, boolean requiresAcknowledgment,
                                   String dueAt, boolean notify, String clientId) {
        return new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision, contentRef,
                requiresAcknowledgment, dueAt, notify, clientId);
    }

    /** Perform a publish request through the real HTTP pipeline. */
    private ResultActions publish(PublishCommand command) throws Exception {
        return publish(command, STAFF_USER, ORG);
    }

    /** Perform a publish request as a chosen subject in a chosen organization. */
    private ResultActions publish(PublishCommand command, String subject, String organization) throws Exception {
        return mvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(command)),
                subject, organization));
    }

    /** Publish one valid synthetic revision that requests an overdue receipt. */
    private void publishRevision(int revision) throws Exception {
        publish(command(revision, "artifact-" + revision, true,
                NOW.minusSeconds(60).toString(), true, "publish-" + revision))
                .andExpect(status().isOk());
    }

    /** Perform an acknowledgment request as a chosen subject in a chosen organization. */
    private ResultActions acknowledge(int revision, String clientId, String subject,
                                      String organization) throws Exception {
        return mvc.perform(headers(post("/api/v1/instructions/acknowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AcknowledgeCommand(INSTRUCTION, revision, clientId))),
                subject, organization));
    }

    /** Perform a dispatch retry request as a chosen subject in a chosen organization. */
    private ResultActions retry(int revision, String clientId, String subject,
                                String organization) throws Exception {
        return mvc.perform(headers(post("/api/v1/instructions/dispatch/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RetryCommand(INSTRUCTION, revision, clientId))),
                subject, organization));
    }

    /** Perform a follow-up resolution request as a chosen subject in a chosen organization. */
    private ResultActions resolve(String reason) throws Exception {
        return resolve(reason, STAFF_USER, ORG);
    }

    /** Perform a follow-up resolution request as a chosen subject in a chosen organization. */
    private ResultActions resolve(String reason, String subject, String organization) throws Exception {
        return mvc.perform(headers(post("/api/v1/instructions/follow-up/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ResolveCommand(INSTRUCTION, 1, reason))),
                subject, organization));
    }

    /** Read the fact timeline as a chosen subject in a chosen organization. */
    private ResultActions facts(String instructionId, String subject, String organization) throws Exception {
        return mvc.perform(headers(get("/api/v1/instructions/{id}/facts", instructionId),
                subject, organization));
    }

    /** Read the stored due instant of one synthetic instruction revision. */
    private Instant dueAt(int revision) {
        return store.instruction(ORG, INSTRUCTION, revision).orElseThrow().acknowledgmentDueAt();
    }

    /** Assert a validation failure with no echoed identifiers or clinical text. */
    private void assertInvalid(ResultActions result, String code) throws Exception {
        result.andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.contentRef").doesNotExist())
                .andExpect(jsonPath("$.patientId").doesNotExist())
                .andExpect(jsonPath("$.instructionId").doesNotExist());
    }

    /** Assert a role denial with no echoed identifiers or clinical text. */
    private void assertForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.contentRef").doesNotExist())
                .andExpect(jsonPath("$.patientId").doesNotExist());
    }

    /** Assert a conflict with its stable code and no echoed identifiers or clinical text. */
    private void assertConflict(ResultActions result, String code) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.contentRef").doesNotExist())
                .andExpect(jsonPath("$.patientId").doesNotExist());
    }

    /** Assert that a cross-tenant denial reveals only the fixed not-found error body. */
    private void assertConcealed(ResultActions result, String message) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.instructionId").doesNotExist())
                .andExpect(jsonPath("$.patientId").doesNotExist())
                .andExpect(jsonPath("$.unitId").doesNotExist())
                .andExpect(jsonPath("$.contentRef").doesNotExist())
                .andExpect(jsonPath("$.actor").doesNotExist())
                .andExpect(jsonPath("$.facts").doesNotExist());
    }

    /** Synthetic store that loses the client-key reservation race deterministically. */
    private static final class LostReservationStore extends InMemoryInstructionFactStore {
        @Override
        public boolean putClientDigest(String organizationId, String subject, String action,
                                       String clientId, String digest) {
            return false;
        }
    }
}
