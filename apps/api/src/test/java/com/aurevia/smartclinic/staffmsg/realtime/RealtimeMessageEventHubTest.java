package com.aurevia.smartclinic.staffmsg.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit acceptance for the realtime event broker: bounded replay, gap resume and
 * subscriber lifecycle. Fixtures are synthetic identifiers with no clinical text.
 */
class RealtimeMessageEventHubTest {

    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OTHER_CONVERSATION = "dddddddd-dddd-dddd-dddd-dddddddddddd";

    private static final class Recorder implements MessageEventHub.Subscriber {
        private final List<RealtimeEvent> received = new ArrayList<>();
        private int closed;

        @Override
        public void accept(RealtimeEvent event) {
            received.add(event);
        }

        @Override
        public void onClose() {
            closed++;
        }
    }

    @Test
    void appendAssignsMonotonicSequencesAndAfterReplaysOnlyNewerEventsInOrder() {
        MessageEventHub hub = new MessageEventHub();
        RealtimeEvent first = hub.append("message-1", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-a");
        RealtimeEvent second = hub.append("message-2", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");
        RealtimeEvent third = hub.append("read-3", RealtimeEventType.READ, CONVERSATION, "nurse-a");

        assertEquals(1L, first.sequence());
        assertEquals(2L, second.sequence());
        assertEquals(3L, third.sequence());
        assertEquals(List.of(second, third), hub.after(1L));
        assertEquals(List.of(third), hub.after(2L));
        assertTrue(hub.after(3L).isEmpty());
        assertTrue(hub.after(99L).isEmpty());
        assertEquals(RealtimeEventType.READ, third.type());
    }

    @Test
    void subscribeReplaysMissedEventsThenDeliversLiveEventsWithoutGapOrDuplicate() {
        MessageEventHub hub = new MessageEventHub();
        hub.append("message-1", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-a");
        hub.append("message-2", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");
        Recorder recorder = new Recorder();

        List<RealtimeEvent> replay = hub.subscribe(0L, recorder);
        assertEquals(List.of(1L, 2L), replay.stream().map(RealtimeEvent::sequence).toList());
        assertTrue(recorder.received.isEmpty(), "replay is returned, not pushed live");

        RealtimeEvent live = hub.append("read-3", RealtimeEventType.READ, CONVERSATION, "nurse-a");
        assertEquals(List.of(live), recorder.received);
        assertTrue(replay.stream().noneMatch(event -> event.sequence() == 3L),
                "the live event must not also appear in the earlier replay batch");
    }

    @Test
    void aReconnectingSubscriberReceivesExactlyTheMissedGap() {
        MessageEventHub hub = new MessageEventHub();
        hub.append("message-1", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-a");
        hub.append("message-2", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");
        hub.append("message-3", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-a");
        hub.append("read-4", RealtimeEventType.READ, CONVERSATION, "nurse-b");

        List<RealtimeEvent> missed = hub.after(1L);
        assertEquals(List.of(2L, 3L, 4L), missed.stream().map(RealtimeEvent::sequence).toList());
        assertTrue(missed.stream().noneMatch(event -> event.sequence() == 1L));
    }

    @Test
    void historyIsBoundedAndAStaleCursorReturnsOnlyRetainedEvents() {
        MessageEventHub hub = new MessageEventHub();
        for (int i = 0; i < MessageEventHub.MAX_HISTORY + 3; i++) {
            hub.append("message-" + i, RealtimeEventType.MESSAGE, CONVERSATION, "nurse-a");
        }
        List<RealtimeEvent> retained = hub.after(0L);
        assertEquals(MessageEventHub.MAX_HISTORY, retained.size());
        assertEquals(4L, retained.get(0).sequence());
        assertEquals((long) MessageEventHub.MAX_HISTORY + 3L, retained.get(retained.size() - 1).sequence());
    }

    @Test
    void closeRemovesTheSubscriberAndInvokesItsCloseCallbackOnce() {
        MessageEventHub hub = new MessageEventHub();
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        hub.subscribe(0L, first);
        hub.subscribe(0L, second);

        hub.close(first);
        hub.append("message-1", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-a");

        assertEquals(1, first.closed);
        assertTrue(first.received.isEmpty());
        assertEquals(1, second.received.size());
        assertEquals(0, second.closed);
        hub.close(first);
        assertEquals(2, first.closed, "the hub delegates close; idempotency belongs to the subscriber");
    }

    @Test
    void appendWithNoSubscribersStillStoresEvents() {
        MessageEventHub hub = new MessageEventHub();
        RealtimeEvent event = hub.append("message-1", RealtimeEventType.MESSAGE,
                OTHER_CONVERSATION, "nurse-a");
        assertEquals(1L, event.sequence());
        assertEquals(List.of(event), hub.after(0L));
        assertFalse(hub.after(0L).isEmpty());
        assertEquals(1L, hub.currentSequence());
    }

    @Test
    void snapshotAfterCapturesEventsAndHighWaterTogether() {
        MessageEventHub hub = new MessageEventHub();
        hub.append("message-1", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-a");
        hub.append("message-2", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");

        MessageEventHub.Snapshot snapshot = hub.snapshotAfter(1L);

        assertEquals(List.of(2L), snapshot.events().stream().map(RealtimeEvent::sequence).toList());
        assertEquals(2L, snapshot.highWater());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.events().add(snapshot.events().get(0)));
    }

    @Test
    void aThrowingSubscriberCannotBreakFanOutAndIsClosed() {
        MessageEventHub hub = new MessageEventHub();
        Recorder healthy = new Recorder();
        int[] brokenCloses = {0};
        MessageEventHub.Subscriber broken = new MessageEventHub.Subscriber() {
            @Override
            public void accept(RealtimeEvent event) {
                throw new IllegalStateException("emitter already gone");
            }

            @Override
            public void onClose() {
                brokenCloses[0]++;
            }
        };
        hub.subscribe(0L, broken);
        hub.subscribe(0L, healthy);

        RealtimeEvent event = hub.append("message-1", RealtimeEventType.MESSAGE, CONVERSATION, "nurse-a");

        assertEquals(List.of(event), healthy.received);
        assertEquals(1, brokenCloses[0], "the failing subscriber is isolated and closed");
    }
}
