package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.messaging.governance.InMemoryMessagingGovernanceStore;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RevocationEffect;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceActions;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceController;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceService;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

/**
 * MSG-07 clause 5 HTTP acceptance: a revoked connection records whether earlier
 * conversations stay readable under the tenant's verified record-access policy.
 *
 * <p>The real controller, service and tenant-scoped governance store are driven
 * through the Spring MVC pipeline (tenant interceptor and API error handler), so
 * an unwired route or a field that exists only at the unit layer cannot pass.
 * The policy has no HTTP setter, so it is seeded directly into the synthetic
 * store: omitting that seed would silently exercise the fail-closed default.
 * Every identifier, policy version and actor here is synthetic.</p>
 */
class RecordWhetherPriorConversationsRemainReadableUnderTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String ADMIN = "admin";
    private static final String PATIENT_USER = "patient-user";
    private static final String UNIT = "12345678-90ab-cdef-1234-567890abcdef";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String ACTIVE_CONNECTION = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String MISSING = "abcdef01-2345-6789-abcd-ef0123456789";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryMessagingGovernanceStore store;
    private MockMvc mvc;

    /** Wire the real service and store behind the controller through the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingGovernanceStore();
        store.addMember(ORG, ADMIN, "tenant_admin", true);
        store.addMember(ORG, PATIENT_USER, "patient", true);
        store.addMember(OTHER_ORG, ADMIN, "tenant_admin", true);
        store.addRecordAccessPolicy(ORG, true, "policy-v1");
        store.addConnection(ORG, CONNECTION, UNIT, PATIENT, NOW.minus(Duration.ofMinutes(5)));
        store.addConnection(ORG, ACTIVE_CONNECTION, UNIT, PATIENT, null);
        MessagingGovernanceService service = new MessagingGovernanceService(store,
                new MessagingGovernanceActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new MessagingGovernanceController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Clear the thread-bound tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void revocationEffectRecordsPriorConversationsReadableFromVerifiedPolicy() throws Exception {
        mvc.perform(headers(post(revocationUrl(CONNECTION)), ADMIN, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorConversationsReadable").value(true))
                .andExpect(jsonPath("$.recordAccessBasis").value("policy-v1"))
                .andExpect(jsonPath("$.messagingBlocked").value(true))
                .andExpect(jsonPath("$.attachmentAccessBlocked").value(true));

        List<RevocationEffect> effects = store.revocationEffects(ORG, CONNECTION);
        assertEquals(1, effects.size(), "one effect must be recorded for the revoked connection");
        assertTrue(effects.get(0).priorConversationsReadable(),
                "the recorded effect must carry the verified policy's readability");
        assertEquals("policy-v1", effects.get(0).recordAccessBasis(),
                "the recorded effect must carry the verified policy version");

        mvc.perform(headers(get("/api/v1/messaging-governance/audit"), ADMIN, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'restrict' && @.outcome == 'success')]").exists());
    }

    @Test
    void aNotReadablePolicyRecordsPriorConversationsAsNotReadable() throws Exception {
        store.addRecordAccessPolicy(ORG, false, "policy-v2");

        mvc.perform(headers(post(revocationUrl(CONNECTION)), ADMIN, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorConversationsReadable").value(false))
                .andExpect(jsonPath("$.recordAccessBasis").value("policy-v2"));

        List<RevocationEffect> effects = store.revocationEffects(ORG, CONNECTION);
        assertEquals(1, effects.size());
        assertFalse(effects.get(0).priorConversationsReadable(),
                "the recorded effect must carry the not-readable policy decision");
        assertEquals("policy-v2", effects.get(0).recordAccessBasis());
    }

    @Test
    void missingPolicyFailsClosedToNotReadable() throws Exception {
        store.clearRecordAccessPolicy(ORG);

        mvc.perform(headers(post(revocationUrl(CONNECTION)), ADMIN, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorConversationsReadable").value(false))
                .andExpect(jsonPath("$.recordAccessBasis").value("UNKNOWN"));

        List<RevocationEffect> effects = store.revocationEffects(ORG, CONNECTION);
        assertEquals(1, effects.size());
        assertFalse(effects.get(0).priorConversationsReadable(),
                "the fail-closed decision must be the one recorded");
        assertEquals("UNKNOWN", effects.get(0).recordAccessBasis());
    }

    @Test
    void aNonAdminCannotRecordReadabilityEffects() throws Exception {
        mvc.perform(headers(post(revocationUrl(CONNECTION)), PATIENT_USER, ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertTrue(store.revocationEffects(ORG, CONNECTION).isEmpty(),
                "a refused recording must not create a revocation effect");
    }

    @Test
    void anActiveConnectionCannotRecordReadabilityEffects() throws Exception {
        mvc.perform(headers(post(revocationUrl(ACTIVE_CONNECTION)), ADMIN, ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONNECTION_ACTIVE"));

        assertTrue(store.revocationEffects(ORG, ACTIVE_CONNECTION).isEmpty(),
                "an active connection must not acquire a revocation effect");
    }

    @Test
    void aMissingConnectionCannotRecordReadabilityEffects() throws Exception {
        mvc.perform(headers(post(revocationUrl(MISSING)), ADMIN, ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertTrue(store.revocationEffects(ORG, MISSING).isEmpty(),
                "a missing connection must not acquire a revocation effect");
    }

    @Test
    void anotherTenantCannotRecordEffectOnThisConnection() throws Exception {
        mvc.perform(headers(post(revocationUrl(CONNECTION)), ADMIN, OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertTrue(store.revocationEffects(ORG, CONNECTION).isEmpty(),
                "another tenant must not create an effect on this tenant's connection");
        assertTrue(store.revocationEffects(OTHER_ORG, CONNECTION).isEmpty(),
                "the refusal must not create an effect in the caller's tenant either");
    }

    /** Build the revocation-effects route for one connection identifier. */
    private static String revocationUrl(String connectionId) {
        return "/api/v1/messaging-governance/connections/" + connectionId + "/revocation-effects";
    }

    /** Bind the synthetic subject and active organization to a request. */
    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
                                                  String subject, String organizationId) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organizationId);
    }
}
