package com.aurevia.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.events.DeliveryActions;
import com.aurevia.events.DeliveryController;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventService;
import com.aurevia.events.InMemoryDeliveryStore;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * ARC-03 clause 5 proof: an idempotency key is scoped to a tenant, a caller and a
 * command, and it is bound to one request payload by a digest.
 *
 * <p>Every case drives the real {@code POST /api/v1/delivery/events} HTTP entry
 * point through the Spring MVC pipeline: the thin controller, the durable
 * domain service, the bounded action matrix and the tenant interceptor are the
 * production classes. The tenant-scoped {@code InMemoryDeliveryStore} is the
 * existing test fixture; the real PostgreSQL adapter and its forced row-level
 * security are proven by the database clause tests, not by this class. The
 * delivery flow derives the tenant from the authenticated request, scopes the
 * key to the sender and event type, binds it to a digest and refuses a reused
 * key with a different digest. A refusal is asserted both by its machine-readable
 * status and by the absence of a second committed outbox row or success audit,
 * so a replay that silently duplicated an effect cannot pass. All identifiers,
 * subjects and digests are synthetic and no clinical data is used.</p>
 *
 * <p>Evidence source: executed tests in this checkout (tool evidence). This is a
 * proof-only change; no production code, contract or migration is modified.</p>
 */
