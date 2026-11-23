package com.aurevia.smartclinic.staffmsg.governance;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
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
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.ExportRequest;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.MessageView;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.RecordActionRequest;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.AttachmentLink;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ScanState;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * HTTP acceptance for staff-message governance through the real MVC pipeline.
 *
 * <p>The controller, service and synthetic tenant-scoped store are driven with
 * the tenant interceptor and API error handler, so an unwired route or a field
 * that exists only at the unit layer cannot pass. All actors and identifiers
 * are synthetic.</p>
 */
class StaffMessageGovernanceControllerHttpTest {

    private static final String BASE = "/api/v1/staff-messages/governance";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String CONV = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String CONV2 = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String M1 = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String M2 = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String FOREIGN_CONV = "aaaaaaaa-0000-0000-0000-000000000009";
    private static final String FOREIGN_MESSAGE = "bbbbbbbb-0000-0000-0000-000000000009";
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    private InMemoryStaffMessageGovernanceStore store;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire the service and store behind the controller through the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryStaffMessageGovernanceStore();
        store.addMember(ORG, "nurse-a", "unit_staff", true);
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        store.addMember(OTHER_ORG, "nurse-a", "unit_staff", true);
        store.addMember(OTHER_ORG, "auditor", "privacy_auditor", true);
        store.addConversation(ORG, CONV, NOW.minus(Duration.ofDays(40)));
        store.addConversation(ORG, CONV2, NOW.minus(Duration.ofDays(40)));
        store.addConversation(OTHER_ORG, FOREIGN_CONV, NOW.minus(Duration.ofDays(40)));
        for (String conversation : new String[] {CONV, CONV2}) {
            store.addParticipant(ORG, conversation, "nurse-a");
        }
        store.addParticipant(OTHER_ORG, FOREIGN_CONV, "nurse-a");
        store.addMessage(ORG, new MessageView(M1, CONV, "clinician", "Lab review", NOW, "lab results"));
        store.addMessage(ORG, new MessageView(M2, CONV2, "clinician", "Other conversation", NOW, "private"));
        store.addMessage(OTHER_ORG, new MessageView(FOREIGN_MESSAGE, FOREIGN_CONV, "nurse-a", "Foreign",
                NOW, "foreign content"));
        store.addLink(ORG, M1, new AttachmentLink(ORG, "cccccccc-0000-0000-0000-000000000011", M1,
                "doc-1", "sha-http-1", ScanState.CLEAN));
        store.putPolicy(ORG, "conversation", 30);

        StaffMessageGovernanceService service = new StaffMessageGovernanceService(store,
                new StaffMessageGovernanceActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new StaffMessageGovernanceController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequestBuilder asNurse(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", "nurse-a").header("X-Active-Organization", ORG);
    }

    private MockHttpServletRequestBuilder asAuditor(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", "auditor").header("X-Active-Organization", ORG);
    }

    @Test
    void searchOverHttpReturnsOnlyTheActorConversationAndAuditsTheRead() throws Exception {
        mvc.perform(asNurse(get(BASE + "/search").param("limit", "10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits.length()").value(2))
                .andExpect(jsonPath("$.hits[?(@.messageId == '" + M1 + "')]").exists())
                .andExpect(jsonPath("$.hits[?(@.messageId == '" + FOREIGN_MESSAGE + "')]").doesNotExist());
        mvc.perform(asAuditor(get(BASE + "/audit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'audit_read')]").exists());
    }

    @Test
    void exportOverHttpCarriesEveryAttachmentChecksum() throws Exception {
        ExportProvenance provenance = new ExportProvenance(CONV, "rev-2", "record_access");
        String body = mapper.writeValueAsString(new ExportRequest(CONV, provenance));
        mvc.perform(asAuditor(post(BASE + "/exports").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith(BASE + "/exports/")))
                .andExpect(jsonPath("$.conversationId").value(CONV))
                .andExpect(jsonPath("$.createdBy").value("auditor"))
                .andExpect(jsonPath("$.checksums[0]").value("sha-http-1"))
                .andExpect(jsonPath("$.provenance.purpose").value("record_access"));
    }

    @Test
    void commandsAreReachableOverHttpWithServerDerivedAudit() throws Exception {
        String action = mapper.writeValueAsString(new RecordActionRequest("send", CONV, "facility"));
        mvc.perform(asNurse(post(BASE + "/actions").contentType(MediaType.APPLICATION_JSON).content(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("send"))
                .andExpect(jsonPath("$.outcome").value("success"));

        mvc.perform(asNurse(post(BASE + "/messages/" + M1 + "/hide")
                        .param("conversationId", CONV)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("delete"));

        String hold = mapper.writeValueAsString(new PlaceHoldRequest(CONV, "Synthetic dispute"));
        mvc.perform(asAuditor(post(BASE + "/legal-holds")
                        .contentType(MediaType.APPLICATION_JSON).content(hold)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceId").value(CONV));

        mvc.perform(asAuditor(get(BASE + "/retention/" + CONV)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("LEGAL_HOLD"));

        String holdId = store.legalHolds(ORG).get(0).id();
        mvc.perform(asAuditor(post(BASE + "/legal-holds/" + holdId + "/release")
                        .param("reason", "Dispute resolved")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedBy").value("auditor"));
    }

    @Test
    void unauthenticatedAndForbiddenRequestsAreRefused() throws Exception {
        mvc.perform(get(BASE + "/search").param("limit", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        String body = mapper.writeValueAsString(
                new ExportRequest(CONV, new ExportProvenance(CONV, "rev-2", "record_access")));
        mvc.perform(asNurse(post(BASE + "/exports").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void crossTenantRetentionIsConcealedOverHttp() throws Exception {
        mvc.perform(get(BASE + "/retention/" + CONV)
                        .header("X-Synthetic-Subject", "auditor")
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void productionConstructorAcceptsTheInjectedService() {
        StaffMessageGovernanceService service = new StaffMessageGovernanceService(store,
                new StaffMessageGovernanceActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertNotNull(new StaffMessageGovernanceController(service));
    }
}
