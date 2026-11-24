package com.aurevia.messaging.clauses;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.governance.InMemoryMessagingGovernanceStore;
import com.aurevia.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.messaging.governance.MessagingAuditContract.LinkToChartRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.messaging.governance.MessagingAuditContract.ProvenanceLink;
import com.aurevia.messaging.governance.MessagingGovernanceActions;
import com.aurevia.messaging.governance.MessagingGovernanceController;
import com.aurevia.messaging.governance.MessagingGovernanceService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * HTTP acceptance for the MSG-07 provenance-on-chart-link rule.
 *
 * <p>Drives the real {@link com.aurevia.messaging.governance.MessagingGovernanceController}
 * and {@link com.aurevia.messaging.governance.MessagingGovernanceService} through the
 * Spring MVC pipeline (tenant interceptor and API error handler) over a fresh tenant-scoped synthetic
 * store for each case, so an unwired route, a missing role check or a dropped provenance field fails
 * here. The rule proven is: only a clinician with an active membership in the selected organization
 * may link a message or instruction for a patient with a connection in that organization, and the
 * stored link plus its audit fact keep the source references, actor and instant. This test does not
 * claim that the source message is bound to the patient; it proves the patient-connection binding the
 * service actually enforces. All identifiers, actors and references are synthetic.</p>
 */
class PreserveProvenanceAuthorizedStaffLinkClinicallyRelevantTest {

