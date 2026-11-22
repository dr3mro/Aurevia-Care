package com.aurevia.smartclinic.messaging.governance;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CreateExportRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-07 HTTP acceptance for export provenance.
 *
 * <p>The real controller, service and tenant-scoped governance store are driven
 * through the Spring MVC pipeline (tenant interceptor and API error handler), so
 * an unwired route or a field that only exists at the unit layer cannot pass.
 * All identifiers and actors are synthetic.</p>
 */
class MessagingGovernanceControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String AUDITOR = "auditor";
    private static final String CONVERSATION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryMessagingGovernanceStore store;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire the real service and store behind the controller through the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingGovernanceStore();
        store.addMember(ORG, AUDITOR, "privacy_auditor", true);
        store.addResource(ORG, CONVERSATION, "conversation", NOW);
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
    void exportCreatedOverHttpCarriesItsSourceProvenance() throws Exception {
        ExportProvenance provenance = new ExportProvenance(CONVERSATION, "rev-2", "record_access");
        String body = mapper.writeValueAsString(
                new CreateExportRequest("conversation", 600L, provenance));

        mvc.perform(headers(post("/api/v1/messaging-governance/exports")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        startsWith("/api/v1/messaging-governance/exports/")))
                .andExpect(jsonPath("$.scope").value("conversation"))
                .andExpect(jsonPath("$.createdBy").value(AUDITOR))
                .andExpect(jsonPath("$.provenance.sourceReference").value(CONVERSATION))
                .andExpect(jsonPath("$.provenance.sourceRevision").value("rev-2"))
                .andExpect(jsonPath("$.provenance.purpose").value("record_access"));

        mvc.perform(headers(get("/api/v1/messaging-governance/audit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'export')]").exists());
    }

    @Test
    void exportWithoutProvenanceIsRefusedOverHttp() throws Exception {
        String body = """
                {"scope":"conversation","ttlSeconds":600}
                """;

        mvc.perform(headers(post("/api/v1/messaging-governance/exports")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PROVENANCE"));
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", AUDITOR)
                .header("X-Active-Organization", ORG);
    }
}
