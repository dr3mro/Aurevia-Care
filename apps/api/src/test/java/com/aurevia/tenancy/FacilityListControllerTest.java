package com.aurevia.tenancy;

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
import com.aurevia.web.FacilityController;
import com.aurevia.web.TenantContextInterceptor;

/** ARC-03 HTTP adapter for the facility list: authentication, authorization and error mapping. */
class FacilityListControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private FacilityService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FacilityService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new FacilityController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(get("/api/v1/facilities").header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void forbiddenRoleReturns403() throws Exception {
        when(service.list(isNull(), isNull()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "missing action permission"));
        mvc.perform(get("/api/v1/facilities")
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void badLimitReturns422() throws Exception {
        when(service.list(isNull(), org.mockito.ArgumentMatchers.eq("999")))
                .thenThrow(new ApiException(422, "INVALID_LIMIT", "limit must be 1-200"));
        mvc.perform(get("/api/v1/facilities").param("limit", "999")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test
    void validRequestReturnsPage() throws Exception {
        when(service.list(isNull(), isNull())).thenReturn(
                new FacilityPage(List.of(new Facility(ORG, "ALPHA", "Alpha", "UTC")),
                        "ALPHA:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        mvc.perform(get("/api/v1/facilities")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("ALPHA"))
                .andExpect(jsonPath("$.items[0].timeZone").value("UTC"))
                .andExpect(jsonPath("$.nextCursor").value("ALPHA:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }

    @Test
    void cursorAndLimitArePassedThrough() throws Exception {
        when(service.list(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new FacilityPage(List.of(), null));
        mvc.perform(get("/api/v1/facilities")
                        .param("cursor", "ALPHA:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                        .param("limit", "25")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());
        verify(service).list("ALPHA:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "25");
    }
}
