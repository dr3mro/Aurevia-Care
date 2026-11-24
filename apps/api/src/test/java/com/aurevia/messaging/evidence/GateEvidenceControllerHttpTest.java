package com.aurevia.messaging.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.messaging.evidence.GateEvidenceContract.AccessibilityCheck;
import com.aurevia.messaging.evidence.GateEvidenceContract.AuditEvent;
import com.aurevia.messaging.evidence.GateEvidenceContract.Availability;
import com.aurevia.messaging.evidence.GateEvidenceContract.DowntimeRunbook;
import com.aurevia.messaging.evidence.GateEvidenceContract.EvidenceInput;
import com.aurevia.messaging.evidence.GateEvidenceContract.IdempotentCommand;
import com.aurevia.messaging.evidence.GateEvidenceContract.JournalEvent;
import com.aurevia.messaging.evidence.GateEvidenceContract.LatencySample;
import com.aurevia.messaging.evidence.GateEvidenceContract.LoadProfile;
import com.aurevia.messaging.evidence.GateEvidenceContract.RestoreResult;
import com.aurevia.messaging.evidence.GateEvidenceContract.Snapshot;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** G3 gate-evidence HTTP entry point: the composite report is reachable through a protected route. */
class GateEvidenceControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-19T16:00:00Z");

    private MockMvc mvc;
    private String body;
    private GateEvidenceController controller;
    private EvidenceInput input;

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        GateEvidenceService service = new GateEvidenceService(new EvidenceHash(), clock);
        controller = new GateEvidenceController(service);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        input = new EvidenceInput(
                new Snapshot("snap", "v1", NOW.minusSeconds(60)),
                List.of(new JournalEvent("evt", "hash", NOW.minusSeconds(30))),
                new RestoreResult("state", List.of("evt"), NOW.minusSeconds(20), NOW.minusSeconds(10)),
                new DowntimeRunbook(true, true, true, true),
                List.of(new IdempotentCommand("key", "digest")),
                List.of(new IdempotentCommand("key", "digest")),
                LoadProfile.PILOT, List.of(new LatencySample(false, 40)),
                new Availability(999, 1000),
                List.of(new AccessibilityCheck("LABEL", "en", false, true, "ux-owner"),
                        new AccessibilityCheck("LABEL", "ar", true, true, "ux-owner")),
                List.of(new AuditEvent("subject", "TREATMENT", "facility", "corr", "READ", "chart",
                        NOW.minusSeconds(5), "SUCCESS")));
        body = new ObjectMapper().writeValueAsString(input);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void producesReportThroughHttp() throws Exception {
        mvc.perform(post("/api/v1/gate-evidence/produce")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Synthetic-Subject", "auditor-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(ORG))
                .andExpect(jsonPath("$.records.length()").value(5))
                .andExpect(jsonPath("$.verdict").exists());
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        ApiException error = assertThrows(ApiException.class, () -> controller.produce(input));
        assertEquals(401, error.status());
        assertEquals("UNAUTHENTICATED", error.code());
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(post("/api/v1/gate-evidence/produce")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
