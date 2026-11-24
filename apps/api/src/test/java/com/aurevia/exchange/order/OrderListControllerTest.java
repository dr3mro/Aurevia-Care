package com.aurevia.exchange.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

/** ARC-03 HTTP adapter for the order list: authentication, authorization and error mapping. */
class OrderListControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant TIME_A = Instant.parse("2026-11-20T09:07:06Z");

    private OrderService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OrderService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
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
        mvc.perform(get("/api/v1/orders").header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void forbiddenRoleReturns403() throws Exception {
        when(service.list(isNull(), isNull(), isNull()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "missing exchange permission"));
        mvc.perform(get("/api/v1/orders")
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void badLimitReturns422() throws Exception {
        when(service.list(isNull(), eq("999"), isNull()))
                .thenThrow(new ApiException(422, "INVALID_LIMIT", "limit must be 1-200"));
        mvc.perform(get("/api/v1/orders").param("limit", "999")
                        .header("X-Synthetic-Subject", "clinician-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test
    void validRequestReturnsPage() throws Exception {
        when(service.list(isNull(), isNull(), isNull())).thenReturn(
                new OrderPage(List.of(new OrderView(ID_A, 1, "11111111-1111-1111-1111-111111111111",
                        "ENC-1", "CBC", "routine", "Synthetic question",
                        "22222222-2222-2222-2222-222222222222", "clinician-a", "dr-a", "lab-queue",
                        "55555555-5555-5555-5555-555555555555", "66666666-6666-6666-6666-666666666666",
                        "77777777-7777-7777-7777-777777777777", "88888888-8888-8888-8888-888888888888",
                        "99999999-9999-9999-9999-999999999999", TIME_A)), TIME_A + ":" + ID_A));
        mvc.perform(get("/api/v1/orders")
                        .header("X-Synthetic-Subject", "clinician-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ID_A))
                .andExpect(jsonPath("$.items[0].orderCode").value("CBC"))
                .andExpect(jsonPath("$.nextCursor").value(TIME_A + ":" + ID_A));
    }

    @Test
    void cursorLimitAndStatusArePassedThrough() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(new OrderPage(List.of(), null));
        mvc.perform(get("/api/v1/orders")
                        .param("cursor", TIME_A + ":" + ID_A)
                        .param("limit", "25")
                        .param("status", "ready")
                        .header("X-Synthetic-Subject", "clinician-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());
        verify(service).list(TIME_A + ":" + ID_A, "25", "ready");
    }
}
