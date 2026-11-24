package com.aurevia.events;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.events.DurableEventContract.ConsumerReceipt;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.ExternalEvent;
import com.aurevia.events.DurableEventContract.ExternalLink;
import com.aurevia.events.DurableEventContract.LinkRequest;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventContract.ReplayRecord;
import com.aurevia.events.DurableEventContract.ReplayRequest;
import com.aurevia.events.DurableEventContract.RetryResult;
import com.aurevia.events.DurableEventContract.ReviewRequest;

/**
 * Thin HTTP adapter for ARC-05 durable delivery.
 *
 * <p>All tenant derivation, authorization, idempotency, ordering, backoff and
 * review decisions live in
 * {@link com.aurevia.events.DurableEventService}. This adapter only
 * binds fields and maps the created event to its Location header.</p>
 */
@RestController
@RequestMapping("/api/v1/delivery")
public class DeliveryController {

    private final DurableEventService service;

    /**
     * Create the controller.
     *
     * @param service ARC-05 durable delivery domain service
     */
    public DeliveryController(DurableEventService service) {
        this.service = service;
    }

    /**
     * Commit one outbox event.
     *
     * @param request publish command
     * @return HTTP 201 with the committed event and its Location header
     */
    @PostMapping("/events")
    public ResponseEntity<OutboxEvent> publish(@RequestBody PublishRequest request) {
        OutboxEvent event = service.publish(request);
        return ResponseEntity.created(URI.create("/api/v1/delivery/events/" + event.eventId())).body(event);
    }

    /**
     * Mark an event as sent.
     *
     * @param id      event id
     * @param ifMatch expected aggregate version
     * @return the new event snapshot
     */
    @PostMapping("/events/{id}/sent")
    public OutboxEvent markSent(@PathVariable String id, @RequestHeader("If-Match") String ifMatch) {
        return service.markSent(id, ifMatch);
    }

    /**
     * Mark an event as delivered.
     *
     * @param id      event id
     * @param ifMatch expected aggregate version
     * @return the new event snapshot
     */
    @PostMapping("/events/{id}/delivered")
    public OutboxEvent markDelivered(@PathVariable String id, @RequestHeader("If-Match") String ifMatch) {
        return service.markDelivered(id, ifMatch);
    }

    /**
     * Record a failed send attempt.
     *
     * @param id      event id
     * @param ifMatch expected aggregate version
     * @param reason  non-sensitive failure reason
     * @return the new event snapshot
     */
    @PostMapping("/events/{id}/failed")
    public OutboxEvent markFailed(@PathVariable String id, @RequestHeader("If-Match") String ifMatch,
                                  @RequestParam String reason) {
        return service.markFailed(id, ifMatch, reason);
    }

    /**
     * Schedule a bounded retry or dead-letter the event.
     *
     * @param id      event id
     * @param ifMatch expected aggregate version
     * @return the requeued event or the dead-letter entry
     */
    @PostMapping("/events/{id}/retry")
    public RetryResult retry(@PathVariable String id, @RequestHeader("If-Match") String ifMatch) {
        return service.retry(id, ifMatch);
    }

    /**
     * Move a failed event to the restricted dead-letter queue.
     *
     * @param id     event id
     * @param reason non-sensitive reason
     * @return the created dead-letter entry
     */
    @PostMapping("/events/{id}/dead-letter")
    public DeadLetterEntry deadLetter(@PathVariable String id, @RequestParam String reason) {
        return service.deadLetter(id, reason);
    }

    /**
     * Review a dead-letter entry.
     *
     * @param request authorized review command
     * @return the reviewed entry
     */
    @PostMapping("/dead-letters/review")
    public DeadLetterEntry reviewDeadLetter(@RequestBody ReviewRequest request) {
        return service.reviewDeadLetter(request);
    }

    /**
     * Consume one external partner event.
     *
     * @param event validated external event
     * @return the processing receipt
     */
    @PostMapping("/consumer-events")
    public ConsumerReceipt consume(@RequestBody ExternalEvent event) {
        return service.consume(event);
    }

    /**
     * Replay an event with its original identity.
     *
     * @param request authorized replay command
     * @return the replay fact
     */
    @PostMapping("/replays")
    public ReplayRecord replay(@RequestBody ReplayRequest request) {
        return service.replay(request);
    }

    /**
     * Link consumed data to confirmed order and patient identifiers.
     *
     * @param request validated link command
     * @return the provenance link fact
     */
    @PostMapping("/external-links")
    public ExternalLink linkExternal(@RequestBody LinkRequest request) {
        return service.linkExternal(request);
    }

    /**
     * Read the restricted dead-letter queue.
     *
     * @return dead-letter entries in insertion order
     */
    @GetMapping("/dead-letters")
    public List<DeadLetterEntry> deadLetters() {
        return service.deadLetters();
    }

    /**
     * Read the tenant's append-only delivery audit trail.
     *
     * @return audit facts in insertion order
     */
    @GetMapping("/audit")
    public List<DeliveryAuditEvent> auditTrail() {
        return service.auditTrail();
    }
}
