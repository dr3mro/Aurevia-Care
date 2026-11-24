package com.aurevia.staffmsg.instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.aurevia.staffmsg.instant.InstantMessagingContract.ConversationView;
import com.aurevia.staffmsg.instant.InstantMessagingContract.MessageView;
import com.aurevia.staffmsg.instant.InstantMessagingContract.UnreadView;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** HTTP entry point for instant staff messaging: every route reaches the domain service. */
class InstantMessagingControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "nurse-a";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String BASE = "/api/v1/staff/instant";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    private InstantMessagingService service;
    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        service = mock(InstantMessagingService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new InstantMessagingController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ConversationView conversation() {
        return new ConversationView(CONVERSATION, FACILITY, "direct", "topic", 2L, 1L);
    }

    private static MessageView message() {
        return new MessageView("message-2", 2L, ACTOR, "owner", "body", AT);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder headers(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-Synthetic-Subject", ACTOR).header("X-Active-Organization", ORG);
    }

    @Test
    void opensADirectConversation() throws Exception {
        when(service.openDirect(any())).thenReturn(conversation());
        mvc.perform(headers(post(BASE + "/direct"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new InstantMessagingContract.OpenDirectRequest(FACILITY, "nurse-b"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONVERSATION))
                .andExpect(jsonPath("$.unreadCount").value(1));
        verify(service).openDirect(any());
    }

    @Test
    void createsAGroupConversation() throws Exception {
        ConversationView group = new ConversationView(CONVERSATION, FACILITY, "group", "topic", 0L, 0L);
        when(service.createGroup(any())).thenReturn(group);
        mvc.perform(headers(post(BASE + "/groups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new InstantMessagingContract.CreateGroupRequest(FACILITY, "topic", List.of("nurse-b")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("group"));
        verify(service).createGroup(any());
    }

    @Test
    void addsAParticipant() throws Exception {
        when(service.addParticipant(any())).thenReturn(conversation());
        mvc.perform(headers(post(BASE + "/participants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new InstantMessagingContract.AddParticipantRequest(FACILITY, CONVERSATION, "nurse-b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONVERSATION));
        verify(service).addParticipant(any());
    }

    @Test
    void removesAParticipant() throws Exception {
        when(service.removeParticipant(FACILITY, CONVERSATION, "nurse-b")).thenReturn(conversation());
        mvc.perform(headers(delete(BASE + "/{id}/participants/{subject}", CONVERSATION, "nurse-b"))
                        .param("facilityId", FACILITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONVERSATION));
        verify(service).removeParticipant(FACILITY, CONVERSATION, "nurse-b");
    }

    @Test
    void sendsWithTheIdempotencyHeader() throws Exception {
        when(service.send(any(), eq("key-1"))).thenReturn(message());
        mvc.perform(headers(post(BASE + "/{id}/messages", CONVERSATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-1")
                        .content(mapper.writeValueAsString(
                                new InstantMessagingContract.SendRequest(FACILITY, CONVERSATION, "body"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(2))
                .andExpect(jsonPath("$.body").value("body"));
        verify(service).send(any(), eq("key-1"));
    }

    @Test
    void readsTheThread() throws Exception {
        when(service.messages(FACILITY, CONVERSATION)).thenReturn(List.of(message()));
        mvc.perform(headers(get(BASE + "/{id}/messages", CONVERSATION)).param("facilityId", FACILITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sequence").value(2));
        verify(service).messages(FACILITY, CONVERSATION);
    }

    @Test
    void marksReadAndReturnsUnread() throws Exception {
        when(service.markRead(any())).thenReturn(new UnreadView(CONVERSATION, 0L));
        mvc.perform(headers(post(BASE + "/read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new InstantMessagingContract.MarkReadRequest(FACILITY, CONVERSATION, 2L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
        verify(service).markRead(any());
    }

    @Test
    void listsUnreadCounts() throws Exception {
        when(service.unreadCounts(FACILITY)).thenReturn(List.of(new UnreadView(CONVERSATION, 3L)));
        mvc.perform(headers(get(BASE + "/unread")).param("facilityId", FACILITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationId").value(CONVERSATION))
                .andExpect(jsonPath("$[0].unreadCount").value(3));
        verify(service).unreadCounts(FACILITY);
    }

    @Test
    void listsConversations() throws Exception {
        when(service.conversations(FACILITY)).thenReturn(List.of(conversation()));
        mvc.perform(headers(get(BASE + "/conversations")).param("facilityId", FACILITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CONVERSATION));
        verify(service).conversations(FACILITY);
    }

    @Test
    void aDomainDenialBecomesAResponseStatus() throws Exception {
        when(service.messages(anyString(), anyString()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "the conversation belongs to another facility"));
        mvc.perform(headers(get(BASE + "/{id}/messages", CONVERSATION)).param("facilityId", FACILITY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
