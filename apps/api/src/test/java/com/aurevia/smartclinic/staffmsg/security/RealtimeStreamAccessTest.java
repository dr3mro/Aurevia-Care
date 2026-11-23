package com.aurevia.smartclinic.staffmsg.security;

import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.FACILITY_A;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.FACILITY_B;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.ORG_A;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.OUTSIDER;
import static com.aurevia.smartclinic.staffmsg.security.StaffMessagingSecurityFixture.SENDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aurevia.smartclinic.staffmsg.instant.InstantConversationStore;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.staffmsg.realtime.MessageEventHub;
import com.aurevia.smartclinic.staffmsg.realtime.RealtimeController;
import com.aurevia.smartclinic.staffmsg.realtime.RealtimeEventType;
import com.aurevia.smartclinic.staffmsg.realtime.RealtimeService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Realtime stream and poll authorization for identifier-only conversation events
 * (MSG-07, ARC-07).
 *
 * <p>Both paths are exercised: a live append after membership revocation closes
 * the emitter with no delivery, and a replay from an old Last-Event-ID after
 * removal emits nothing. An emitter captured through the package-private factory
 * seam makes the no-delivery assertion deterministic rather than timing based.</p>
 */
class RealtimeStreamAccessTest {

    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    private MockMvc mvc;
    private MessageEventHub hub;
    private InstantConversationStore store;
    private AtomicBoolean active;
    private AtomicBoolean inScope;
    private AtomicBoolean visible;

    /** Wire the real controller and service over a mutable synthetic authorization store. */
    @BeforeEach
    void setUp() {
        active = new AtomicBoolean(true);
        inScope = new AtomicBoolean(true);
        visible = new AtomicBoolean(true);
        store = mock(InstantConversationStore.class);
        when(store.activeStaff(anyString(), anyString())).thenAnswer(call -> active.get());
        when(store.facilityWithinScope(anyString(), anyString(), anyString()))
                .thenAnswer(call -> inScope.get());
        when(store.conversationsForFacility(anyString(), anyString(), anyString()))
                .thenAnswer(call -> visible.get() ? List.of(conversation()) : List.of());
        hub = new MessageEventHub();
        RealtimeService service = StaffMessagingSecurityFixture.serviceWithEmitter(
                store, hub, mock(SseEmitter.class));
        mvc = StaffMessagingSecurityFixture.mvc(new RealtimeController(service));
    }

    /** Clear the thread-local used by both servlet and direct service calls. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static StaffConversation conversation() {
        return new StaffConversation(ORG_A, FACILITY_A, CONVERSATION, ConversationKind.DIRECT,
                null, SENDER, Instant.parse("2026-11-20T10:00:00Z"));
    }

    private static void bind() {
        TenantContext.set(new TenantContext(ORG_A, SENDER, TenantContext.PURPOSE, "realtime-security"));
    }

    @Test
    void unauthenticatedPollAndStreamAreRejected() throws Exception {
        mvc.perform(get("/api/v1/staff/instant/events").param("facilityId", FACILITY_A))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/staff/instant/events/stream").param("facilityId", FACILITY_A))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedOrganizationIsRejectedBeforeAuthorization() throws Exception {
        mvc.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/events").param("facilityId", FACILITY_A),
                        "not-a-uuid", SENDER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_SELECTION"));
    }

    @Test
    void ghostStaffIsForbiddenOnPollAndStream() throws Exception {
        active.set(false);
        mvc.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/events").param("facilityId", FACILITY_A), ORG_A, SENDER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/events/stream").param("facilityId", FACILITY_A), ORG_A, SENDER))
                .andExpect(status().isForbidden());
    }

    @Test
    void foreignFacilityIsForbiddenOnPollAndStream() throws Exception {
        inScope.set(false);
        mvc.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/events").param("facilityId", FACILITY_B), ORG_A, OUTSIDER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(StaffMessagingSecurityFixture.headers(
                        get("/api/v1/staff/instant/events/stream").param("facilityId", FACILITY_B), ORG_A, OUTSIDER))
                .andExpect(status().isForbidden());
    }

    @Test
    void removedActiveStaffClosesTheLiveStreamWithoutDelivery() throws Exception {
        bind();
        SseEmitter emitter = mock(SseEmitter.class);
        RealtimeService bound = StaffMessagingSecurityFixture.serviceWithEmitter(
                store, hub, emitter);
        bound.stream(FACILITY_A, "0");
        active.set(false);

        hub.append("message-after-removal", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");

        verify(emitter).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void lostFacilityScopeClosesTheLiveStreamWithoutDelivery() throws Exception {
        bind();
        SseEmitter emitter = mock(SseEmitter.class);
        RealtimeService bound = StaffMessagingSecurityFixture.serviceWithEmitter(
                store, hub, emitter);
        bound.stream(FACILITY_A, "0");
        inScope.set(false);

        hub.append("message-after-scope-loss", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");

        verify(emitter).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void losingAllVisibleConversationsClosesTheLiveStreamWithoutDelivery() throws Exception {
        bind();
        SseEmitter emitter = mock(SseEmitter.class);
        RealtimeService bound = StaffMessagingSecurityFixture.serviceWithEmitter(store, hub, emitter);
        bound.stream(FACILITY_A, "0");
        visible.set(false);

        hub.append("message-after-visibility-loss", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");

        verify(emitter).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void replayFromAnOldEventIdLeaksNothingAfterRemoval() throws Exception {
        bind();
        hub.append("message-before-removal", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");
        assertEquals(1, hub.after(0L).size(), "the event exists in the replay buffer");
        visible.set(false);

        SseEmitter replayEmitter = mock(SseEmitter.class);
        RealtimeService replayService = StaffMessagingSecurityFixture.serviceWithEmitter(
                store, hub, replayEmitter);
        replayService.stream(FACILITY_A, "0");

        verify(replayEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void visibleConversationStillReceivesEventsNegativeControl() throws Exception {
        bind();
        SseEmitter emitter = mock(SseEmitter.class);
        RealtimeService bound = StaffMessagingSecurityFixture.serviceWithEmitter(
                store, hub, emitter);
        bound.stream(FACILITY_A, "0");

        hub.append("message-while-visible", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");

        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
