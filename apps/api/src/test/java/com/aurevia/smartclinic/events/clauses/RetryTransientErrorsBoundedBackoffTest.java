package com.aurevia.smartclinic.events.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.events.DeliveryActions;
import com.aurevia.smartclinic.events.DurableEventContract.AuditAction;
import com.aurevia.smartclinic.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.smartclinic.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.smartclinic.events.DurableEventContract.DeliveryState;
import com.aurevia.smartclinic.events.DurableEventContract.Outcome;
import com.aurevia.smartclinic.events.DurableEventContract.OutboxEvent;
import com.aurevia.smartclinic.events.DurableEventContract.PublishRequest;
import com.aurevia.smartclinic.events.DurableEventContract.RetryResult;
import com.aurevia.smartclinic.events.DurableEventService;
import com.aurevia.smartclinic.events.InMemoryDeliveryStore;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ARC-05 clause 4 proof: a transient delivery failure is retried on a bounded,
 * capped backoff, and retry refuses every event it must not touch.
 *
 * <p>These are deterministic in-memory unit tests over the production
 * {@link com.aurevia.smartclinic.events.DurableEventService}. They use a fixed
 * clock and synthetic tenant, aggregate and event identifiers only. No clinical
 * payload or credential appears in any fixture, and every audit fact carries
 * only non-sensitive delivery metadata. The HTTP entry point is proven
 * separately by the cited JDBC delivery-store integration test.</p>
 */
class RetryTransientErrorsBoundedBackoffTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DIGEST = "a".repeat(64);
    private static final String KEY = "order-42-issued";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryDeliveryStore store;
    private DurableEventService service;

    /** Seed synthetic memberships and a fresh service with a fixed clock for one test. */
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
        service = new DurableEventService(store, new DeliveryActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        as("worker", ORG);
    }

    /** Drop the tenant context so one test's identity cannot leak into the next. */
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

    private DeliveryAuditEvent lastAudit(String organizationId) {
        List<DeliveryAuditEvent> events = store.auditEvents(organizationId);
        return events.get(events.size() - 1);
    }

    @Test
    void retrySchedulesBoundedBackoffAfterTransientFailure() {
        OutboxEvent published = publish();
        OutboxEvent failed = service.markFailed(published.eventId(), "1", "channel timeout");

        RetryResult result = service.retry(failed.eventId(), "2");

        OutboxEvent retried = result.event();
        assertEquals(DeliveryState.PENDING, retried.state());
        assertNull(result.deadLetter());
        assertEquals(1, retried.attempt());
        assertEquals(NOW.plusSeconds(DurableEventService.backoffSeconds(1)), retried.nextAttemptAt());
        assertEquals(NOW.plusSeconds(2L), retried.nextAttemptAt());
        assertEquals(failed.aggregateVersion() + 1, retried.aggregateVersion());
        assertEquals(3, retried.aggregateVersion());
        assertEquals(Outcome.SUCCESS, lastAudit(ORG).outcome());
        assertEquals(AuditAction.RETRY_SCHEDULED, lastAudit(ORG).action());
        // Pin the bounded growth: one second at attempt zero, doubling, then a
        // hard 60-second cap across the retry budget's reachable attempt range.
        assertEquals(1L, DurableEventService.backoffSeconds(0));
        assertEquals(2L, DurableEventService.backoffSeconds(1));
        assertEquals(32L, DurableEventService.backoffSeconds(5));
        assertEquals(60L, DurableEventService.backoffSeconds(6));
        assertEquals(60L, DurableEventService.backoffSeconds(20));
    }

    @Test
    void retryDeadLettersWhenTheAttemptBudgetIsExhausted() {
        OutboxEvent published = publish();
        OutboxEvent exhausted = new OutboxEvent(published.eventId(), published.organizationId(),
                published.aggregateId(), published.aggregateVersion(), published.sender(),
                published.eventType(), published.namespace(), published.payloadDigest(),
                published.idempotencyKey(), DeliveryState.FAILED, DurableEventService.MAX_ATTEMPTS,
                published.nextAttemptAt(), published.createdAt(), "channel unavailable");
        store.putOutboxEvent(ORG, exhausted);

        RetryResult result = service.retry(published.eventId(), "1");

        assertNull(result.event());
        DeadLetterEntry dead = result.deadLetter();
        assertEquals(published.eventId(), dead.eventId());
        assertEquals(ORG, dead.organizationId());
        assertEquals("retry budget exhausted", dead.reason());
        assertEquals(DurableEventService.MAX_ATTEMPTS, dead.attempt());
        // The seeded attempt is the last allowed one, so the next attempt (4)
        // exceeds MAX_ATTEMPTS (3) and the event is dead-lettered, not requeued.
        assertTrue(dead.attempt() + 1 > DurableEventService.MAX_ATTEMPTS);
        assertEquals(DeliveryState.DEAD_LETTERED,
                store.outboxEvent(ORG, published.eventId()).orElseThrow().state());
        assertEquals(1, store.deadLetters(ORG).size());
        assertEquals(AuditAction.DEAD_LETTER, lastAudit(ORG).action());
        assertEquals(Outcome.SUCCESS, lastAudit(ORG).outcome());
    }

    @Test
    void retryRefusesAnEventThatIsNotFailed() {
        OutboxEvent published = publish();

        ApiException failure = assertThrows(ApiException.class,
                () -> service.retry(published.eventId(), "1"));

        assertEquals(409, failure.status());
        assertEquals("NOT_FAILED", failure.code());
        assertEquals(Outcome.CONFLICT, lastAudit(ORG).outcome());
    }

    @Test
    void retryRefusesAStaleVersion() {
        OutboxEvent published = publish();
        OutboxEvent failed = service.markFailed(published.eventId(), "1", "channel timeout");

        ApiException failure = assertThrows(ApiException.class,
                () -> service.retry(failed.eventId(), "1"));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        assertEquals(Outcome.STALE, lastAudit(ORG).outcome());
    }

    @Test
    void retryRefusesAnActorWithoutTheDeliveryRole() {
        OutboxEvent published = publish();
        OutboxEvent failed = service.markFailed(published.eventId(), "1", "channel timeout");

        as("clinician", ORG);
        ApiException clinician = assertThrows(ApiException.class,
                () -> service.retry(failed.eventId(), "2"));
        assertEquals(403, clinician.status());
        assertEquals("FORBIDDEN", clinician.code());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());

        as("auditor", ORG);
        ApiException auditor = assertThrows(ApiException.class,
                () -> service.retry(failed.eventId(), "2"));
        assertEquals(403, auditor.status());
        assertEquals("FORBIDDEN", auditor.code());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());

        // A revoked membership loses access on the next request, independent of role.
        as("inactive", ORG);
        ApiException inactive = assertThrows(ApiException.class,
                () -> service.retry(failed.eventId(), "2"));
        assertEquals(403, inactive.status());
        assertEquals("FORBIDDEN", inactive.code());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
    }

    @Test
    void retryRefusesACallerWithoutTenantContext() {
        OutboxEvent published = publish();
        OutboxEvent failed = service.markFailed(published.eventId(), "1", "channel timeout");

        TenantContext.clear();
        ApiException unauthenticated = assertThrows(ApiException.class,
                () -> service.retry(failed.eventId(), "2"));

        assertEquals(401, unauthenticated.status());
        assertEquals("UNAUTHENTICATED", unauthenticated.code());
    }

    @Test
    void retryConcealsAnEventFromAnotherTenant() {
        OutboxEvent published = publish();
        OutboxEvent failed = service.markFailed(published.eventId(), "1", "channel timeout");

        as("worker", OTHER_ORG);
        ApiException concealed = assertThrows(ApiException.class,
                () -> service.retry(failed.eventId(), "2"));

        assertEquals(404, concealed.status());
        assertEquals("NOT_FOUND", concealed.code());
        // The peer tenant never receives the event row; the retry stops at the
        // tenant-scoped lookup and leaves only a concealment audit there.
        assertTrue(store.outboxEvent(OTHER_ORG, published.eventId()).isEmpty());
        List<DeliveryAuditEvent> peerAudit = store.auditEvents(OTHER_ORG);
        assertEquals(1, peerAudit.size());
        assertEquals(Outcome.NOT_FOUND, peerAudit.get(0).outcome());
        // The owning tenant's event is untouched by the cross-tenant attempt.
        assertEquals(failed, store.outboxEvent(ORG, published.eventId()).orElseThrow());
    }
}
