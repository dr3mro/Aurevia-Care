package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.events.DeliveryActions;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventContract.ReplayRecord;
import com.aurevia.events.DurableEventContract.ReplayRequest;
import com.aurevia.events.DurableEventContract.Resolution;
import com.aurevia.events.DurableEventContract.ReviewRequest;
import com.aurevia.events.DurableEventService;
import com.aurevia.events.InMemoryDeliveryStore;
import com.aurevia.exchange.result.ResultActionPolicy;
import com.aurevia.exchange.result.ResultCommands.ImportResultRequest;
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.ResultReport;
import com.aurevia.exchange.result.ResultRepository;
import com.aurevia.exchange.result.ResultRoutingService;

/**
 * Cross-cutting acceptance for an exchange that fails after the commit.
 *
 * <p>Requirement XCH-06: patient release references an immutable revision.
 * Spec section 11 "Failed exchange after commit": the order and its outbox entry
 * survive process loss, a replay from the review queue produces one downstream
 * effect and one clinical task for the intended revision, and a stale or foreign
 * tenant attempt is refused. A new service instance over the same store models
 * the restart; all identifiers and notes are synthetic.</p>
 */
class FailedExchangeAfterCommitAcceptanceTest extends FirstSliceFixture {

    private static final String VERIFIER = "syn-result-verifier";
    private static final String WORKER = "syn-exchange-worker";
    private static final String ADMIN = "syn-exchange-admin";
    private static final String ORDER = "order-after-commit";
    private static final String SOURCE = "partner-lab";
    private static final String ACCESSION = "ACC-AFTER-COMMIT";
    private static final String DEST = "clinic-a";
    private static final String AGGREGATE = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String DIGEST = "b".repeat(64);
    private static final String OTHER_DIGEST = "c".repeat(64);
    private static final String KEY = "order-after-commit-1";

    private ResultRepository results;
    private InMemoryDeliveryStore deliveryStore;

