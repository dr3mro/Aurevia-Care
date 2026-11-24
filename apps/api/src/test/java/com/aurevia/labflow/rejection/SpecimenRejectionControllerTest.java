package com.aurevia.labflow.rejection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.aurevia.lab.accession.AccessionEvent;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** LAB-01 HTTP adapter: reject, recollect and specimen history entry points. */
class SpecimenRejectionControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String NEW_ID = "44444444-4444-4444-4444-444444444444";

    private SpecimenRejectionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SpecimenRejectionService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new SpecimenRejectionController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String rejectBody() {
        return "{\"reason\":\"haemolysed\",\"detail\":null,\"orderingClinician\":\"clinician-a\","
                + "\"notifyPatient\":true,\"expectedVersion\":1}";
    }

    private static String recollectBody() {
        return "{\"accessionNo\":\"ACC-2\",\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":2}";
    }

    @Test
    void rejectWithoutLoginReturns401() throws Exception {
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/reject")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(rejectBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectForbiddenRoleReturns403() throws Exception {
        when(service.reject(eq(ID), any()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "denied"));
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/reject")
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(rejectBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void rejectInvalidReasonReturns422() throws Exception {
        when(service.reject(eq(ID), any()))
                .thenThrow(new ApiException(422, "REASON_REQUIRED", "a rejection reason is required"));
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/reject")
                        .header("X-Synthetic-Subject", "verifier-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":null,\"orderingClinician\":\"clinician-a\","
                                + "\"notifyPatient\":true,\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    }

    @Test
    void rejectReturns200WithNotificationOutcome() throws Exception {
        when(service.reject(eq(ID), any())).thenReturn(new RejectionOutcome(ID, AccessionState.REJECTED,
                2, "haemolysed", "clinician-a", true, true));
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/reject")
                        .header("X-Synthetic-Subject", "verifier-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(rejectBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specimenId").value(ID))
                .andExpect(jsonPath("$.state").value("rejected"))
                .andExpect(jsonPath("$.reason").value("haemolysed"))
                .andExpect(jsonPath("$.clinicianNotified").value(true))
                .andExpect(jsonPath("$.patientNotified").value(true));
        verify(service).reject(eq(ID), any());
    }

    @Test
    void recollectReturns200WithLinkedSpecimenAndSameOrder() throws Exception {
        when(service.recollect(eq(ID), any())).thenReturn(new RecollectionOutcome(NEW_ID, ID,
                "ORD/1", AccessionState.ACCESSIONED, 1));
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/recollect")
                        .header("X-Synthetic-Subject", "verifier-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(recollectBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specimenId").value(NEW_ID))
                .andExpect(jsonPath("$.replacesAccessionId").value(ID))
                .andExpect(jsonPath("$.sourceOrderRef").value("ORD/1"))
                .andExpect(jsonPath("$.state").value("accessioned"));
        verify(service).recollect(eq(ID), any());
    }

    @Test
    void recollectConflictReturns409() throws Exception {
        when(service.recollect(eq(ID), any()))
                .thenThrow(new ApiException(409, "CONFLICT", "accession number already exists"));
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/recollect")
                        .header("X-Synthetic-Subject", "verifier-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(recollectBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void historyReturns200WithImmutableEvents() throws Exception {
        AccessionEvent event = new AccessionEvent("e1", ID, 2, "rejected", "received", "rejected",
                "haemolysed", null, "2026-11-19T11:00:00Z");
        when(service.history(ID)).thenReturn(List.of(event));
        mvc.perform(get("/api/v1/lab/specimens/" + ID + "/history")
                        .header("X-Synthetic-Subject", "verifier-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("rejected"))
                .andExpect(jsonPath("$[0].reason").value("haemolysed"));
    }

    @Test
    void historyNotFoundReturns404() throws Exception {
        when(service.history(ID)).thenThrow(new ApiException(404, "NOT_FOUND", "concealed"));
        mvc.perform(get("/api/v1/lab/specimens/" + ID + "/history")
                        .header("X-Synthetic-Subject", "verifier-b")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
