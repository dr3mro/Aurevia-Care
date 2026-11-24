package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.governance.InMemoryMessagingGovernanceStore;
import com.aurevia.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.messaging.governance.MessagingAuditContract.CopyRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.messaging.governance.MessagingAuditContract.RequestCopyRequest;
import com.aurevia.messaging.governance.MessagingGovernanceActions;
import com.aurevia.messaging.governance.MessagingGovernanceController;
import com.aurevia.messaging.governance.MessagingGovernanceService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-07 clause 7 acceptance at the HTTP boundary: a request for copies is
 * routed to the site's record-access procedure and is refused for callers the
 * rule excludes.
 *
 * <p>The positive case drives the real controller, service and tenant-scoped
 * governance store through the Spring MVC pipeline, so an unwired route or a
 * field that exists only at the unit layer cannot pass. The negatives cover the
 * role allowlist, input validation and active-membership guard. The external,
 * human-gated site procedure itself is not in this repository; see the clause
 * evidence note. All identifiers and actors here are synthetic.</p>
 */
class RequestsCopiesFollowSiteSRecordAccessTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "copy-patient";
    private static final String CLINICIAN = "copy-clinician";
    private static final String INACTIVE_PATIENT = "copy-inactive-patient";
    private static final String RESOURCE = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String PURPOSE = "record_access";
    private static final String PATH = "/api/v1/messaging-governance/copy-requests";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryMessagingGovernanceStore store;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire the real service and store behind the controller through the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingGovernanceStore();
        store.addMember(ORG, PATIENT, "patient", true);
        store.addMember(ORG, CLINICIAN, "clinician", true);
        // Allowlisted role but inactive: isolates the active-membership guard from the role allowlist.
        store.addMember(ORG, INACTIVE_PATIENT, "patient", false);
        store.addResource(ORG, RESOURCE, "conversation", NOW);
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
    void copyRequestIsRoutedToRecordAccessAndAuditedOverHttp() throws Exception {
        String body = mapper.writeValueAsString(new RequestCopyRequest(RESOURCE, PURPOSE));

        mvc.perform(postRequest(body, PATIENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROUTED"))
                .andExpect(jsonPath("$.organizationId").value(ORG))
                .andExpect(jsonPath("$.resourceId").value(RESOURCE))
                .andExpect(jsonPath("$.requestedBy").value(PATIENT))
                .andExpect(jsonPath("$.purpose").value(PURPOSE));

        List<CopyRequest> stored = store.copyRequests(ORG);
        assertEquals(1, stored.size());
        CopyRequest routed = stored.get(0);
        assertEquals("ROUTED", routed.status());
        assertEquals(ORG, routed.organizationId());
        assertEquals(PATIENT, routed.requestedBy());
        assertEquals(PURPOSE, routed.purpose());
        // The route stores only in the caller's tenant; it never merges copy requests across organizations.
        assertTrue(store.copyRequests(OTHER_ORG).isEmpty());

        List<AuditEvent> facts = store.auditEvents(ORG, RESOURCE);
        assertEquals(1, facts.size());
        assertEquals(AuditAction.COPY_REQUEST, facts.get(0).action());
        assertEquals(Outcome.SUCCESS, facts.get(0).outcome());
        assertEquals(PATIENT, facts.get(0).subject());
    }

    @Test
    void copyRequestIsRefusedForARoleWithoutCopyPermission() throws Exception {
        String body = mapper.writeValueAsString(new RequestCopyRequest(RESOURCE, PURPOSE));

        mvc.perform(postRequest(body, CLINICIAN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertTrue(store.copyRequests(ORG).isEmpty());
        List<AuditEvent> facts = store.auditEvents(ORG, RESOURCE);
        assertEquals(1, facts.size());
        assertEquals(AuditAction.COPY_REQUEST, facts.get(0).action());
        assertEquals(Outcome.DENIED, facts.get(0).outcome());
    }

    @Test
    void copyRequestIsRefusedForBlankPurposeAndInvalidResourceId() throws Exception {
        mvc.perform(postRequest(
                        mapper.writeValueAsString(new RequestCopyRequest(RESOURCE, "   ")), PATIENT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PURPOSE"));

        mvc.perform(postRequest(
                        mapper.writeValueAsString(new RequestCopyRequest("not-a-uuid", PURPOSE)), PATIENT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_RESOURCEID"));

        // Validation rejects before context and authorization, so no fact and no stored request exist.
        assertTrue(store.copyRequests(ORG).isEmpty());
        assertTrue(store.auditEvents(ORG, RESOURCE).isEmpty());
        assertTrue(store.auditEvents(ORG, "not-a-uuid").isEmpty());
    }

    @Test
    void copyRequestIsRefusedForInactiveMembership() throws Exception {
        String body = mapper.writeValueAsString(new RequestCopyRequest(RESOURCE, PURPOSE));

        mvc.perform(postRequest(body, INACTIVE_PATIENT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("no active membership"));

        assertTrue(store.copyRequests(ORG).isEmpty());
        List<AuditEvent> facts = store.auditEvents(ORG, RESOURCE);
        assertEquals(1, facts.size());
        assertEquals(AuditAction.COPY_REQUEST, facts.get(0).action());
        assertEquals(Outcome.DENIED, facts.get(0).outcome());
    }

    /** Build an authenticated synthetic copy request with a valid tenant selection. */
    private MockHttpServletRequestBuilder postRequest(String body, String subject) {
        return post(PATH)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", ORG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
