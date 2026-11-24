package com.aurevia.labflow.results;

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

/** LAB-02 HTTP adapter: entry, verification, correction, withdrawal and read. */
class ResultControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "lab-verifier-a";

    private ResultEntryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ResultEntryService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new LabResultEntryController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String entryBody() {
        return "{\"patientId\":\"22222222-2222-2222-2222-222222222222\",\"accessionNo\":\"ACC-1\","
                + "\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":1,\"owner\":\"clinician-a\","
                + "\"queue\":\"critical-queue\",\"dueAfter\":\"PT30M\",\"escalation\":\"PHONE\","
                + "\"observations\":[{\"code\":\"GLU\",\"kind\":\"numeric\",\"presence\":\"present\","
                + "\"value\":\"5.5\",\"unit\":\"mmol/L\",\"referenceLow\":\"4.0\",\"referenceHigh\":\"6.0\"}]}";
    }

    private static String decisionBody() {
        return "{\"patientId\":\"22222222-2222-2222-2222-222222222222\",\"accessionNo\":\"ACC-1\","
                + "\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":1,\"owner\":\"clinician-a\","
                + "\"queue\":\"critical-queue\",\"reason\":\"a documented correction\"}";
    }

    private static ReportView report(String state) {
        return new ReportView(ID, 1, null, state, "22222222-2222-2222-2222-222222222222",
                ID, "ORD/1", "lab-collector-a", SUBJECT, Instant.parse("2026-11-21T10:00:00Z"), null,
                List.of(new ReportView.ObservationView("GLU", "numeric", "present", "5.5", "",
                        "mmol/L", null, null, "photometry", "analyser-1", "none")));
    }

    @Test
    void enterWithoutLoginReturns401() throws Exception {
        mvc.perform(post("/api/v1/lab/results/" + ID)
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(entryBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void enterForbiddenRoleReturns403() throws Exception {
        when(service.enter(eq(ID), any())).thenThrow(new ApiException(403, "FORBIDDEN", "denied"));
        mvc.perform(post("/api/v1/lab/results/" + ID)
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(entryBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void enterReturns200WithPreliminaryReport() throws Exception {
        when(service.enter(eq(ID), any())).thenReturn(report("preliminary"));
        mvc.perform(post("/api/v1/lab/results/" + ID)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(entryBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("preliminary"))
                .andExpect(jsonPath("$.observations[0].abnormalFlag").value("none"));
        verify(service).enter(eq(ID), any());
    }

    @Test
    void verifyCorrectAndErrorReturnTheirReportStates() throws Exception {
        when(service.verify(eq(ID), any())).thenReturn(report("final"));
        when(service.correct(eq(ID), any())).thenReturn(report("corrected"));
        when(service.enterInError(eq(ID), any())).thenReturn(report("entered_in_error"));

        mvc.perform(post("/api/v1/lab/results/" + ID + "/verify")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(decisionBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("final"));
        mvc.perform(post("/api/v1/lab/results/" + ID + "/correct")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(decisionBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("corrected"));
        mvc.perform(post("/api/v1/lab/results/" + ID + "/error")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(decisionBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("entered_in_error"));
    }

    @Test
    void readReturns200AndConcealsAnUnknownReportAs404() throws Exception {
        when(service.read(ID)).thenReturn(report("preliminary"));
        mvc.perform(get("/api/v1/lab/results/" + ID)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(ID))
                .andExpect(jsonPath("$.state").value("preliminary"));

        when(service.read(ID)).thenThrow(new ApiException(404, "NOT_FOUND", "concealed"));
        mvc.perform(get("/api/v1/lab/results/" + ID)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
