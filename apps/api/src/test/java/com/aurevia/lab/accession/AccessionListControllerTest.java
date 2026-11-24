package com.aurevia.lab.accession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** ARC-03 HTTP adapter for the lab accession list: authentication, authorization and error mapping. */
class AccessionListControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CREATED_A = "2026-11-20T09:07:06Z";

    private LabAccessionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LabAccessionService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new LabAccessionController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AccessionView view() {
        LabAccession accession = new LabAccession(ID_A, 1, "ACC-1", "LAB-A", ORG, "ORD-SYN",
                AccessionState.ACCESSIONED, null, null, null, null, null, null, null, null, null);
        return new AccessionView(accession, List.of());
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(get("/api/v1/lab/accessions").header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void forbiddenRoleReturns403() throws Exception {
        when(service.list(isNull(), isNull(), isNull()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "missing laboratory permission"));
        mvc.perform(get("/api/v1/lab/accessions")
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void badLimitReturns422() throws Exception {
        when(service.list(isNull(), eq("999"), isNull()))
                .thenThrow(new ApiException(422, "INVALID_LIMIT", "limit must be 1-200"));
        mvc.perform(get("/api/v1/lab/accessions").param("limit", "999")
                        .header("X-Synthetic-Subject", "lab-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test
    void validRequestReturnsPage() throws Exception {
        when(service.list(isNull(), isNull(), isNull())).thenReturn(
                new AccessionPage(List.of(view()), CREATED_A + ":" + ID_A));
        mvc.perform(get("/api/v1/lab/accessions")
                        .header("X-Synthetic-Subject", "lab-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].accession.id").value(ID_A))
                .andExpect(jsonPath("$.items[0].accession.accessionNo").value("ACC-1"))
                .andExpect(jsonPath("$.nextCursor").value(CREATED_A + ":" + ID_A));
    }

    @Test
    void cursorLimitAndStateArePassedThrough() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(new AccessionPage(List.of(), null));
        mvc.perform(get("/api/v1/lab/accessions")
                        .param("cursor", CREATED_A + ":" + ID_A)
                        .param("limit", "25")
                        .param("state", "received")
                        .header("X-Synthetic-Subject", "lab-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());
        verify(service).list(CREATED_A + ":" + ID_A, "25", "received");
    }
}
