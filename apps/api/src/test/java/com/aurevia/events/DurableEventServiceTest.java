package com.aurevia.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.aurevia.api.ApiException;
import com.aurevia.events.DurableEventContract.ConsumerReceipt;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.ExternalEvent;
import com.aurevia.events.DurableEventContract.ExternalLink;
import com.aurevia.events.DurableEventContract.LinkRequest;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.ProcessingOutcome;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventContract.ReplayRecord;
import com.aurevia.events.DurableEventContract.ReplayRequest;
import com.aurevia.events.DurableEventContract.Resolution;
import com.aurevia.events.DurableEventContract.ReviewRequest;
import com.aurevia.events.DurableEventContract.RetryResult;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-05 acceptance for the durable outbox, deduplication, replay and dead-letter review.
 *
 * <p>Every case uses synthetic identifiers and a fixed clock. Evidence is from
 * executed unit tests, not a clinical or human reviewer.</p>
 */
class DurableEventServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ORDER = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String MISSING = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);
    private static final String KEY = "order-42-issued";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryDeliveryStore store;
    private DurableEventService service;
    private DeliveryController controller;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryStore();
        store.addMember(ORG, "worker", "worker", true);
        store.addMember(ORG, "operator", "operator", true);
        store.addMember(ORG, "admin", "integration_admin", true);
        store.addMember(ORG, "clinician", "clinician", true);
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        store.addMember(ORG, "inactive", "worker", false);
        store.addMember(OTHER_ORG, "worker", "worker", true);
        store.addOrder(ORG, ORDER, PATIENT);
        store.addPatient(ORG, PATIENT);
        service = new DurableEventService(store, new DeliveryActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        controller = new DeliveryController(service);
        as("worker", ORG);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void as(String subject, String organizationId) {
        TenantContext.set(new TenantContext(organizationId, subject, "DELIVERY", "corr-1"));
    }

    private OutboxEvent publish() {
        return service.publish(new PublishRequest(AGGREGATE, 1, "lab-partner", "observation.result",
                "partner-lab", DIGEST, KEY));
    }

    private DeliveryAuditEvent lastAudit() {
        List<DeliveryAuditEvent> events = store.auditEvents(ORG);
        return events.get(events.size() - 1);
    }

    @Test
    void publishCommitsOutboxAuditAndDedupKeyTogether() {
        OutboxEvent event = publish();
        assertEquals(DeliveryState.PENDING, event.state());
        assertEquals(1, event.aggregateVersion());
        assertEquals(0, event.attempt());
        assertEquals(NOW, event.createdAt());
        assertNotNull(event.eventId());
        assertEquals(event, store.outboxEvent(ORG, event.eventId()).orElseThrow());
        assertEquals(event, store.byIdempotencyKey(ORG, "lab-partner", "observation.result", KEY).orElseThrow());
        assertEquals(1, store.auditEvents(ORG).size());
        assertEquals("worker", lastAudit().actorId());
        assertEquals("DELIVERY", lastAudit().purpose());
        assertEquals("corr-1", lastAudit().correlationId());
    }

    @Test
    void publishWithSameKeyAndDigestReturnsTheOriginalEvent() {
        OutboxEvent first = publish();
        OutboxEvent repeated = publish();
        assertEquals(first.eventId(), repeated.eventId());
        assertEquals(1, store.byIdempotencyKey(ORG, "lab-partner", "observation.result", KEY).isPresent() ? 1 : 0);
        assertEquals(2, store.auditEvents(ORG).size());
        assertEquals(Outcome.DUPLICATE, lastAudit().outcome());
    }

    @Test
    void publishWithSameKeyAndDifferentDigestIsAConflict() {
        publish();
        ApiException failure = assertThrows(ApiException.class, () ->
                service.publish(new PublishRequest(AGGREGATE, 1, "lab-partner", "observation.result",
                        "partner-lab", OTHER_DIGEST, KEY)));
        assertEquals(409, failure.status());
        assertEquals("IDEMPOTENCY_CONFLICT", failure.code());
        assertEquals(Outcome.CONFLICT, lastAudit().outcome());
    }

    @Test
    void publishFailsClosedWithoutContextOrMembership() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, this::publish).status());
        as("inactive", ORG);
        ApiException denied = assertThrows(ApiException.class, this::publish);
        assertEquals(403, denied.status());
        assertEquals(Outcome.DENIED, lastAudit().outcome());
        as("inactive", OTHER_ORG);
        assertEquals(403, assertThrows(ApiException.class, this::publish).status());
    }

    @Test
    void stateTransitionsAdvanceVersionAndImmutableEventId() {
        OutboxEvent published = publish();
        OutboxEvent sent = service.markSent(published.eventId(), "1");
        assertEquals(published.eventId(), sent.eventId());
        assertEquals(DeliveryState.SENT, sent.state());
        assertEquals(2, sent.aggregateVersion());
        OutboxEvent delivered = service.markDelivered(published.eventId(), "2");
        assertEquals(published.eventId(), delivered.eventId());
        assertEquals(DeliveryState.DELIVERED, delivered.state());
        assertEquals(3, delivered.aggregateVersion());
        assertEquals(delivered, store.outboxEvent(ORG, published.eventId()).orElseThrow());
    }

    @Test
    void illegalTransitionAndStaleVersionAreRejected() {
        OutboxEvent published = publish();
        ApiException stale = assertThrows(ApiException.class,
                () -> service.markSent(published.eventId(), "2"));
        assertEquals(412, stale.status());
        assertEquals(Outcome.STALE, lastAudit().outcome());
        OutboxEvent sent = service.markSent(published.eventId(), "1");
        ApiException illegal = assertThrows(ApiException.class,
                () -> service.markSent(sent.eventId(), "2"));
        assertEquals(409, illegal.status());
        assertEquals("ILLEGAL_TRANSITION", illegal.code());
        assertEquals(Outcome.CONFLICT, lastAudit().outcome());
    }

    @Test
    void markFailedRecordsReasonFromAnyActiveState() {
        OutboxEvent published = publish();
        OutboxEvent failed = service.markFailed(published.eventId(), "1", "partner timeout");
        assertEquals(DeliveryState.FAILED, failed.state());
        assertEquals("partner timeout", failed.detail());
        assertEquals(2, failed.aggregateVersion());
        OutboxEvent second = service.publish(new PublishRequest(AGGREGATE, 1, "lab-partner",
                "observation.result", "partner-lab", DIGEST, "key-sent"));
        OutboxEvent sent = service.markSent(second.eventId(), "1");
        assertEquals(DeliveryState.FAILED,
                service.markFailed(sent.eventId(), "2", "delivery refused").state());
    }

    @Test
    void retrySchedulesBoundedBackoffAndRequeues() {
        OutboxEvent published = publish();
        OutboxEvent failed = service.markFailed(published.eventId(), "1", "timeout");
        RetryResult result = service.retry(failed.eventId(), "2");
        OutboxEvent retried = result.event();
        assertEquals(DeliveryState.PENDING, retried.state());
        assertEquals(null, result.deadLetter());
        assertEquals(1, retried.attempt());
        assertEquals(NOW.plusSeconds(DurableEventService.backoffSeconds(1)), retried.nextAttemptAt());
        assertEquals(3, retried.aggregateVersion());
        assertEquals(Outcome.SUCCESS, lastAudit().outcome());
        assertEquals(60L, DurableEventService.backoffSeconds(20));
        assertEquals(1L, DurableEventService.backoffSeconds(0));
    }

    @Test
    void retryExhaustionMovesEventToRestrictedDeadLetterQueue() {
        OutboxEvent published = publish();
        store.putOutboxEvent(ORG, withAttempt(published, 3, DeliveryState.FAILED));
        RetryResult retryResult = service.retry(published.eventId(), "1");
        assertEquals(null, retryResult.event());
        DeadLetterEntry dead = retryResult.deadLetter();
        assertEquals(published.eventId(), dead.eventId());
        assertEquals(3, dead.attempt());
        assertEquals(DeliveryState.DEAD_LETTERED,
                store.outboxEvent(ORG, published.eventId()).orElseThrow().state());
        assertEquals(1, store.deadLetters(ORG).size());
    }

    @Test
    void deadLetterRequiresFailedStateAndIsAudited() {
        OutboxEvent published = publish();
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.deadLetter(published.eventId(), "manual")).status());
        OutboxEvent failed = service.markFailed(published.eventId(), "1", "timeout");
        DeadLetterEntry entry = service.deadLetter(failed.eventId(), "manual review");
        assertEquals("manual review", entry.reason());
        assertEquals(1, store.deadLetters(ORG).size());
    }

    @Test
    void reviewRequeuesOrResolvesAndIsRestrictedToAdmins() {
        OutboxEvent published = publish();
        DeadLetterEntry entry = service.deadLetter(service.markFailed(published.eventId(), "1", "x").eventId(),
                "review");
        as("worker", ORG);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.reviewDeadLetter(new ReviewRequest(entry.eventId(), "requeued", "try again")))
                .status());
        as("admin", ORG);
        DeadLetterEntry requeued = service.reviewDeadLetter(
                new ReviewRequest(entry.eventId(), "requeued", "try again"));
        assertEquals(Resolution.REQUEUED, requeued.resolution());
        assertEquals("admin", requeued.reviewer());
        assertEquals("try again", requeued.reviewReason());
        assertEquals(DeliveryState.PENDING,
                store.outboxEvent(ORG, entry.eventId()).orElseThrow().state());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.reviewDeadLetter(new ReviewRequest(entry.eventId(), "resolved", "done")))
                .status());
        DeadLetterEntry second = service.deadLetter(
                service.markFailed(published.eventId(), "4", "again").eventId(), "review-2");
        DeadLetterEntry resolved = service.reviewDeadLetter(
                new ReviewRequest(second.eventId(), "resolved", "no retry"));
        assertEquals(Resolution.RESOLVED, resolved.resolution());
        assertEquals(DeliveryState.DEAD_LETTERED,
                store.outboxEvent(ORG, second.eventId()).orElseThrow().state());
    }

    @Test
    void missingResourcesAreConcealed() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.markSent(MISSING, "1")).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.deadLetter(MISSING, "x")).status());
        as("admin", ORG);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.reviewDeadLetter(new ReviewRequest(MISSING, "resolved", "x"))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.replay(new ReplayRequest(MISSING, "reason"))).status());
    }

    private OutboxEvent withAttempt(OutboxEvent event, int attempt, DeliveryState state) {
        return new OutboxEvent(event.eventId(), event.organizationId(), event.aggregateId(),
                event.aggregateVersion(), event.sender(), event.eventType(), event.namespace(),
                event.payloadDigest(), event.idempotencyKey(), state, attempt, event.nextAttemptAt(),
                event.createdAt(), event.detail());
    }

    private ExternalEvent inbound(String externalId, int version, String order, String patient) {
        return new ExternalEvent(externalId, "lab-partner", "observation.result", "partner-lab",
                AGGREGATE, version, order, patient, DIGEST);
    }

    @Test
    void consumeDeduplicatesInsideTheSenderAndTypeNamespace() {
        ExternalEvent event = inbound("ext-1", 1, ORDER, PATIENT);
        ConsumerReceipt first = service.consume(event);
        assertEquals(ProcessingOutcome.ACCEPTED, first.outcome());
        ConsumerReceipt duplicate = service.consume(event);
        assertEquals(first.receiptId(), duplicate.receiptId());
        // Deduplication returns the original immutable fact; the repeat is a
        // separate audit outcome, never a second processing effect.
        assertEquals(first.outcome(), duplicate.outcome());
        assertEquals(2, store.auditEvents(ORG).stream()
                .filter(a -> a.action().wire().equals("consume")).count());
        assertEquals(Outcome.DUPLICATE, lastAudit().outcome());
        // The same external id from a different sender is a distinct event.
        ExternalEvent otherSender = new ExternalEvent("ext-1", "radiology-partner", "observation.result",
                "partner-lab", AGGREGATE, 1, ORDER, PATIENT, DIGEST);
        assertEquals(ProcessingOutcome.ACCEPTED, service.consume(otherSender).outcome());
    }

    @Test
    void consumeHoldsOutOfOrderChangesAndRecordsTheOutcome() {
        service.consume(inbound("ext-2", 5, ORDER, PATIENT));
        ConsumerReceipt stale = service.consume(inbound("ext-1", 3, ORDER, PATIENT));
        assertEquals(ProcessingOutcome.HELD_OUT_OF_ORDER, stale.outcome());
        assertEquals(Outcome.HELD, lastAudit().outcome());
        // A correction at a higher version is accepted and the original receipt kept.
        ConsumerReceipt correction = service.consume(inbound("ext-3", 6, ORDER, PATIENT));
        assertEquals(ProcessingOutcome.ACCEPTED, correction.outcome());
        assertEquals(3, store.auditEvents(ORG).stream()
                .filter(a -> a.action().wire().equals("consume")).count());
    }

    @Test
    void consumeRejectsUnconfirmedIdentifiers() {
        ConsumerReceipt wrongOrder = service.consume(inbound("ext-4", 1, MISSING, PATIENT));
        assertEquals(ProcessingOutcome.REJECTED, wrongOrder.outcome());
        assertEquals(Outcome.NOT_FOUND, lastAudit().outcome());
        ConsumerReceipt wrongPatient = service.consume(inbound("ext-5", 2, ORDER, MISSING));
        assertEquals(ProcessingOutcome.REJECTED, wrongPatient.outcome());
    }

    @Test
    void consumeAcceptsEventsWithoutIdentifiersAndRejectsMalformedOnes() {
        ExternalEvent anonymous = new ExternalEvent("ext-anon", "lab-partner", "observation.result",
                "partner-lab", AGGREGATE, 1, null, null, DIGEST);
        assertEquals(ProcessingOutcome.ACCEPTED, service.consume(anonymous).outcome());
        assertEquals(422, assertThrows(ApiException.class, () -> service.consume(
                new ExternalEvent("ext-bad", "lab-partner", "observation.result", "partner-lab",
                        AGGREGATE, 1, "not-a-uuid", PATIENT, DIGEST))).status());
    }

    @Test
    void replayPreservesEventIdentityAndRequiresAuthorizationAndReason() {
        OutboxEvent original = publish();
        as("worker", ORG);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.replay(new ReplayRequest(original.eventId(), "again"))).status());
        as("admin", ORG);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.replay(new ReplayRequest(original.eventId(), " "))).status());
        ReplayRecord replay = service.replay(new ReplayRequest(original.eventId(), "partner outage replay"));
        assertEquals(original.eventId(), replay.originalEventId());
        assertEquals("admin", replay.authorizedBy());
        assertEquals("partner outage replay", replay.reason());
        assertEquals(original, store.outboxEvent(ORG, original.eventId()).orElseThrow());
        assertEquals(Outcome.SUCCESS, lastAudit().outcome());
    }

    @Test
    void linkExternalConfirmsOrderAndPatientBeforeLinking() {
        service.consume(inbound("ext-6", 1, ORDER, PATIENT));
        as("clinician", ORG);
        ExternalLink link = service.linkExternal(new LinkRequest("ext-6", ORDER, PATIENT, AGGREGATE));
        assertEquals(PATIENT, link.patientId());
        assertEquals(ORDER, link.orderId());
        assertEquals("clinician", link.linkedBy());
    }

    @Test
    void linkExternalConcealsMismatchedOrUnprocessedData() {
        service.consume(inbound("ext-7", 1, ORDER, PATIENT));
        as("clinician", ORG);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(new LinkRequest("ext-7", ORDER, MISSING, AGGREGATE))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(new LinkRequest("ext-7", MISSING, PATIENT, AGGREGATE))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(new LinkRequest("missing", ORDER, PATIENT, AGGREGATE))).status());
        as("worker", ORG);
        assertEquals(ProcessingOutcome.REJECTED,
                service.consume(inbound("ext-8", 2, MISSING, PATIENT)).outcome());
        as("clinician", ORG);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(new LinkRequest("ext-8", ORDER, PATIENT, AGGREGATE))).status());
    }

    @Test
    void deadLetterReviewAndAuditAreRoleScoped() {
        as("worker", ORG);
        assertEquals(403, assertThrows(ApiException.class, service::deadLetters).status());
        as("admin", ORG);
        assertTrue(service.deadLetters().isEmpty());
        assertEquals(Outcome.SUCCESS, lastAudit().outcome());
        as("worker", ORG);
        assertEquals(403, assertThrows(ApiException.class, service::auditTrail).status());
        assertEquals(Outcome.DENIED, lastAudit().outcome());
        as("auditor", ORG);
        assertFalse(service.auditTrail().isEmpty());
        assertEquals(Outcome.SUCCESS, lastAudit().outcome());
    }

    @Test
    void crossTenantEventsAreConcealed() {
        OutboxEvent event = publish();
        as("worker", OTHER_ORG);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.markSent(event.eventId(), "1")).status());
        assertEquals(1, store.auditEvents(OTHER_ORG).size());
        assertEquals(Outcome.NOT_FOUND, store.auditEvents(OTHER_ORG).get(0).outcome());
    }

    @Test
    void retryRequiresAFailedEvent() {
        OutboxEvent pending = publish();
        ApiException failure = assertThrows(ApiException.class, () -> service.retry(pending.eventId(), "1"));
        assertEquals(409, failure.status());
        assertEquals("NOT_FAILED", failure.code());
        assertEquals(Outcome.CONFLICT, lastAudit().outcome());
    }

    @Test
    void linkExternalRechecksOrderAndPatientExistence() {
        String ghostOrder = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
        store.putReceipt(ORG, new ConsumerReceipt("r1", ORG, "ext-ghost-order", "lab", "result",
                "partner-lab", AGGREGATE, 1, ghostOrder, PATIENT, ProcessingOutcome.ACCEPTED,
                null, NOW, "worker"));
        store.putReceipt(ORG, new ConsumerReceipt("r2", ORG, "ext-ghost-patient", "lab", "result",
                "partner-lab", AGGREGATE, 2, ORDER, MISSING, ProcessingOutcome.ACCEPTED,
                null, NOW, "worker"));
        as("clinician", ORG);
        assertEquals(404, assertThrows(ApiException.class, () -> service.linkExternal(
                new LinkRequest("ext-ghost-order", ghostOrder, PATIENT, AGGREGATE))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit().outcome());
        assertEquals(404, assertThrows(ApiException.class, () -> service.linkExternal(
                new LinkRequest("ext-ghost-patient", ORDER, MISSING, AGGREGATE))).status());
    }

    @Test
    void illegalTransitionsAreRejectedFromEveryTerminalState() {
        OutboxEvent pending = publish();
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.markDelivered(pending.eventId(), "1")).status());
        OutboxEvent second = service.publish(new PublishRequest(AGGREGATE, 1, "lab-partner",
                "observation.result", "partner-lab", DIGEST, "key-second"));
        service.markSent(second.eventId(), "1");
        OutboxEvent delivered = service.markDelivered(second.eventId(), "2");
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.markSent(delivered.eventId(), "3")).status());
        OutboxEvent third = service.publish(new PublishRequest(AGGREGATE, 1, "lab-partner",
                "observation.result", "partner-lab", DIGEST, "key-third"));
        OutboxEvent failed = service.markFailed(third.eventId(), "1", "timeout");
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.markSent(failed.eventId(), "2")).status());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.markDelivered(failed.eventId(), "2")).status());
    }

    @Test
    void controllerRoutesBindEveryCommandToTheService() {
        OutboxEvent published = publish();
        OutboxEvent sent = controller.markSent(published.eventId(), "1");
        assertEquals(DeliveryState.SENT, sent.state());
        OutboxEvent delivered = controller.markDelivered(published.eventId(), "2");
        assertEquals(DeliveryState.DELIVERED, delivered.state());
        OutboxEvent failed = controller.markFailed(published.eventId(), "3", "timeout");
        assertEquals(DeliveryState.FAILED, failed.state());
        OutboxEvent retried = controller.retry(published.eventId(), "4").event();
        assertEquals(DeliveryState.PENDING, retried.state());
        controller.markFailed(published.eventId(), "5", "timeout again");
        DeadLetterEntry dead = controller.deadLetter(published.eventId(), "manual");
        assertEquals(published.eventId(), dead.eventId());
        as("admin", ORG);
        DeadLetterEntry reviewed = controller.reviewDeadLetter(
                new ReviewRequest(dead.eventId(), "resolved", "done"));
        assertEquals(Resolution.RESOLVED, reviewed.resolution());
        assertNotNull(controller.consume(inbound("ext-9", 1, ORDER, PATIENT)));
        assertNotNull(controller.replay(new ReplayRequest(published.eventId(), "reason")));
        assertNotNull(controller.linkExternal(new LinkRequest("ext-9", ORDER, PATIENT, AGGREGATE)));
        assertNotNull(controller.deadLetters());
        as("auditor", ORG);
        assertNotNull(controller.auditTrail());
        as("worker", ORG);
        ResponseEntity<OutboxEvent> created = controller.publish(new PublishRequest(AGGREGATE, 1,
                "lab-partner", "observation.result", "partner-lab", DIGEST, "key-2"));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals(URI.create("/api/v1/delivery/events/" + created.getBody().eventId()),
                created.getHeaders().getLocation());
    }
}
