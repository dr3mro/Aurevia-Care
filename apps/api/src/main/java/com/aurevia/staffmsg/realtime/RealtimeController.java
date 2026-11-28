package com.aurevia.staffmsg.realtime;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Thin HTTP adapter for identifier-only staff conversation events.
 *
 * <p>The active organization and actor are taken from the request context; a
 * caller only names a facility and a sequence cursor. Authorization, membership
 * re-checks and tenant rebinding live in the injected service. Both routes use the
 * same cursor and visibility filter; polling returns a bounded page with a next
 * cursor, while the stream replays identifiers from the requested sequence.</p>
 */
@RestController
@RequestMapping("/api/v1/staff/instant")
public final class RealtimeController {

    private final RealtimeService service;

    /**
     * Create the controller with its injected domain service.
     *
     * @param service realtime authorization and projection service
     */
    public RealtimeController(RealtimeService service) {
        this.service = service;
    }

    /**
     * Open an identifier-only event stream, replaying from the last received sequence.
     *
     * @param facilityId  facility the caller must be allowed to use
     * @param lastEventId sequence of the last event the client received, or absent for zero
     * @return the SSE emitter installed for this subscription
     */
    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String facilityId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return service.stream(facilityId, lastEventId);
    }

    /**
     * Poll the same identifier-only events with a bounded cursor page.
     *
     * @param facilityId facility the caller must be allowed to use
     * @param cursor     exclusive lower sequence bound, or absent for zero
     * @param limit      maximum number of events to return
     * @return the page and the next cursor
     */
    @GetMapping("/events")
    public EventPage events(@RequestParam String facilityId,
                            @RequestParam(name = "cursor", required = false) String cursor,
                            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return service.poll(facilityId, cursor, limit);
    }
}