class UseIdempotencyKeysScopedTenantCallerCommandTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PEER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SENDER = "lab-partner";
    private static final String OTHER_SENDER = "radiology-partner";
    private static final String EVENT_TYPE = "observation.result";
    private static final String OTHER_EVENT_TYPE = "imaging.study";
    private static final String NAMESPACE = "partner-lab";
    private static final String KEY = "retry-key-1";
    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryDeliveryStore store;
    private MockMvc mvc;
    private ObjectMapper mapper;

    /** Wire the real controller and service behind the tenant-aware MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryStore();
        // Both tenants have an active worker so that a refusal proves scoping,
        // not a missing role. The operator is active but cannot publish.
        store.addMember(ORG, "worker", "worker", true);
        store.addMember(ORG, "operator", "operator", true);
        store.addMember(PEER_ORG, "peer-worker", "worker", true);
        DurableEventService service = new DurableEventService(store, new DeliveryActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new DeliveryController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
    }

    /** Clear the thread-bound tenant context after every request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sameKeyAndDigestReplayTheFirstOutcomeWithoutASecondOutboxRow() throws Exception {
        ResultActions first = publish(KEY, DIGEST, SENDER, EVENT_TYPE, "worker", ORG)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").value(KEY))
                .andExpect(jsonPath("$.payloadDigest").value(DIGEST));
        String firstId = eventId(first);

        // An identical retry in the same tenant, by the same caller and command
        // must return the original event, not commit a second one.
        ResultActions retry = publish(KEY, DIGEST, SENDER, EVENT_TYPE, "worker", ORG)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(firstId));
        assertEquals(firstId, eventId(retry));
        assertEquals(1, successAudits(ORG));
        assertEquals(2, store.auditEvents(ORG).size());
        assertEquals(Outcome.DUPLICATE, store.auditEvents(ORG).get(1).outcome());
        assertTrue(store.outboxEvent(ORG, firstId).isPresent());
    }

    @Test
    void reusedKeyWithDifferentDigestConflictsWithoutASecondWrite() throws Exception {
        String firstId = eventId(publish(KEY, DIGEST, SENDER, EVENT_TYPE, "worker", ORG)
                .andExpect(status().isCreated()));

        // The same key bound to another payload is a first-message-wins conflict.
        publish(KEY, OTHER_DIGEST, SENDER, EVENT_TYPE, "worker", ORG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertEquals(1, successAudits(ORG));
        assertEquals(1, store.auditEvents(ORG).stream()
                .filter(audit -> audit.outcome() == Outcome.CONFLICT).count());
        assertEquals(firstId, store.byIdempotencyKey(ORG, SENDER, EVENT_TYPE, KEY)
                .orElseThrow().eventId());
    }

    @Test
    void theKeyIsScopedToItsTenantSoAnotherTenantCannotReplayIt() throws Exception {
        String firstId = eventId(publish(KEY, DIGEST, SENDER, EVENT_TYPE, "worker", ORG)
                .andExpect(status().isCreated()));

        // A peer-tenant worker with the same key and payload proceeds to a new
        // event; the key never escapes its owning tenant's dedup namespace.
        ResultActions peer = publish(KEY, DIGEST, SENDER, EVENT_TYPE, "peer-worker", PEER_ORG)
                .andExpect(status().isCreated());
        String peerId = eventId(peer);
        assertNotEquals(firstId, peerId);
        assertTrue(store.outboxEvent(ORG, firstId).isPresent());
        assertTrue(store.outboxEvent(PEER_ORG, peerId).isPresent());
        assertTrue(store.outboxEvent(PEER_ORG, firstId).isEmpty());
        assertEquals(1, successAudits(ORG));
        assertEquals(1, successAudits(PEER_ORG));
    }

    @Test
    void aDifferentCallerAndCommandWithTheSameKeyDoNotReplay() throws Exception {
        String firstId = eventId(publish(KEY, DIGEST, SENDER, EVENT_TYPE, "worker", ORG)
                .andExpect(status().isCreated()));

        // The dedup key includes the sender and event type, so changing either
        // part of the caller/command scope is a fresh command, not a replay.
        String otherCallerId = eventId(publish(KEY, DIGEST, OTHER_SENDER, EVENT_TYPE, "worker", ORG)
                .andExpect(status().isCreated()));
        String otherCommandId = eventId(publish(KEY, DIGEST, SENDER, OTHER_EVENT_TYPE, "worker", ORG)
                .andExpect(status().isCreated()));
        assertNotEquals(firstId, otherCallerId);
        assertNotEquals(firstId, otherCommandId);
        assertNotEquals(otherCallerId, otherCommandId);
        assertEquals(3, successAudits(ORG));
    }

    @Test
    void missingIdempotencyKeyIsRejectedWithoutAnyWrite() throws Exception {
        // A blank key cannot be claimed; the command is refused at the boundary
        // and leaves no outbox row and no audit fact.
        publish(publishRequest("   ", DIGEST, SENDER, EVENT_TYPE), "worker", ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCYKEY"));

        assertEquals(0, store.auditEvents(ORG).size());
    }

    @Test
    void aReplayStillRequiresCurrentAuthorization() throws Exception {
        String firstId = eventId(publish(KEY, DIGEST, SENDER, EVENT_TYPE, "worker", ORG)
                .andExpect(status().isCreated()));

        // The original outcome is returned only while the caller is still
        // authorized: an active operator lacks the publish action and is refused.
        publish(KEY, DIGEST, SENDER, EVENT_TYPE, "operator", ORG)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertEquals(1, successAudits(ORG));
        assertEquals(1, store.auditEvents(ORG).stream()
                .filter(audit -> audit.outcome() == Outcome.DENIED).count());
        assertTrue(store.outboxEvent(ORG, firstId).isPresent());
    }

    /** Build the HTTP body for a publish command with synthetic values. */
    private static PublishRequest publishRequest(String key, String digest, String sender, String eventType) {
        return new PublishRequest(AGGREGATE, 1, sender, eventType, NAMESPACE, digest, key);
    }

    /** Post one publish command as the given synthetic subject and tenant. */
    private ResultActions publish(String key, String digest, String sender, String eventType,
                                   String subject, String organization) throws Exception {
        return publish(publishRequest(key, digest, sender, eventType), subject, organization);
    }

    /** Post one publish request through the real controller with auth headers. */
    private ResultActions publish(PublishRequest request, String subject, String organization)
            throws Exception {
        return mvc.perform(post("/api/v1/delivery/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-clause")
                .content(mapper.writeValueAsString(request)));
    }

    /** Read the immutable event id assigned by the server. */
    private String eventId(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("eventId").asText();
    }

    /** Count committed publish facts for a tenant; a retry must never add one. */
    private long successAudits(String organization) {
        return store.auditEvents(organization).stream()
                .filter(audit -> audit.outcome() == Outcome.SUCCESS).count();
    }
}
