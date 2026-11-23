package com.aurevia.smartclinic.staffmsg.realtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.staffmsg.instant.InstantConversationStore;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

/**
 * HTTP-level acceptance for realtime delivery through the real controller,
 * service and hub. The store is a deterministic synthetic mock; no PostgreSQL
 * or new table is needed for this slice.
 */
class RealtimeIntegrationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "nurse-a";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String BASE = "/api/v1/staff/instant";

    private MessageEventHub hub;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        InstantConversationStore store = mock(InstantConversationStore.class);
        when(store.activeStaff(anyString(), anyString())).thenReturn(true);
        when(store.facilityWithinScope(anyString(), anyString(), anyString())).thenReturn(true);
        when(store.conversationsForFacility(anyString(), anyString(), anyString()))
                .thenReturn(List.of(new StaffConversation(ORG, FACILITY, CONVERSATION, null, null, ACTOR, null)));
        hub = new MessageEventHub();
        AtomicReference<SseEmitter> captured = new AtomicReference<>();
        RealtimeService service = new RealtimeService(store, hub, () -> {
            SseEmitter emitter = new SseEmitter(60_000L);
            captured.set(emitter);
            return emitter;
        });
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new RealtimeController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        capturedEmitter = captured;
    }

    private AtomicReference<SseEmitter> capturedEmitter;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request) {
        return request.header("X-Synthetic-Subject", ACTOR).header("X-Active-Organization", ORG);
    }

    @Test
    void streamAndPollingAgreeOnTheIdentifierSetAndNeverExposeMessageText() throws Exception {
        hub.append("message-1", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");
        hub.append("read-2", RealtimeEventType.READ, CONVERSATION, ACTOR);
        hub.append("message-3", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");

        MvcResult poll = mvc.perform(headers(get(BASE + "/events"))
                        .param("facilityId", FACILITY)
                        .param("cursor", "0"))
                .andExpect(status().isOk())
                .andReturn();
        String pollBody = poll.getResponse().getContentAsString();
        assertTrue(pollBody.contains("message-1"));
        assertTrue(pollBody.contains("read-2"));
        assertTrue(pollBody.contains("message-3"));
        assertFalse(pollBody.contains("body"), "polling must expose identifiers only");

        MvcResult async = mvc.perform(headers(get(BASE + "/events/stream"))
                        .param("facilityId", FACILITY)
                        .header("Last-Event-ID", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();
        hub.append("message-4", RealtimeEventType.MESSAGE, CONVERSATION, ACTOR);
        capturedEmitter.get().complete();

        MvcResult stream = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();
        String streamBody = stream.getResponse().getContentAsString();
        assertTrue(streamBody.contains("read-2"), "the missed event is replayed");
        assertTrue(streamBody.contains("message-4"), "the live event is delivered");
        assertFalse(streamBody.contains("message-1"), "already-seen events are not replayed");
        assertTrue(streamBody.contains("id:2"), "the SSE id carries the sequence for Last-Event-ID");
        assertTrue(streamBody.contains("event:read"), "the SSE event name carries the stable wire type");
        assertTrue(streamBody.contains("event:message"));
        assertFalse(streamBody.contains("body"), "the stream must expose identifiers only");
    }
}
