package com.aurevia.smartclinic.messaging.clauses;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.messaging.governance.InMemoryMessagingGovernanceStore;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CreateExportRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceActions;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceController;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceService;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-07 clause 2 acceptance through the governance HTTP entry point.
 *
 * <p>The real controller, service, action matrix and tenant-scoped governance
 * store are driven through the Spring MVC pipeline (synthetic-auth tenant
 * interceptor and uniform API error handler). Hold placement, retention
 * decisions and export creation are therefore proven at the HTTP boundary, with
 * an injected clock and synthetic identifiers. Retention application itself has
 * no HTTP route: its eligible-set computation is exercised on the real service
 * after the hold has been placed over HTTP, and the missing execution job is
 * recorded in the companion clause document.</p>
 */
class ApplyConfiguredRetentionExportLegalHoldsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String AUDITOR = "auditor";
    private static final String PATIENT = "patient-user";
    private static final String FOREIGN_AUDITOR = "foreign-auditor";
    private static final String HELD = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String IN_WINDOW = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String EXPIRED = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryMessagingGovernanceStore store;
    private MessagingGovernanceService service;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire the real domain objects behind the controller through the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingGovernanceStore();
        store.addMember(ORG, AUDITOR, "privacy_auditor", true);
        store.addMember(ORG, PATIENT, "patient", true);
        store.addMember(OTHER_ORG, FOREIGN_AUDITOR, "privacy_auditor", true);
        // The policy is 30 days: 40 and 31 days are past the window, 10 is inside it.
        store.putRetentionPolicy(ORG, "conversation", 30);
        store.addResource(ORG, HELD, "conversation", NOW.minus(Duration.ofDays(40)));
        store.addResource(ORG, IN_WINDOW, "conversation", NOW.minus(Duration.ofDays(10)));
        store.addResource(ORG, EXPIRED, "conversation", NOW.minus(Duration.ofDays(31)));
        service = new MessagingGovernanceService(store, new MessagingGovernanceActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new MessagingGovernanceController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Clear the thread-bound tenant context after the last request of each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Place a hold over HTTP, proving the governance command reaches the controller. */
    private void placeHoldAsAuditor() throws Exception {
        String body = mapper.writeValueAsString(new PlaceHoldRequest(HELD, "Synthetic dispute"));
        mvc.perform(asAuditor(post("/api/v1/messaging-governance/legal-holds")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        startsWith("/api/v1/messaging-governance/legal-holds/")))
                .andExpect(jsonPath("$.resourceId").value(HELD))
                .andExpect(jsonPath("$.reason").value("Synthetic dispute"))
                .andExpect(jsonPath("$.placedBy").value(AUDITOR));
    }

    /** Build an export command with valid, non-sensitive provenance for a tracked source. */
    private String exportBody() throws Exception {
        return mapper.writeValueAsString(new CreateExportRequest("conversation", 600L,
                new ExportProvenance(HELD, "rev-2", "record_access")));
    }

    private MockHttpServletRequestBuilder asAuditor(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", AUDITOR)
                .header("X-Active-Organization", ORG);
    }

    private MockHttpServletRequestBuilder asPatient(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", PATIENT)
                .header("X-Active-Organization", ORG);
    }

    private MockHttpServletRequestBuilder asForeignAuditor(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", FOREIGN_AUDITOR)
                .header("X-Active-Organization", OTHER_ORG);
    }

    @Test
    void retentionHoldAndExportAreAppliedThroughTheGovernanceApi() throws Exception {
        placeHoldAsAuditor();
        mvc.perform(asAuditor(get("/api/v1/messaging-governance/retention/" + HELD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value(HELD))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("LEGAL_HOLD"));

        mvc.perform(asAuditor(get("/api/v1/messaging-governance/retention/" + EXPIRED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.reason").value("ELIGIBLE"));

        mvc.perform(asAuditor(get("/api/v1/messaging-governance/retention/" + IN_WINDOW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("RETENTION_WINDOW"));

        String location = mvc.perform(asAuditor(post("/api/v1/messaging-governance/exports")
                        .contentType(MediaType.APPLICATION_JSON).content(exportBody())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        startsWith("/api/v1/messaging-governance/exports/")))
                .andExpect(jsonPath("$.scope").value("conversation"))
                .andExpect(jsonPath("$.createdBy").value(AUDITOR))
                .andExpect(jsonPath("$.provenance.sourceReference").value(HELD))
                .andExpect(jsonPath("$.provenance.sourceRevision").value("rev-2"))
                .andExpect(jsonPath("$.provenance.purpose").value("record_access"))
                .andReturn().getResponse().getHeader("Location");
        String exportId = location.substring(location.lastIndexOf('/') + 1);
        // Provenance must survive as a durable governance fact, not only as an echo.
        assertEquals(HELD, store.export(ORG, exportId).orElseThrow().provenance().sourceReference());
        assertEquals("rev-2", store.export(ORG, exportId).orElseThrow().provenance().sourceRevision());
        assertEquals("record_access", store.export(ORG, exportId).orElseThrow().provenance().purpose());

        mvc.perform(asAuditor(get("/api/v1/messaging-governance/audit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'hold_place')]").exists())
                .andExpect(jsonPath("$[?(@.action == 'audit_read')]").exists())
                .andExpect(jsonPath("$[?(@.action == 'export')]").exists());
    }

    @Test
    void patientCannotPlaceHoldOrCreateExport() throws Exception {
        String holdBody = mapper.writeValueAsString(new PlaceHoldRequest(HELD, "Synthetic dispute"));

        mvc.perform(asPatient(post("/api/v1/messaging-governance/legal-holds")
                        .contentType(MediaType.APPLICATION_JSON).content(holdBody)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(asPatient(post("/api/v1/messaging-governance/exports")
                        .contentType(MediaType.APPLICATION_JSON).content(exportBody())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertTrue(store.legalHolds(ORG).isEmpty(), "a patient must not create a legal hold");
        assertEquals(2, store.auditEvents(ORG).size());
        assertTrue(store.auditEvents(ORG).stream()
                .allMatch(event -> event.outcome() == Outcome.DENIED));
    }

    @Test
    void retentionDecisionForAnotherOrganizationsResourceIsConcealed() throws Exception {
        mvc.perform(asForeignAuditor(get("/api/v1/messaging-governance/retention/" + HELD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertTrue(store.auditEvents(ORG).isEmpty(), "the owning tenant's trail must not be touched");
        assertEquals(1, store.auditEvents(OTHER_ORG).size());
        assertEquals(Outcome.NOT_FOUND, store.auditEvents(OTHER_ORG).get(0).outcome());
        assertEquals(HELD, store.auditEvents(OTHER_ORG).get(0).resourceId());
    }

    @Test
    void exportWithoutProvenanceIsRefused() throws Exception {
        String body = """
                {"scope":"conversation","ttlSeconds":600}
                """;

        mvc.perform(asAuditor(post("/api/v1/messaging-governance/exports")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PROVENANCE"));

        assertTrue(store.auditEvents(ORG).isEmpty(),
                "a refused export must not fabricate a successful audit fact");
    }

    @Test
    void heldResourceSurvivesRetention() throws Exception {
        placeHoldAsAuditor();
        mvc.perform(asAuditor(get("/api/v1/messaging-governance/retention/" + HELD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("LEGAL_HOLD"));

        // The interceptor clears the context after each request, so rebind it for
        // the direct service call: applyRetention has no HTTP route yet.
        TenantContext.set(new TenantContext(ORG, AUDITOR, "MSG_GOVERNANCE", "corr-apply"));
        List<String> eligible = service.applyRetention(NOW);

        assertEquals(List.of(EXPIRED), eligible);
        assertFalse(eligible.contains(HELD), "a held resource must survive retention");
        assertFalse(eligible.contains(IN_WINDOW), "an in-window resource must survive retention");
        assertEquals(1, store.auditEvents(ORG).stream()
                .filter(event -> event.action() == AuditAction.RETENTION_APPLY).count());
        assertEquals(EXPIRED, store.auditEvents(ORG).stream()
                .filter(event -> event.action() == AuditAction.RETENTION_APPLY)
                .findFirst().orElseThrow().resourceId());
    }
}
