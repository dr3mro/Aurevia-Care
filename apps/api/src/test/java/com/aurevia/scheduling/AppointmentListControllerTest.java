package com.aurevia.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import com.aurevia.web.AppointmentController;
import com.aurevia.web.TenantContextInterceptor;

/** ARC-03 HTTP adapter for the appointment list: authentication, authorization and error mapping. */
class AppointmentListControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant START = Instant.parse("2027-01-05T09:00:00Z");
    private static final String FROM = "2027-01-01T00:00:00Z";
    private static final String TO = "2027-01-31T00:00:00Z";

    private AppointmentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AppointmentService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new AppointmentController(service))
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
        mvc.perform(get("/api/v1/appointments")
                        .param("from", FROM).param("to", TO)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void forbiddenRoleReturns403() throws Exception {
        when(service.list(isNull(), isNull(), eq(FROM), eq(TO), isNull(), isNull()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "missing scheduling permission"));
        mvc.perform(get("/api/v1/appointments")
                        .param("from", FROM).param("to", TO)
                        .header("X-Synthetic-Subject", "auditor-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void badLimitReturns422() throws Exception {
        when(service.list(isNull(), eq("999"), eq(FROM), eq(TO), isNull(), isNull()))
                .thenThrow(new ApiException(422, "INVALID_LIMIT", "limit must be 1-200"));
        mvc.perform(get("/api/v1/appointments")
                        .param("from", FROM).param("to", TO).param("limit", "999")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test
    void validRequestReturnsPage() throws Exception {
        Appointment appointment = new Appointment(ID, 1, "patient-1", "MAIN", "DR-A", "ROOM-1",
                "NEW", START, START.plus(Duration.ofMinutes(30)), AppointmentState.BOOKED,
                null, null, null);
        when(service.list(isNull(), isNull(), eq(FROM), eq(TO), isNull(), isNull()))
                .thenReturn(new AppointmentPage(
                        List.of(new AppointmentView(appointment, List.of())), START + ":" + ID));
        mvc.perform(get("/api/v1/appointments")
                        .param("from", FROM).param("to", TO)
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].appointment.id").value(ID))
                .andExpect(jsonPath("$.items[0].appointment.state").value("booked"))
                .andExpect(jsonPath("$.nextCursor").value(START + ":" + ID));
    }

    @Test
    void allFiltersArePassedThrough() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AppointmentPage(List.of(), null));
        mvc.perform(get("/api/v1/appointments")
                        .param("cursor", START + ":" + ID)
                        .param("limit", "25")
                        .param("from", FROM)
                        .param("to", TO)
                        .param("resource", "DR-A")
                        .param("state", "booked")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());
        verify(service).list(START + ":" + ID, "25", FROM, TO, "DR-A", "booked");
    }
}
