package com.aurevia.labflow.outage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.labflow.outage.OutageModel.Disposition;
import com.aurevia.labflow.outage.OutageModel.EvidenceAttachment;
import com.aurevia.labflow.outage.OutageModel.ManualResult;
import com.aurevia.labflow.outage.OutageModel.OutageEvent;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** Partner-outage fallback HTTP adapter: entry, attachment link, unmatched queue and history. */
class OutageControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ORDER = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "lab-collector-a";

    private OutageService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OutageService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new OutageController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String body() {
        return "{\"patientId\":\"22222222-2222-2222-2222-222222222222\",\"accessionNo\":\"ACC-1\","
                + "\"accessionIssuer\":\"LAB-A\",\"reason\":\"partner interface down\","
                + "\"attachment\":{\"objectKey\":\"partner/report-1\",\"fileName\":\"original-report.pdf\","
                + "\"checksum\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\","
                + "\"contentType\":\"application/pdf\"},"
                + "\"observations\":[{\"code\":\"GLU\",\"kind\":\"numeric\",\"presence\":\"present\","
                + "\"value\":\"5.5\",\"unit\":\"mmol/L\"}],\"unmatchedOwner\":null}";
    }

    private static ManualResult result(Disposition disposition) {
        return new ManualResult("manual-1", disposition == Disposition.LINKED ? ORDER : null,
                disposition, "22222222-2222-2222-2222-222222222222", "partner interface down",
                new EvidenceAttachment("partner/report-1", "original-report.pdf",
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "application/pdf"),
                "GLU", null, SUBJECT, Instant.parse("2026-11-21T10:00:00Z"));
    }

    private static OutageEvent event() {
        return new OutageEvent("outage-1", ORDER, OutageService.ACTION, Disposition.LINKED,
                "partner interface down", SUBJECT, Instant.parse("2026-11-21T10:00:00Z"));
    }

    @Test
    void recordWithoutLoginReturns401() throws Exception {
        mvc.perform(post("/api/v1/lab/outage/orders/" + ORDER + "/manual-results")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void recordForbiddenRoleReturns403() throws Exception {
        when(service.record(eq(ORDER), any())).thenThrow(new ApiException(403, "FORBIDDEN", "denied"));
        mvc.perform(post("/api/v1/lab/outage/orders/" + ORDER + "/manual-results")
                        .header("X-Synthetic-Subject", "reception-a").header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void recordRequiresAReason() throws Exception {
        when(service.record(eq(ORDER), any()))
                .thenThrow(new ApiException(422, "REASON_REQUIRED", "a reason is required"));
        mvc.perform(post("/api/v1/lab/outage/orders/" + ORDER + "/manual-results")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    }

    @Test
    void recordReturns200WithTheLinkedAttachment() throws Exception {
        when(service.record(eq(ORDER), any())).thenReturn(result(Disposition.LINKED));
        mvc.perform(post("/api/v1/lab/outage/orders/" + ORDER + "/manual-results")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("linked"))
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.attachment.objectKey").value("partner/report-1"));
        verify(service).record(eq(ORDER), any());
    }

    @Test
    void attachmentLinkConcealsAMissingOrderAs404() throws Exception {
        when(service.forOrder(ORDER)).thenReturn(Optional.of(result(Disposition.LINKED)));
        mvc.perform(get("/api/v1/lab/outage/orders/" + ORDER + "/attachment")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.fileName").value("original-report.pdf"));

        when(service.forOrder(ORDER)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/lab/outage/orders/" + ORDER + "/attachment")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound());
    }

    @Test
    void unmatchedAndHistoryAreExposed() throws Exception {
        when(service.unmatchedQueue()).thenReturn(List.of(result(Disposition.UNMATCHED)));
        when(service.history(ORDER)).thenReturn(List.of(event()));

        mvc.perform(get("/api/v1/lab/outage/unmatched")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].disposition").value("unmatched"))
                .andExpect(jsonPath("$[0].attachment.objectKey").value("partner/report-1"));

        mvc.perform(get("/api/v1/lab/outage/orders/" + ORDER + "/history")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value(OutageService.ACTION))
                .andExpect(jsonPath("$[0].disposition").value("linked"))
                .andExpect(jsonPath("$[0].reason").value("partner interface down"));
    }
}
