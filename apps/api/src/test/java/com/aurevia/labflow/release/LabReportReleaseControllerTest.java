package com.aurevia.labflow.release;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** LAB-02 / PAT-02 release HTTP adapter. */
class LabReportReleaseControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String RELEASE = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "clinician-a";

    private LabReportReleaseService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LabReportReleaseService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new LabReportReleaseController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String releaseBody() {
        return "{\"reportId\":\"specimen-1\",\"revisionId\":\"specimen-1/v2\",\"revisionVersion\":2,"
                + "\"patientId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"policy\":{\"policyVersion\":1,\"patientKind\":\"TIMED\",\"patientDelay\":\"PT1H\"}}";
    }

    private static ReleasedReportView view(String correction, String notice) {
        return new ReleasedReportView(RELEASE, "specimen-1/v2", 2, "AUTHORIZED", ORG,
                Instant.parse("2026-11-21T11:00:00Z"), false, correction, notice);
    }

    @Test
    void releaseWithoutLoginReturns401() throws Exception {
        mvc.perform(post("/api/v1/lab/releases")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void releaseReturns200WithVisibilityDecision() throws Exception {
        when(service.release(any())).thenReturn(view(null, null));
        mvc.perform(post("/api/v1/lab/releases")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(RELEASE))
                .andExpect(jsonPath("$.patientVisibleNow").value(false));
        verify(service).release(any());
    }

    @Test
    void correctAndWithdrawReturnTheirProjection() throws Exception {
        when(service.correct(any(CorrectReleaseCommand.class)))
                .thenReturn(view("corrected to specimen-1/v3 v3: GLU corrected", null));
        when(service.withdraw(any(WithdrawReleaseCommand.class)))
                .thenReturn(view(null, "Report withdrawn: wrong patient"));

        mvc.perform(post("/api/v1/lab/releases/" + RELEASE + "/correct")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctedRevisionId\":\"specimen-1/v3\",\"correctedRevisionVersion\":3,"
                                + "\"reason\":\"GLU corrected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctionContext").value("corrected to specimen-1/v3 v3: GLU corrected"));

        mvc.perform(post("/api/v1/lab/releases/" + RELEASE + "/withdraw")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wrong patient\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawalNotice").value("Report withdrawn: wrong patient"));
    }

    @Test
    void readReturns200AndConcealsAnUnknownReleaseAs404() throws Exception {
        when(service.read(RELEASE)).thenReturn(view(null, null));
        mvc.perform(get("/api/v1/lab/releases/" + RELEASE)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value("specimen-1/v2"));

        when(service.read(RELEASE)).thenThrow(new ApiException(404, "NOT_FOUND", "concealed"));
        mvc.perform(get("/api/v1/lab/releases/" + RELEASE)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void historyReturns200AndMapsServiceConflicts() throws Exception {
        when(service.history(RELEASE)).thenReturn(List.of(new ReleaseHistoryEntry(
                "RELEASE_EVENT", RELEASE, "RELEASE_AUTHORIZE", SUBJECT, null,
                Instant.parse("2026-11-21T10:00:00Z"))));
        mvc.perform(get("/api/v1/lab/releases/" + RELEASE + "/history")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("RELEASE_EVENT"));

        when(service.correct(any(CorrectReleaseCommand.class)))
                .thenThrow(new ApiException(409, "CONFLICT", "stale"));
        mvc.perform(post("/api/v1/lab/releases/" + RELEASE + "/correct")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctedRevisionId\":\"specimen-1/v3\",\"correctedRevisionVersion\":3,"
                                + "\"reason\":\"GLU corrected\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}