    /** Compose the result router and the durable delivery log with synthetic doubles. */
    @BeforeEach
    void setUpExchange() {
        results = mock(ResultRepository.class);
        when(results.hasActiveMembership(VERIFIER)).thenReturn(true);
        when(results.activeRoles(VERIFIER)).thenReturn(List.of("lab_verifier", "clinician"));
        when(results.insertReport(any())).thenReturn(true);
        when(results.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(results.priorRecipients(ORDER)).thenReturn(List.of());
        when(results.findReview(anyString())).thenReturn(Optional.empty());

        deliveryStore = new InMemoryDeliveryStore();
        deliveryStore.addMember(CLINIC_A, WORKER, "worker", true);
        deliveryStore.addMember(CLINIC_A, ADMIN, "tenant_admin", true);
        deliveryStore.addMember(CLINIC_B, WORKER, "worker", true);
        deliveryStore.addMember(CLINIC_B, ADMIN, "tenant_admin", true);
    }

    @Test
    void committedOrderAndOutboxSurviveRestartAndReplayHasOneEffectAndOneTask() {
        // Spec 11: a restarted service over the same durable store returns the
        // originally committed report and task; replaying the same final revision
        // inserts neither a second report nor a second review, and a different
        // payload under the same idempotency key is a conflict.
        bind(CLINIC_A, VERIFIER);
        ResultRoutingService original = router();
        when(results.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of());
        when(results.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.empty());
        ImportOutcome first = original.importResult(request("final", null, DIGEST));
        ResultReport stored = first.report();
        assertEquals(1, stored.version());
        verify(results, times(1)).insertReport(any());
        verify(results, times(1)).insertReview(stored.id());

        ResultRoutingService resumed = router();
        when(results.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(stored));
        when(results.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(stored));
        ImportOutcome replay = resumed.importResult(request("final", null, DIGEST));
        assertEquals(stored, replay.report());
        verify(results, times(1)).insertReport(any());
        verify(results, times(1)).insertReview(stored.id());

        bind(CLINIC_A, WORKER);
        DurableEventService beforeLoss = new DurableEventService(deliveryStore, new DeliveryActions(), clock());
        OutboxEvent committed = beforeLoss.publish(publish(DIGEST));
        DurableEventService afterLoss = new DurableEventService(deliveryStore, new DeliveryActions(), clock());
        OutboxEvent duplicate = afterLoss.publish(publish(DIGEST));
        assertEquals(committed.eventId(), duplicate.eventId());
        assertEquals(committed, deliveryStore.outboxEvent(CLINIC_A, committed.eventId()).orElseThrow());

        ApiException conflict = assertThrows(ApiException.class, () -> afterLoss.publish(publish(OTHER_DIGEST)));
        assertEquals(409, conflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());

        bind(CLINIC_A, ADMIN);
        ReplayRecord record = afterLoss.replay(new ReplayRequest(committed.eventId(), "synthetic restore review"));
        assertEquals(committed.eventId(), record.originalEventId());
        assertEquals(CLINIC_A, record.organizationId());

        bind(CLINIC_B, WORKER);
        assertTrue(deliveryStore.outboxEvent(CLINIC_B, committed.eventId()).isEmpty());
        ApiException concealed = assertThrows(ApiException.class,
                () -> afterLoss.markSent(committed.eventId(), "1"));
        assertEquals(404, concealed.status());
        assertEquals("NOT_FOUND", concealed.code());
    }

    @Test
    void failedTransmissionIsDeadLetteredAndReplayedOnceWithAStaleWriteRefused() {
        // Spec 11: a failed hand-off becomes a restricted, reviewable fact; the
        // authorized replay keeps the original event identity. A caller holding
        // an earlier version cannot advance the event afterwards.
        bind(CLINIC_A, WORKER);
        DurableEventService events = new DurableEventService(deliveryStore, new DeliveryActions(), clock());
        OutboxEvent published = events.publish(publish(DIGEST));
        OutboxEvent sent = events.markSent(published.eventId(), "1");
        OutboxEvent failed = events.markFailed(sent.eventId(), "2", "synthetic partner timeout");
        DeadLetterEntry dead = events.deadLetter(failed.eventId(), "synthetic repeated failure");
        assertEquals(published.eventId(), dead.eventId());

        bind(CLINIC_A, ADMIN);
        DeadLetterEntry reviewed = events.reviewDeadLetter(new ReviewRequest(dead.eventId(),
                Resolution.REQUEUED.wire(), "synthetic authorized requeue"));
        assertEquals(Resolution.REQUEUED, reviewed.resolution());
        assertEquals(ADMIN, reviewed.reviewer());
        ReplayRecord replay = events.replay(new ReplayRequest(published.eventId(), "synthetic reviewed replay"));
        assertEquals(published.eventId(), replay.originalEventId());
        assertEquals(ADMIN, replay.authorizedBy());

        bind(CLINIC_A, WORKER);
        ApiException stale = assertThrows(ApiException.class,
                () -> events.markDelivered(published.eventId(), "1"));
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());

        ApiException workerReplay = assertThrows(ApiException.class,
                () -> events.replay(new ReplayRequest(published.eventId(), "synthetic unprivileged replay")));
        assertEquals(403, workerReplay.status());
        assertEquals("FORBIDDEN", workerReplay.code());

        bind(CLINIC_B, ADMIN);
        ApiException peerReplay = assertThrows(ApiException.class,
                () -> events.replay(new ReplayRequest(published.eventId(), "synthetic peer replay")));
        assertEquals(404, peerReplay.status());
        assertEquals("NOT_FOUND", peerReplay.code());
    }

    private ResultRoutingService router() {
        return new ResultRoutingService(results, new ResultActionPolicy(), transactions, audit, clock());
    }

    private static ExpectedOrder order() {
        return new ExpectedOrder(ORDER, ids(), "owner-1", "coverage-queue");
    }

    private static ResultIdentifiers ids() {
        return new ResultIdentifiers(PATIENT, SOURCE, ACCESSION, DEST, ORDER);
    }

    private static ImportResultRequest request(String kind, Integer supersedes, String checksum) {
        return new ImportResultRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, kind, supersedes,
                NOW.toString(), checksum);
    }

    private static PublishRequest publish(String digest) {
        return new PublishRequest(AGGREGATE, 1, SOURCE, "observation.result", "partner-lab", digest, KEY);
    }
}
