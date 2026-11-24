package com.aurevia.messaging.reports;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** PAT-02 HTTP entry point: released reports are reachable through a real protected route. */
class ReleasedReportControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-subject-1";
    private static final String PATIENT = "patient-1";
    private static final String REPORT = "report-1";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    private InMemoryReportReleaseStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryReportReleaseStore();
        ReleasedReportService service = new ReleasedReportService(store, new InMemoryAuditSink(), NOW);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new ReleasedReportController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        store.putLink(ORG, SUBJECT, new PatientChartLink(PATIENT, true, true));
        store.putReport(new ReleasedReport(REPORT, 1, PATIENT, ORG, "Clinic A", "2026-06-10",
                "en", "document-1", "checksum-1", Status.RELEASED, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listsReleasedReportsThroughHttp() throws Exception {
        mvc.perform(get("/api/v1/patient/reports")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reportId").value(REPORT))
                .andExpect(jsonPath("$[0].revision").value(1));
    }

    @Test
    void downloadsAnAuthorizedRevisionThroughHttp() throws Exception {
        mvc.perform(get("/api/v1/patient/reports/{id}/download", REPORT)
                        .param("revision", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("document-1"))
                .andExpect(jsonPath("$.checksum").value("checksum-1"));
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(get("/api/v1/patient/reports")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unlinkedSubjectReturns403() throws Exception {
        mvc.perform(get("/api/v1/patient/reports")
                        .header("X-Synthetic-Subject", "no-link")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void invalidRevisionReturns422() throws Exception {
        mvc.perform(get("/api/v1/patient/reports/{id}/download", REPORT)
                        .param("revision", "0")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REVISION"));
    }
}
