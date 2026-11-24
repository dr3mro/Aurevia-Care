package com.aurevia.staff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
import com.aurevia.web.StaffController;
import com.aurevia.web.TenantContextInterceptor;

/** ARC-03 HTTP adapter for the staff list: authentication, authorization and error mapping. */
class StaffListControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private StaffService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new StaffController(service))
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
        mvc.perform(get("/api/v1/staff").header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void forbiddenRoleReturns403() throws Exception {
        when(service.list(isNull(), isNull(), isNull()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "missing action permission"));
        mvc.perform(get("/api/v1/staff")
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void badLimitReturns422() throws Exception {
        when(service.list(isNull(), eq("999"), isNull()))
                .thenThrow(new ApiException(422, "INVALID_LIMIT", "limit must be 1-200"));
        mvc.perform(get("/api/v1/staff").param("limit", "999")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test
    void validRequestReturnsPage() throws Exception {
        when(service.list(isNull(), isNull(), isNull())).thenReturn(
                new StaffPage(List.of(new StaffMember("alpha-a", "Alpha", "active", List.of("reception"))),
                        "alpha-a:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        mvc.perform(get("/api/v1/staff")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].subject").value("alpha-a"))
                .andExpect(jsonPath("$.items[0].roles[0]").value("reception"))
                .andExpect(jsonPath("$.nextCursor").value("alpha-a:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }

    @Test
    void cursorAndStatusArePassedThrough() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(new StaffPage(List.of(), null));
        mvc.perform(get("/api/v1/staff")
                        .param("cursor", "alpha-a:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                        .param("limit", "25")
                        .param("status", "deactivated")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).list(
                "alpha-a:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "25", "deactivated");
    }
}
