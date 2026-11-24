package com.aurevia.labflow.collection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** LAB-01 HTTP adapter: collection and label entry points, auth and error mapping. */
class SpecimenCollectionControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "33333333-3333-3333-3333-333333333333";

    private SpecimenCollectionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SpecimenCollectionService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new SpecimenCollectionController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String collectBody() {
        return "{\"patientId\":\"22222222-2222-2222-2222-222222222222\",\"accessionNo\":\"ACC-1\","
                + "\"accessionIssuer\":\"LAB-A\",\"specimenType\":\"PLASMA\",\"siteCode\":\"SITE-A\","
                + "\"collector\":\"Sam Collector\",\"collectedAt\":\"2026-11-19T10:15:30Z\","
                + "\"quality\":\"acceptable\",\"expectedVersion\":1}";
    }

    private static LabAccession collected() {
        return new LabAccession(ID, 2, "ACC-1", "LAB-A", "22222222-2222-2222-2222-222222222222",
                "ORD/1", AccessionState.COLLECTED, "PLASMA", "SITE-A", "Sam Collector",
                "2026-11-19T10:15:30Z", null, "acceptable", null, null, null);
    }

    private static SpecimenLabelData label() {
        return new SpecimenLabelData("ACC-1", "LAB-A", "Synthetic Patient", "MRN-1", "CLINIC-A",
                "PLASMA", "2026-11-19T10:15:30Z");
    }

    @Test
    void collectWithoutLoginReturns401() throws Exception {
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/collect")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(collectBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void collectForbiddenRoleReturns403() throws Exception {
        when(service.collect(eq(ID), any())).thenThrow(new ApiException(403, "FORBIDDEN", "denied"));
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/collect")
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(collectBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void collectMismatchReturns422() throws Exception {
        when(service.collect(eq(ID), any()))
                .thenThrow(new ApiException(422, "PATIENT_ACCESSION_MISMATCH", "mismatch"));
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/collect")
                        .header("X-Synthetic-Subject", "lab-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(collectBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESSION_MISMATCH"));
    }

    @Test
    void collectReturns200WithRecordedSpecimen() throws Exception {
        when(service.collect(eq(ID), any())).thenReturn(collected());
        mvc.perform(post("/api/v1/lab/specimens/" + ID + "/collect")
                        .header("X-Synthetic-Subject", "lab-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(collectBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("collected"))
                .andExpect(jsonPath("$.specimenType").value("PLASMA"))
                .andExpect(jsonPath("$.siteCode").value("SITE-A"))
                .andExpect(jsonPath("$.quality").value("acceptable"));
        verify(service).collect(eq(ID), any());
    }

    @Test
    void labelReturns200WithCompleteLabelData() throws Exception {
        when(service.labelData(ID)).thenReturn(label());
        mvc.perform(get("/api/v1/lab/specimens/" + ID + "/label")
                        .header("X-Synthetic-Subject", "lab-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessionNo").value("ACC-1"))
                .andExpect(jsonPath("$.accessionIssuer").value("LAB-A"))
                .andExpect(jsonPath("$.patientName").value("Synthetic Patient"))
                .andExpect(jsonPath("$.patientMrn").value("MRN-1"))
                .andExpect(jsonPath("$.patientMrnIssuer").value("CLINIC-A"))
                .andExpect(jsonPath("$.specimenType").value("PLASMA"))
                .andExpect(jsonPath("$.collectedAt").value("2026-11-19T10:15:30Z"));
    }

    @Test
    void labelNotFoundReturns404() throws Exception {
        when(service.labelData(ID)).thenThrow(new ApiException(404, "NOT_FOUND", "concealed"));
        mvc.perform(get("/api/v1/lab/specimens/" + ID + "/label")
                        .header("X-Synthetic-Subject", "lab-b")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
