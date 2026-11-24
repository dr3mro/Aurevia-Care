package com.aurevia.staffmsg.realtime;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/**
 * Thread-safe broker for identifier-only conversation events (ARC-07).
 *
 * <p>The hub is a process-local live-delivery and bounded-replay mechanism, not
 * a system of record: conversation messages remain in the existing durable
 * store. {@link com.aurevia.staffmsg.realtime.MessageEventHub#append}
 * assigns a monotonic stream sequence under the same
 * monitor as {@link com.aurevia.staffmsg.realtime.MessageEventHub#subscribe},
 * so a client that registers and replays from a cursor cannot miss or duplicate
 * an event appended during the hand-off.
 * Subscriber callbacks run on the appending thread; a failing subscriber is
 * closed so one broken emitter cannot stop fan-out to the others.</p>
 */
@Component
public final class MessageEventHub {

    /** Maximum number of recent events retained for replay after a drop. */
    static final int MAX_HISTORY = 512;

    private final AtomicLong sequence = new AtomicLong();
    private final List<RealtimeEvent> history = new CopyOnWriteArrayList<>();
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Append one event, allocate its sequence and deliver it to current subscribers.
     *
     * @param id             stable identifier of the changed message or cursor
     * @param type           what changed
     * @param conversationId owning conversation identifier
     * @param subject        acting staff subject
     * @return the stored event with its allocated sequence
     */
    public synchronized RealtimeEvent append(String id, RealtimeEventType type,
                                             String conversationId, String subject) {
        RealtimeEvent event = new RealtimeEvent(id, sequence.incrementAndGet(), type, conversationId, subject);
        history.add(event);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException failure) {
                close(subscriber);
            }
        }
        return event;
    }

    /**
     * Register a live subscriber and return the replay batch atomically.
     *
     * <p>Registration and the replay snapshot share the append monitor, so an
     * event is either in the returned batch or delivered live, never both.</p>
     *
     * @param cursor     exclusive lower sequence bound
     * @param subscriber callback to receive later events
     * @return retained events newer than the cursor, in sequence order
     */
    public List<RealtimeEvent> subscribe(long cursor, Subscriber subscriber) {
        List<RealtimeEvent> replay = new java.util.ArrayList<>();
        subscribe(cursor, subscriber, replay::add);
        return List.copyOf(replay);
    }

    /**
     * Register a subscriber and deliver its replay batch before releasing the
     * monitor, so a concurrent append cannot deliver a newer event first.
     *
     * @param cursor     exclusive lower sequence bound
     * @param subscriber callback to receive later events
     * @param replay     sink for the retained events, called in sequence order
     */
    public synchronized void subscribe(long cursor, Subscriber subscriber, Consumer<RealtimeEvent> replay) {
        subscribers.add(subscriber);
        for (RealtimeEvent event : after(cursor)) {
            replay.accept(event);
        }
    }

    /**
     * Capture a replay batch and the high-water sequence under one monitor.
     *
     * @param cursor exclusive lower sequence bound
     * @return the matching events and the sequence at the instant of the snapshot
     */
    public synchronized Snapshot snapshotAfter(long cursor) {
        return new Snapshot(after(cursor), sequence.get());
    }

    /**
     * Remove a subscriber and invoke its close hook.
     *
     * @param subscriber subscriber to revoke
     */
    public void close(Subscriber subscriber) {
        subscribers.remove(subscriber);
        subscriber.onClose();
    }

    /**
     * Return retained events newer than a cursor, in sequence order.
     *
     * @param cursor exclusive lower sequence bound
     * @return immutable ordered list, empty when nothing is retained after the cursor
     */
    public List<RealtimeEvent> after(long cursor) {
        return history.stream()
                .filter(event -> event.sequence() > cursor)
                .sorted(Comparator.comparingLong(RealtimeEvent::sequence))
                .toList();
    }

    /**
     * Return the highest sequence assigned so far, or zero on a fresh process.
     *
     * @return stream high-water mark
     */
    public long currentSequence() {
        return sequence.get();
    }

    /**
     * Consistent view of the replay buffer and the stream high-water mark.
     *
     * @param events   retained events newer than the requested cursor
     * @param highWater sequence assigned at the instant of the snapshot
     * @return the immutable snapshot
     */
    public record Snapshot(List<RealtimeEvent> events, long highWater) {

        /**
         * Freeze the event list so the snapshot cannot be changed by a caller.
         */
        public Snapshot {
            events = List.copyOf(events);
        }

        /**
         * Return the immutable event list.
         *
         * @return the snapshot events
         */
        @Override
        public List<RealtimeEvent> events() {
            return List.copyOf(events);
        }
    }

    /**
     * Callback installed by a stream subscriber. Every method may be called on
     * the thread that appended the event, so implementations must not rely on a
     * request-scoped thread-local being already bound.
     */
    public interface Subscriber {

        /**
         * Deliver one live event.
         *
         * @param event the appended event
         */
        void accept(RealtimeEvent event);

        /**
         * Release resources held for this subscription, such as completing its emitter.
         */
        void onClose();
    }
}
