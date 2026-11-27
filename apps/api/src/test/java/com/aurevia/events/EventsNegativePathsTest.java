package com.aurevia.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.aurevia.api.ApiException;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventContract.ReviewRequest;
import com.aurevia.tenant.TenantContext;

/**
 * Executable documentation of the fail-closed failure signals in the delivery package.
 *
 * <p>Every case uses synthetic identifiers and a fixed clock and asserts only
 * behaviour the production code has today. Failure paths already proven by the
 * neighbouring service and HTTP clause suites are deliberately not repeated; the
 * cases here pin validator entry points, the order in which role denial precedes
 * a tenant-scoped lookup, and the absence of record detail in a concealment error.
 * Evidence is from executed unit tests, not a clinical or human reviewer.</p>
 */
class EventsNegativePathsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String MISSING = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String MALFORMED_ID = "not-a-uuid";
    private static final String SENDER = "lab-partner";
    private static final String EVENT_TYPE = "observation.result";
    private static final String NAMESPACE = "partner-lab";
    private static final String DIGEST = "a".repeat(64);
    private static final String KEY = "order-42-issued";
    private static final int MAX_REASON_LENGTH = 256;
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryDeliveryStore store;
    private DurableEventService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryStore();
        store.addMember(ORG, "worker", "worker", true);
        store.addMember(ORG, "operator", "operator", true);
        store.addMember(ORG, "admin", "integration_admin", true);
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        store.addMember(ORG, "inactive", "worker", false);
        // The peer caller must be active and permitted in its own tenant or the
        // service would refuse at authorize() before reaching the 404 path.
        store.addMember(OTHER_ORG, "worker", "worker", true);
        service = new DurableEventService(store, new DeliveryActions(), Clock.fixed(NOW, ZoneOffset.UTC));
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
        return service.publish(request(AGGREGATE, 1, SENDER, EVENT_TYPE, NAMESPACE, DIGEST, KEY));
    }

    private PublishRequest request(String aggregateId, int version, String sender, String eventType,
                                   String namespace, String digest, String key) {
        return new PublishRequest(aggregateId, version, sender, eventType, namespace, digest, key);
    }

    private DeliveryAuditEvent lastAudit(String organizationId) {
        List<DeliveryAuditEvent> events = store.auditEvents(organizationId);
        return events.get(events.size() - 1);
    }

    private void assertInvalidPublish(PublishRequest invalid, String code) {
        ApiException failure = assertThrows(ApiException.class, () -> service.publish(invalid));
        assertEquals(422, failure.status());
        assertEquals(code, failure.code());
        // Validation precedes context and authorization, so there is no actor to
        // attribute and no durable fact to append.
        assertTrue(store.auditEvents(ORG).isEmpty());
        assertTrue(store.byIdempotencyKey(ORG, invalid.sender(), invalid.eventType(),
                invalid.idempotencyKey()).isEmpty());
    }

    private void assertInvalidEventId(Executable command) {
        ApiException failure = assertThrows(ApiException.class, command);
        assertEquals(422, failure.status());
        assertEquals("INVALID_EVENTID", failure.code());
    }

    @Test
    void publishRejectsInvalidFieldsWithoutWritingAnything() {
        assertInvalidPublish(request(" ", 1, SENDER, EVENT_TYPE, NAMESPACE, DIGEST, KEY), "INVALID_AGGREGATEID");
        assertInvalidPublish(request(MALFORMED_ID, 1, SENDER, EVENT_TYPE, NAMESPACE, DIGEST, KEY),
                "INVALID_AGGREGATEID");
        assertInvalidPublish(request(AGGREGATE, 0, SENDER, EVENT_TYPE, NAMESPACE, DIGEST, KEY),
                "INVALID_AGGREGATEVERSION");
        assertInvalidPublish(request(AGGREGATE, 1, " ", EVENT_TYPE, NAMESPACE, DIGEST, KEY), "INVALID_SENDER");
        assertInvalidPublish(request(AGGREGATE, 1, "x".repeat(129), EVENT_TYPE, NAMESPACE, DIGEST, KEY),
                "INVALID_SENDER");
        assertInvalidPublish(request(AGGREGATE, 1, SENDER, " ", NAMESPACE, DIGEST, KEY), "INVALID_EVENTTYPE");
        assertInvalidPublish(request(AGGREGATE, 1, SENDER, EVENT_TYPE, " ", DIGEST, KEY), "INVALID_NAMESPACE");
        assertInvalidPublish(request(AGGREGATE, 1, SENDER, EVENT_TYPE, NAMESPACE, "abc", KEY), "INVALID_DIGEST");
        assertInvalidPublish(request(AGGREGATE, 1, SENDER, EVENT_TYPE, NAMESPACE, DIGEST, " "),
                "INVALID_IDEMPOTENCYKEY");
    }

    @Test
    void invalidPublishInputIsRejectedBeforeContextAndAuthorization() {
        PublishRequest invalid = request(" ", 1, SENDER, EVENT_TYPE, NAMESPACE, DIGEST, KEY);

        TenantContext.clear();
        ApiException unauthenticated = assertThrows(ApiException.class, () -> service.publish(invalid));
        assertEquals(422, unauthenticated.status());
        assertEquals("INVALID_AGGREGATEID", unauthenticated.code());

        // An active but insufficient role must still see the input error: the
        // validator owns this boundary before the role matrix runs.
        as("operator", ORG);
        ApiException insufficientRole = assertThrows(ApiException.class, () -> service.publish(invalid));
        assertEquals(422, insufficientRole.status());
        assertEquals("INVALID_AGGREGATEID", insufficientRole.code());
        assertTrue(store.auditEvents(ORG).isEmpty());
        assertTrue(store.auditEvents(OTHER_ORG).isEmpty());
    }

    @Test
    void deliveryCommandsRejectOversizedReasonsBeforeAnyWrite() {
        OutboxEvent event = publish();
        String oversized = "x".repeat(MAX_REASON_LENGTH + 1);

        ApiException failed = assertThrows(ApiException.class,
                () -> service.markFailed(event.eventId(), "1", oversized));
        assertEquals(422, failed.status());
        assertEquals("INVALID_REASON", failed.code());
        ApiException dead = assertThrows(ApiException.class,
                () -> service.deadLetter(event.eventId(), oversized));
        assertEquals(422, dead.status());
        assertEquals("INVALID_REASON", dead.code());
        as("admin", ORG);
        ApiException reviewed = assertThrows(ApiException.class,
                () -> service.reviewDeadLetter(new ReviewRequest(event.eventId(), "resolved", oversized)));
        assertEquals(422, reviewed.status());
        assertEquals("INVALID_REASON", reviewed.code());

        assertEquals(1, store.auditEvents(ORG).size(), "only the publish success was recorded");
        assertEquals(event, store.outboxEvent(ORG, event.eventId()).orElseThrow());
    }

    @Test
    void transitionCommandsRejectMalformedIdsBeforeAnyWrite() {
        assertInvalidEventId(() -> service.markSent(MALFORMED_ID, "1"));
        assertInvalidEventId(() -> service.markDelivered(MALFORMED_ID, "1"));
        assertInvalidEventId(() -> service.retry(MALFORMED_ID, "1"));
        assertInvalidEventId(() -> service.deadLetter(MALFORMED_ID, "probe"));
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void roleDenialPrecedesLookupSoAnAuditorCannotProbeEventExistence() {
        as("auditor", ORG);

        ApiException mark = assertThrows(ApiException.class, () -> service.markSent(MISSING, "1"));
        assertEquals(403, mark.status());
        assertEquals("FORBIDDEN", mark.code());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
        ApiException retry = assertThrows(ApiException.class, () -> service.retry(MISSING, "1"));
        assertEquals(403, retry.status());
        assertEquals("FORBIDDEN", retry.code());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());

        // A 403 rather than 404 keeps a wrong-role caller from learning whether
        // the identifier exists in this tenant.
        assertTrue(store.outboxEvent(ORG, MISSING).isEmpty());
        assertEquals(2, store.auditEvents(ORG).size());
    }

    @Test
    void crossTenantDeadLetterIsConcealedWithoutRecordDetail() {
        OutboxEvent event = publish();
        as("worker", OTHER_ORG);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.deadLetter(event.eventId(), "probe"));
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        assertEquals("NOT_FOUND", failure.getMessage());
        assertFalse(failure.getMessage().contains(AGGREGATE));
        assertFalse(failure.getMessage().contains(DIGEST));
        assertFalse(failure.getMessage().contains(event.eventId()));
        assertFalse(failure.getMessage().contains(ORG));

        assertEquals(1, store.auditEvents(OTHER_ORG).size());
        assertEquals(Outcome.NOT_FOUND, lastAudit(OTHER_ORG).outcome());
        assertTrue(store.outboxEvent(OTHER_ORG, event.eventId()).isEmpty());
        assertEquals(event, store.outboxEvent(ORG, event.eventId()).orElseThrow());
    }
}
