package com.aurevia.staffmsg.realtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * HTTP adapter acceptance: both realtime routes reach the injected service and
 * domain failures become machine-readable statuses. Synthetic headers only.
 */
class RealtimeControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "nurse-a";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String BASE = "/api/v1/staff/instant";

    private RealtimeService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RealtimeService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new RealtimeController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder headers(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder.header("X-Synthetic-Subject", ACTOR).header("X-Active-Organization", ORG);
    }

    @Test
    void pollingRouteReturnsTheIdentifierOnlyPage() throws Exception {
        when(service.poll(FACILITY, "0", 100)).thenReturn(new EventPage(List.of(
                new RealtimeEvent("message-1", 1L, RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b")), 1L));

        mvc.perform(headers(get(BASE + "/events"))
                        .param("facilityId", FACILITY)
                        .param("cursor", "0")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].id").value("message-1"))
                .andExpect(jsonPath("$.events[0].sequence").value(1))
                .andExpect(jsonPath("$.nextCursor").value(1));
        verify(service).poll(FACILITY, "0", 100);
    }

    @Test
    void streamRouteStartsAnAsyncSseResponseWithTheLastEventId() throws Exception {
        when(service.stream(FACILITY, "4")).thenReturn(new SseEmitter(60_000L));

        mvc.perform(headers(get(BASE + "/events/stream"))
                        .param("facilityId", FACILITY)
                        .header("Last-Event-ID", "4"))
                .andExpect(request().asyncStarted());
        verify(service).stream(FACILITY, "4");
    }

    @Test
    void streamRoutePropagatesAnAuthorizationDenial() throws Exception {
        when(service.stream(FACILITY, "0")).thenThrow(new ApiException(403, "FORBIDDEN", "no active staff role"));

        mvc.perform(headers(get(BASE + "/events/stream"))
                        .param("facilityId", FACILITY)
                        .header("Last-Event-ID", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void pollingRoutePropagatesAnInvalidCursor() throws Exception {
        when(service.poll(FACILITY, "-1", 100)).thenThrow(new ApiException(422, "INVALID_INPUT", "bad cursor"));

        mvc.perform(headers(get(BASE + "/events"))
                        .param("facilityId", FACILITY)
                        .param("cursor", "-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verify(service).poll(FACILITY, "-1", 100);
    }
}
