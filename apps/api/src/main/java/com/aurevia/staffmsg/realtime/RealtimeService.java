package com.aurevia.staffmsg.realtime;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.instant.InstantConversationStore;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.tenant.TenantContext;

/**
 * Authorization and projection for identifier-only conversation events.
 *
 * <p>Every read re-derives the caller from {@link com.aurevia.tenant.TenantContext},
 * re-checks active staff status and facility scope, and limits events to
 * conversations where the caller is still an active member. Former members see
 * no new identifiers. The context and facility are captured once at subscription
 * time and rebound around each emitter callback, because the servlet interceptor
 * clears the thread-local at async hand-off and notification may run on the
 * appending thread with its own tenant already bound.</p>
 */
@Service
public class RealtimeService {

    /** Bounded emitter lifetime; an idle stream is released rather than held forever. */
    static final long SSE_TIMEOUT_MS = 3_600_000L;
    private static final int MAX_LIMIT = 500;

    private final InstantConversationStore store;
    private final MessageEventHub hub;
    private final EmitterFactory emitters;

    /**
     * Create the service with its store, hub and the production emitter factory.
     *
     * @param store instant conversation store used only for authorization and visibility
     * @param hub   process-local event broker
     */
    @Autowired
    public RealtimeService(InstantConversationStore store, MessageEventHub hub) {
        this(store, hub, () -> new SseEmitter(SSE_TIMEOUT_MS));
    }

    RealtimeService(InstantConversationStore store, MessageEventHub hub, EmitterFactory emitters) {
        this.store = store;
        this.hub = hub;
        this.emitters = emitters;
    }

    /**
     * Read a bounded page of identifier-only events after a cursor.
     *
     * @param facilityId facility the caller must be allowed to use
     * @param cursor     exclusive lower sequence bound, or {@code null} for zero
     * @param limit      requested page size, clamped to {@code 1..500}
     * @return the matching events and the high-water cursor
     * @throws ApiException on denial or an invalid cursor
     */
    public EventPage poll(String facilityId, String cursor, int limit) {
        Access access = authorize(facilityId);
        long after = requireCursor(cursor);
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Set<String> visible = visibleConversations(access);
        MessageEventHub.Snapshot snapshot = hub.snapshotAfter(after);
        List<RealtimeEvent> events = snapshot.events().stream()
                .filter(event -> visible.contains(event.conversationId()))
                .limit(size)
                .toList();
        return new EventPage(events, snapshot.highWater());
    }

    /**
     * Open a live SSE stream, replaying missed events before live delivery.
     *
     * @param facilityId  facility the caller must be allowed to use
     * @param lastEventId exclusive lower sequence bound, or {@code null} for zero
     * @return the configured emitter
     * @throws ApiException on denial or an invalid sequence
     */
    public SseEmitter stream(String facilityId, String lastEventId) {
        return subscribe(emitters.create(), facilityId, lastEventId);
    }

    SseEmitter subscribe(SseEmitter emitter, String facilityId, String lastEventId) {
        Access access = authorize(facilityId);
        long cursor = requireCursor(lastEventId);
        Set<String> visible = visibleConversations(access);
        AtomicBoolean closed = new AtomicBoolean(false);

        MessageEventHub.Subscriber subscriber = new MessageEventHub.Subscriber() {
            /**
             * Deliver one live event.
             *
             * @param event the appended event
             */
            @Override
            public void accept(RealtimeEvent event) {
                withSnapshot(access.context(), () -> {
                    Set<String> visible = visibleConversations(access);
                    if (!stillAuthorized(access, visible)) {
                        hub.close(this);
                        return;
                    }
                    if (!visible.contains(event.conversationId())) {
                        return;
                    }
                    if (!send(emitter, access.context(), event)) {
                        hub.close(this);
                    }
                });
            }
            /**
             * Release resources held for this subscription, such as completing its emitter.
             */
            @Override
            public void onClose() {
                if (closed.compareAndSet(false, true)) {
                    withSnapshot(access.context(), emitter::complete);
                }
            }
        };

        Runnable cleanup = subscriber::onClose;
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(failure -> subscriber.onClose());

        hub.subscribe(cursor, subscriber, event -> {
            if (visible.contains(event.conversationId()) && !send(emitter, access.context(), event)) {
                hub.close(subscriber);
            }
        });
        return emitter;
    }

    private boolean stillAuthorized(Access access, Set<String> visible) {
        TenantContext context = access.context();
        if (!store.activeStaff(context.organizationId(), context.subject())) {
            return false;
        }
        if (!store.facilityWithinScope(context.organizationId(), context.subject(), access.facility())) {
            return false;
        }
        return !visible.isEmpty();
    }

    private Set<String> visibleConversations(Access access) {
        TenantContext context = access.context();
        return store.conversationsForFacility(context.organizationId(), access.facility(), context.subject())
                .stream()
                .map(StaffConversation::id)
                .collect(Collectors.toSet());
    }

    private static boolean send(SseEmitter emitter, TenantContext snapshot, RealtimeEvent event) {
        TenantContext previous = TenantContext.current();
        TenantContext.set(snapshot);
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence()))
                    .name(event.type().wire()).data(event));
            return true;
        } catch (IOException failure) {
            emitter.completeWithError(failure);
            return false;
        } finally {
            restore(previous);
        }
    }

    /**
     * Run a callback with the captured tenant snapshot bound.
     *
     * <p>Every store read inside the callback is tenant-guarded, so rebinding
     * must cover authorization as well as emitter I/O; the previous thread-local
     * is always restored afterwards.</p>
     *
     * @param snapshot captured tenant and subject
     * @param action   callback to run under that snapshot
     */
    private static void withSnapshot(TenantContext snapshot, Runnable action) {
        TenantContext previous = TenantContext.current();
        TenantContext.set(snapshot);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    /**
     * Restore the thread-local that was bound before a callback. On an async
     * thread there is none, so the snapshot is cleared instead of leaking to the
     * next request served by that pooled thread.
     */
    private static void restore(TenantContext previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }

    private Access authorize(String facilityId) {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(403, "FORBIDDEN", "no active tenant context");
        }
        if (!store.activeStaff(context.organizationId(), context.subject())) {
            throw new ApiException(403, "FORBIDDEN", "the subject has no active staff role");
        }
        String facility = requireFacility(facilityId);
        if (!store.facilityWithinScope(context.organizationId(), context.subject(), facility)) {
            throw new ApiException(403, "FORBIDDEN", "the subject is outside the active facility");
        }
        return new Access(context, facility);
    }

    private static String requireFacility(String facilityId) {
        if (Objects.toString(facilityId, "").isBlank()) {
            throw invalid("facilityId is required");
        }
        return facilityId;
    }

    private static long requireCursor(String raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw.isBlank()) {
            throw invalid("cursor must be a non-negative sequence");
        }
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException failure) {
            throw invalid("cursor must be a non-negative sequence");
        }
        if (value < 0L) {
            throw invalid("cursor must be a non-negative sequence");
        }
        return value;
    }

    private static ApiException invalid(String message) {
        return new ApiException(422, "INVALID_INPUT", message);
    }

    /**
     * Immutable authorization snapshot captured once per request or subscription.
     *
     * @param context  bound tenant and subject
     * @param facility facility selected by the caller and validated in scope
     * @return the snapshot used by every later callback
     */
    private record Access(TenantContext context, String facility) {
    }

    /** Factory seam so emitter callbacks can be tested without an async servlet thread. */
    @FunctionalInterface
    interface EmitterFactory {
        /**
         * Create an emitter for one stream.
         *
         * @return a new SSE emitter
     */
        SseEmitter create();
    }
}