    private static final String CHART_LINKS_URL = "/api/v1/messaging-governance/chart-links";
    private static final String AUDIT_URL = "/api/v1/messaging-governance/audit";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNVERIFIED_PATIENT = "abcdef01-2345-6789-abcd-ef0123456789";
    private static final String CHART = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CONV = "c0ffee11-1111-1111-1111-111111111111";
    private static final String MSG = "d0ffee11-1111-1111-1111-111111111111";
    private static final String ACTIVE_CONNECTION = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String FOREIGN_CONNECTION = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String UNIT = "12345678-90ab-cdef-1234-567890abcdef";
    private static final String CLINICIAN = "clinician";
    private static final String AUDITOR = "auditor";
    private static final String ADMIN = "admin";
    private static final String FOREIGN_CLINICIAN = "foreign-clinician";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryMessagingGovernanceStore store;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire a fresh real service and store behind the controller through the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingGovernanceStore();
        store.addMember(ORG, CLINICIAN, "clinician", true);
        store.addMember(ORG, AUDITOR, "privacy_auditor", true);
        store.addMember(ORG, ADMIN, "tenant_admin", true);
        store.addMember(OTHER_ORG, FOREIGN_CLINICIAN, "clinician", true);
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
    void authorizedClinicianLinksInstructionToChartPreservingProvenance() throws Exception {
        MvcResult result = mvc.perform(headers(post(CHART_LINKS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(PATIENT, "instruction")), CLINICIAN, ORG))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith(CHART_LINKS_URL + "/")))
                .andExpect(jsonPath("$.organizationId").value(ORG))
                .andExpect(jsonPath("$.conversationId").value(CONV))
                .andExpect(jsonPath("$.messageId").value(MSG))
                .andExpect(jsonPath("$.patientId").value(PATIENT))
                .andExpect(jsonPath("$.chartId").value(CHART))
                .andExpect(jsonPath("$.sourceType").value("instruction"))
                .andExpect(jsonPath("$.sourceReference").value("source-ref"))
                .andExpect(jsonPath("$.revision").value("rev-2"))
                .andExpect(jsonPath("$.linkedBy").value(CLINICIAN))
                .andExpect(jsonPath("$.linkedAt").exists())
                .andReturn();

        String id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertEquals(CHART_LINKS_URL + "/" + id, result.getResponse().getHeader("Location"));

        List<ProvenanceLink> links = store.provenanceLinks(ORG, MSG);
        assertEquals(1, links.size());
        ProvenanceLink link = links.get(0);
        assertEquals(id, link.id());
        assertEquals(ORG, link.organizationId());
        assertEquals(CONV, link.conversationId());
        assertEquals(MSG, link.messageId());
        assertEquals(PATIENT, link.patientId());
        assertEquals(CHART, link.chartId());
        assertEquals("instruction", link.sourceType());
        assertEquals("source-ref", link.sourceReference());
        assertEquals("rev-2", link.revision());
        assertEquals(CLINICIAN, link.linkedBy());
        assertEquals(NOW, link.linkedAt());

        AuditEvent chartLink = store.auditEvents(ORG).stream()
                .filter(event -> event.action() == AuditAction.CHART_LINK)
                .findFirst().orElseThrow();
        assertEquals(MSG, chartLink.resourceId());
        assertEquals(CLINICIAN, chartLink.subject());
        assertEquals(Outcome.SUCCESS, chartLink.outcome());
        assertEquals("SYSTEM", chartLink.unitId());
        assertEquals("corr-1", chartLink.correlationId());
        assertEquals(ORG, chartLink.organizationId());

        // Audit read is a separate permission; the clinician who filed the link
        // cannot read the trail, so verify the fact through an authorized auditor.
        mvc.perform(headers(get(AUDIT_URL), AUDITOR, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'chart_link')]", hasSize(1)));
    }

    @Test
    void authorizedClinicianLinksMessageToChartPreservingProvenance() throws Exception {
        MvcResult result = mvc.perform(headers(post(CHART_LINKS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(PATIENT, "message")), CLINICIAN, ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("message"))
                .andExpect(jsonPath("$.messageId").value(MSG))
                .andExpect(jsonPath("$.linkedBy").value(CLINICIAN))
                .andReturn();

        String id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        List<ProvenanceLink> links = store.provenanceLinks(ORG, MSG);
        assertEquals(1, links.size());
        assertEquals(id, links.get(0).id());
        assertEquals("message", links.get(0).sourceType());
        assertEquals(PATIENT, links.get(0).patientId());
        assertEquals(NOW, links.get(0).linkedAt());
    }

    @Test
    void chartLinkByNonClinicianRoleIsForbidden() throws Exception {
        for (String subject : List.of(AUDITOR, ADMIN)) {
            mvc.perform(headers(post(CHART_LINKS_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(PATIENT, "message")), subject, ORG))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        assertTrue(store.provenanceLinks(ORG, MSG).isEmpty());
        assertEquals(2, store.auditEvents(ORG).stream()
                .filter(event -> event.action() == AuditAction.CHART_LINK
                        && event.outcome() == Outcome.DENIED)
                .count());
    }

    @Test
    void chartLinkForPatientWithoutConnectionInActiveTenantIsNotFound() throws Exception {
        store.addConnection(OTHER_ORG, FOREIGN_CONNECTION, UNIT, UNVERIFIED_PATIENT, null);

        mvc.perform(headers(post(CHART_LINKS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UNVERIFIED_PATIENT, "message")), CLINICIAN, ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertTrue(store.provenanceLinks(ORG, MSG).isEmpty());
        assertTrue(store.auditEvents(ORG).stream()
                .anyMatch(event -> event.action() == AuditAction.CHART_LINK
                        && event.outcome() == Outcome.NOT_FOUND));
        assertTrue(store.auditEvents(OTHER_ORG).isEmpty());
    }

    @Test
    void chartLinkByClinicianOfOtherTenantIsForbidden() throws Exception {
        mvc.perform(headers(post(CHART_LINKS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(PATIENT, "message")), FOREIGN_CLINICIAN, ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertTrue(store.provenanceLinks(ORG, MSG).isEmpty());
        assertTrue(store.provenanceLinks(OTHER_ORG, MSG).isEmpty());
        assertTrue(store.auditEvents(OTHER_ORG).isEmpty());
        assertTrue(store.auditEvents(ORG).stream()
                .anyMatch(event -> event.action() == AuditAction.CHART_LINK
                        && event.outcome() == Outcome.DENIED
                        && FOREIGN_CLINICIAN.equals(event.subject())));
    }

    @Test
    void chartLinkWithMalformedPatientIdIsRejected() throws Exception {
        mvc.perform(headers(post(CHART_LINKS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bad-id", "message")), CLINICIAN, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PATIENTID"));

        assertTrue(store.provenanceLinks(ORG, MSG).isEmpty());
    }

    @Test
    void chartLinkWithUnsupportedSourceTypeIsRejected() throws Exception {
        mvc.perform(headers(post(CHART_LINKS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(PATIENT, "diagnosis")), CLINICIAN, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SOURCETYPE"));

        assertTrue(store.provenanceLinks(ORG, MSG).isEmpty());
    }

    private String body(String patientId, String sourceType) throws Exception {
        return mapper.writeValueAsString(new LinkToChartRequest(
                CONV, MSG, patientId, CHART, sourceType, "source-ref", "rev-2"));
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder, String subject,
                                                  String organization) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-1");
    }
}
