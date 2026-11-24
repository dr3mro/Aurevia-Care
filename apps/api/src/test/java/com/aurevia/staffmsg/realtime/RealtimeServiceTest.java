package com.aurevia.staffmsg.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.instant.InstantConversationStore;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.tenant.TenantContext;

/**
 * Unit acceptance for the realtime service: authorization, gap replay, live
 * delivery, revocation and the identifier-only polling projection. All fixtures
 * are synthetic identifiers; the text sentinel is never placed in an event.
 */
class RealtimeServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ACTOR = "nurse-a";
    private static final String PEER = "nurse-b";
    private static final String OTHER_SUBJECT = "nurse-c";
    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OTHER_CONVERSATION = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String SENTINEL_TEXT = "SYNTHETIC_SENTINEL_NO_PATIENT_TEXT_9f3a";

    private InstantConversationStore store;
    private MessageEventHub hub;
    private SseEmitter emitter;
    private RealtimeService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, ACTOR, "STAFF_MESSAGING", "correlation-1"));
        store = mock(InstantConversationStore.class);
        when(store.activeStaff(anyString(), anyString())).thenReturn(true);
        when(store.facilityWithinScope(anyString(), anyString(), anyString())).thenReturn(true);
        when(store.conversationsForFacility(anyString(), anyString(), anyString()))
                .thenReturn(List.of(conversation(CONVERSATION)));
        hub = new MessageEventHub();
        emitter = mock(SseEmitter.class);
        service = new RealtimeService(store, hub, () -> emitter);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static StaffConversation conversation(String id) {
        return new StaffConversation(ORG, FACILITY, id, null, null, ACTOR, null);
    }

    private RealtimeEvent append(String id, String conversationId, String subject) {
        return hub.append(id, RealtimeEventType.MESSAGE, conversationId, subject);
    }

    private static List<RealtimeEvent> sentEvents(SseEmitter target) throws Exception {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(target, atLeast(0)).send(captor.capture());
        List<RealtimeEvent> events = new ArrayList<>();
        for (SseEmitter.SseEventBuilder builder : captor.getAllValues()) {
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                if (part.getData() instanceof RealtimeEvent event) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    private long failure(String facilityId, String cursor) {
        return assertThrows(ApiException.class, () -> service.poll(facilityId, cursor, 10)).status();
    }

    @Test
    void pollReturnsIdentifierOnlyEventsAndTheHighWaterCursor() {
        append("message-1", CONVERSATION, PEER);
        hub.append("read-2", RealtimeEventType.READ, CONVERSATION, ACTOR);
        append("message-3", CONVERSATION, PEER);

        EventPage page = service.poll(FACILITY, "1", 100);

        assertEquals(List.of(2L, 3L), page.events().stream().map(RealtimeEvent::sequence).toList());
        assertEquals(List.of("read-2", "message-3"), page.events().stream().map(RealtimeEvent::id).toList());
        assertEquals(3L, page.nextCursor());
        String json = page.events().toString();
        assertFalse(json.contains("body"));
        assertFalse(json.contains(SENTINEL_TEXT));
        assertEquals(RealtimeEventType.READ, page.events().get(0).type());
    }

    @Test
    void pollFiltersEventsFromConversationsTheCallerCannotSeeButStillAdvancesTheCursor() {
        append("message-1", CONVERSATION, PEER);
        append("message-2", OTHER_CONVERSATION, PEER);
        when(store.conversationsForFacility(ORG, FACILITY, ACTOR)).thenReturn(List.of(conversation(CONVERSATION)));

        EventPage page = service.poll(FACILITY, "0", 100);

        assertEquals(List.of("message-1"), page.events().stream().map(RealtimeEvent::id).toList());
        assertEquals(2L, page.nextCursor(), "the cursor must not stall on invisible events");
    }

    @Test
    void pollWithEveryEventFilteredOutStillAdvancesTheCursor() {
        append("message-1", OTHER_CONVERSATION, PEER);
        when(store.conversationsForFacility(ORG, FACILITY, ACTOR)).thenReturn(List.of());

        EventPage page = service.poll(FACILITY, "0", 100);

        assertTrue(page.events().isEmpty());
        assertEquals(1L, page.nextCursor());
    }

    @Test
    void pollWithACursorAboveTheHighWaterMarkAfterRestartReturnsEmptyAndALoweredCursor() {
        append("message-1", CONVERSATION, PEER);

        EventPage page = service.poll(FACILITY, "1000", 100);

        assertTrue(page.events().isEmpty());
        assertEquals(1L, page.nextCursor(), "a client whose cursor outlived the process must resync");
    }

    @Test
    void pollWithNoEventsReturnsAnEmptyPageAndTheRequestedCursor() {
        EventPage page = service.poll(FACILITY, "0", 100);
        assertTrue(page.events().isEmpty());
        assertEquals(0L, page.nextCursor());
    }

    @Test
    void pollAppliesTheBoundedPageSize() {
        for (int i = 1; i <= 5; i++) {
            append("message-" + i, CONVERSATION, PEER);
        }

        EventPage page = service.poll(FACILITY, "0", 2);

        assertEquals(2, page.events().size());
        assertEquals(5L, page.nextCursor());
    }

    @Test
    void pollRejectsABlankFacility() {
        assertEquals(422, failure("", "0"));
        assertEquals(422, failure(null, "0"));
    }

    @Test
    void pollDeniesWhenNoTenantContextIsBound() {
        TenantContext.clear();
        ApiException error = assertThrows(ApiException.class, () -> service.poll(FACILITY, "0", 10));
        assertEquals(403, error.status());
    }

    @Test
    void pollDeniesAnInactiveStaffMember() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.poll(FACILITY, "0", 10)).status());
        verify(store, never()).conversationsForFacility(anyString(), anyString(), anyString());
    }

    @Test
    void pollDeniesASubjectOutsideTheFacility() {
        when(store.facilityWithinScope(ORG, ACTOR, FACILITY)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.poll(FACILITY, "0", 10)).status());
    }

    @Test
    void pollRejectsBlankNegativeAndNonNumericCursors() {
        assertEquals(422, failure(FACILITY, ""));
        assertEquals(422, failure(FACILITY, "   "));
        assertEquals(422, failure(FACILITY, "-1"));
        assertEquals(422, failure(FACILITY, "not-a-number"));
    }

    @Test
    void pollWithoutACursorReplaysFromTheStart() {
        append("message-1", CONVERSATION, PEER);
        EventPage page = service.poll(FACILITY, null, 100);
        assertEquals(List.of("message-1"), page.events().stream().map(RealtimeEvent::id).toList());
    }

    @Test
    void streamReplaysMissedEventsThenDeliversLiveOnesWithoutDuplicates() throws Exception {
        append("message-1", CONVERSATION, PEER);
        append("message-2", CONVERSATION, PEER);

        service.subscribe(emitter, FACILITY, "1");
        assertEquals(List.of("message-2"), sentEvents(emitter).stream().map(RealtimeEvent::id).toList());

        append("read-3", CONVERSATION, ACTOR);
        assertEquals(List.of("message-2", "read-3"),
                sentEvents(emitter).stream().map(RealtimeEvent::id).toList());
        verify(emitter).onCompletion(any(Runnable.class));
        verify(emitter).onTimeout(any(Runnable.class));
        verify(emitter).onError(any());
    }

    @Test
    void theProductionConstructorCreatesABoundedEmitter() {
        RealtimeService production = new RealtimeService(store, hub);
        SseEmitter result = production.stream(FACILITY, "0");
        assertEquals(RealtimeService.SSE_TIMEOUT_MS, result.getTimeout());
    }

    @Test
    void streamDoesNotReplayEventsFromAnInvisibleConversation() throws Exception {
        append("message-1", OTHER_CONVERSATION, PEER);
        append("message-2", CONVERSATION, PEER);

        service.subscribe(emitter, FACILITY, "0");

        assertEquals(List.of("message-2"), sentEvents(emitter).stream().map(RealtimeEvent::id).toList());
    }

    @Test
    void streamWithoutALastEventIdReplaysFromTheStart() throws Exception {
        append("message-1", CONVERSATION, PEER);
        service.subscribe(emitter, FACILITY, null);
        assertEquals(List.of("message-1"), sentEvents(emitter).stream().map(RealtimeEvent::id).toList());
    }

    @Test
    void streamRejectsBlankNegativeAndNonNumericLastEventIds() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.subscribe(emitter, FACILITY, "")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.subscribe(emitter, FACILITY, "-3")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.subscribe(emitter, FACILITY, "abc")).status());
    }

    @Test
    void streamDeniesAnInactiveStaffMember() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.subscribe(emitter, FACILITY, "0")).status());
    }

    @Test
    void streamRebindsTheSnapshotAndRestoresTheAppendingThreadsContext() throws Exception {
        service.subscribe(emitter, FACILITY, "0");
        TenantContext other = new TenantContext(OTHER_ORG, OTHER_SUBJECT, "OTHER", "correlation-2");
        TenantContext.set(other);
        AtomicReference<TenantContext> duringSend = new AtomicReference<>();
        doAnswer(invocation -> {
            duringSend.set(TenantContext.current());
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        append("message-1", CONVERSATION, PEER);

        assertEquals(new TenantContext(ORG, ACTOR, "STAFF_MESSAGING", "correlation-1"), duringSend.get());
        assertEquals(other, TenantContext.current(), "the appending request must keep its own tenant");
    }

    @Test
    void streamClearsTheContextWhenTheCallbackThreadHadNone() throws Exception {
        service.subscribe(emitter, FACILITY, "0");
        TenantContext.clear();
        AtomicReference<TenantContext> duringSend = new AtomicReference<>();
        doAnswer(invocation -> {
            duringSend.set(TenantContext.current());
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        append("message-1", CONVERSATION, PEER);

        assertEquals(ACTOR, duringSend.get().subject());
        assertEquals(null, TenantContext.current(), "the rebound snapshot must not leak to the next request");
    }

    @Test
    void aFacilityScopeRevocationClosesTheStreamOnTheNextEvent() throws Exception {
        service.subscribe(emitter, FACILITY, "0");
        when(store.facilityWithinScope(ORG, ACTOR, FACILITY)).thenReturn(false);

        append("message-1", CONVERSATION, PEER);

        verify(emitter, times(1)).complete();
        assertTrue(sentEvents(emitter).isEmpty());
    }

    @Test
    void aLiveSendFailureRemovesTheSubscriber() throws Exception {
        service.subscribe(emitter, FACILITY, "0");
        doThrow(new IOException("client gone")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        append("message-1", CONVERSATION, PEER);

        verify(emitter, times(1)).completeWithError(any(IOException.class));
        verify(emitter, times(1)).complete();
        append("message-2", CONVERSATION, PEER);
        verify(emitter, times(1)).completeWithError(any(IOException.class));
    }

    @Test
    void authorizationReadsRunUnderTheCapturedTenantNotTheAppendingThreads() throws Exception {
        service.subscribe(emitter, FACILITY, "0");
        AtomicReference<TenantContext> duringCheck = new AtomicReference<>();
        doAnswer(invocation -> {
            duringCheck.set(TenantContext.current());
            return true;
        }).when(store).activeStaff(ORG, ACTOR);
        TenantContext other = new TenantContext(OTHER_ORG, OTHER_SUBJECT, "OTHER", "correlation-2");
        TenantContext.set(other);

        append("message-1", CONVERSATION, PEER);

        assertEquals(new TenantContext(ORG, ACTOR, "STAFF_MESSAGING", "correlation-1"), duringCheck.get());
        assertEquals(other, TenantContext.current());
        verify(emitter, never()).complete();
    }

    @Test
    void authorizationReadsUseTheCapturedTenantWhenTheAppendingThreadHasNone() throws Exception {
        service.subscribe(emitter, FACILITY, "0");
        AtomicReference<TenantContext> duringCheck = new AtomicReference<>();
        doAnswer(invocation -> {
            duringCheck.set(TenantContext.current());
            return true;
        }).when(store).activeStaff(ORG, ACTOR);
        TenantContext.clear();

        append("message-1", CONVERSATION, PEER);

        assertEquals(ACTOR, duringCheck.get().subject());
        assertEquals(null, TenantContext.current());
        verify(emitter, never()).complete();
    }

    @Test
    void aSendFailureCompletesTheEmitterWithTheError() throws Exception {
        append("message-1", CONVERSATION, PEER);
        IOException failure = new IOException("client gone");
        doThrow(failure).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        service.subscribe(emitter, FACILITY, "0");

        verify(emitter).completeWithError(failure);
        verify(emitter).complete();
        assertEquals(ACTOR, TenantContext.current().subject());
    }

    @Test
    void revokeCompletesOnlyTheRevokedParticipantsStream() throws Exception {
        SseEmitter revoked = mock(SseEmitter.class);
        SseEmitter remaining = mock(SseEmitter.class);
        service.subscribe(revoked, FACILITY, "0");
        TenantContext peer = new TenantContext(ORG, PEER, "STAFF_MESSAGING", "correlation-peer");
        TenantContext.set(peer);
        when(store.conversationsForFacility(ORG, FACILITY, PEER)).thenReturn(List.of(conversation(CONVERSATION)));
        service.subscribe(remaining, FACILITY, "0");
        TenantContext.set(new TenantContext(ORG, ACTOR, "STAFF_MESSAGING", "correlation-1"));
        when(store.activeStaff(ORG, ACTOR)).thenReturn(false);

        append("message-1", CONVERSATION, PEER);

        verify(revoked, times(1)).complete();
        verify(remaining, never()).complete();
        assertTrue(sentEvents(remaining).stream().anyMatch(event -> event.id().equals("message-1")));
        assertTrue(sentEvents(revoked).isEmpty(), "a revoked subscriber must not receive later events");
    }

    @Test
    void aParticipantWithNoRemainingMembershipIsClosedOnTheNextEvent() throws Exception {
        service.subscribe(emitter, FACILITY, "0");
        when(store.conversationsForFacility(ORG, FACILITY, ACTOR)).thenReturn(List.of());

        append("message-1", CONVERSATION, PEER);

        verify(emitter, times(1)).complete();
        assertTrue(sentEvents(emitter).isEmpty());
    }

    @Test
    void aDifferentTenantsSubscriberReceivesNoIdentifiersAndIsClosed() throws Exception {
        SseEmitter foreign = mock(SseEmitter.class);
        TenantContext original = TenantContext.current();
        TenantContext.set(new TenantContext(OTHER_ORG, OTHER_SUBJECT, "OTHER", "correlation-2"));
        when(store.conversationsForFacility(OTHER_ORG, FACILITY, OTHER_SUBJECT)).thenReturn(List.of());
        service.subscribe(foreign, FACILITY, "0");
        when(store.activeStaff(OTHER_ORG, OTHER_SUBJECT)).thenReturn(false);
        TenantContext.set(original);

        append("message-1", CONVERSATION, PEER);

        assertTrue(sentEvents(foreign).isEmpty(), "identifiers must not cross the tenant boundary");
        verify(foreign, times(1)).complete();
    }

    @Test
    void completionTimeoutAndErrorCallbacksCloseTheSubscriberOnlyOnce() {
        AtomicReference<Runnable> completion = new AtomicReference<>();
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        doAnswer(invocation -> {
            completion.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onCompletion(any(Runnable.class));
        doAnswer(invocation -> {
            timeout.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onTimeout(any(Runnable.class));
        doAnswer(invocation -> {
            error.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onError(any());

        service.subscribe(emitter, FACILITY, "0");
        completion.get().run();
        timeout.get().run();
        error.get().accept(new IllegalStateException("disconnected"));

        verify(emitter, times(1)).complete();
    }

    @Test
    void streamDoesNotEmitEventsFromAConversationTheSubjectLeft() throws Exception {
        when(store.conversationsForFacility(ORG, FACILITY, ACTOR)).thenReturn(List.of(conversation(CONVERSATION)));
        service.subscribe(emitter, FACILITY, "0");

        append("message-1", OTHER_CONVERSATION, PEER);
        append("message-2", CONVERSATION, PEER);

        assertEquals(List.of("message-2"), sentEvents(emitter).stream().map(RealtimeEvent::id).toList());
    }
}
