package com.aurevia.authz.history;

import static org.mockito.ArgumentMatchers.anyString;
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
import com.aurevia.web.TenantContextInterceptor;

/** ARC-03 HTTP adapter for the IAM-02 membership history read endpoint. */
class RoleHistoryControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private RoleHistoryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RoleHistoryService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new RoleHistoryController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        com.aurevia.tenant.TenantContext.clear();
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(get("/api/v1/authz/role-history/employee-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void nonAdminReturns403() throws Exception {
        when(service.history(anyString()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "missing action permission"));
        mvc.perform(get("/api/v1/authz/role-history/employee-a")
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void historyHasNoWriteMappings() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/authz/role-history/employee-a")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/authz/role-history/employee-a")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void tenantAdminReadsTheHistory() throws Exception {
        RoleHistory.RoleChange change = new RoleHistory.RoleChange(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "employee-a", "reception", "GRANT", "admin-a",
                "2026-11-21 10:00:00+00", "2026-11-21 09:00:00+00", null);
        when(service.history("employee-a"))
                .thenReturn(new RoleHistory("employee-a", List.of(change)));
        mvc.perform(get("/api/v1/authz/role-history/employee-a")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("employee-a"))
                .andExpect(jsonPath("$.changes[0].roleKey").value("reception"))
                .andExpect(jsonPath("$.changes[0].changeKind").value("GRANT"))
                .andExpect(jsonPath("$.changes[0].actor").value("admin-a"))
                .andExpect(jsonPath("$.changes[0].effectiveFrom").value("2026-11-21 09:00:00+00"));
    }
}
