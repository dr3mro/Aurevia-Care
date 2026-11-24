package com.aurevia.it.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.conformance.ConformanceFixture;
import com.aurevia.exchange.conformance.ConformanceFixtures;
import com.aurevia.exchange.conformance.ConformanceLedger;
import com.aurevia.exchange.conformance.Disposition;
import com.aurevia.exchange.conformance.EventKind;
import com.aurevia.exchange.conformance.PartnerEvent;
import com.aurevia.exchange.conformance.ProcessingResult;
import com.aurevia.exchange.conformance.RevisionRecord;

/**
 * Executable conformance evidence for the synthetic partner fixtures
 * (XCH-01 to XCH-07, LAB-01, LAB-02, ARC-05). Every scenario is synthetic and
 * runs without a database so it executes in the fast tier and in the full gate.
 */
class PartnerConformanceFixturesTest {

    @Test
    void everyFixtureProducesItsDeclaredDispositions() {
        for (ConformanceFixture fixture : ConformanceFixtures.all()) {
            ConformanceLedger ledger = fixture.newLedger();
            List<Disposition> actual = new ArrayList<>();
            for (PartnerEvent event : fixture.events()) {
                actual.add(ledger.apply(event).disposition());
            }
            assertEquals(fixture.expectedDispositions(), actual, fixture.id());
        }
    }

    @Test
    void fixtureCatalogIsCompleteNamedAndTraceable() {
        List<ConformanceFixture> all = ConformanceFixtures.all();
        assertEquals(13, all.size());
        for (ConformanceFixture fixture : all) {
            assertNotNull(fixture.id());
            assertFalse(fixture.id().isBlank());
            assertFalse(fixture.requirements().isBlank());
            assertFalse(fixture.description().isBlank());
            assertNotNull(fixture.ledger().get());
            assertEquals(fixture, ConformanceFixtures.byId(fixture.id()).orElseThrow());
        }
        assertTrue(ConformanceFixtures.byId("does-not-exist").isEmpty());
    }

    @Test
    void happyPathKeepsOneOrderedRevisionPerAggregate() {
        ConformanceFixture fixture = ConformanceFixtures.happyOrderToResult();
        ConformanceLedger ledger = fixture.newLedger();
        for (PartnerEvent event : fixture.events()) {
            assertEquals(Disposition.APPLIED, ledger.apply(event).disposition());
        }
        assertEquals(6, ledger.revisions("order-1").size());
        assertEquals(6L, ledger.lastRevision("order-1"));
        assertEquals(-1L, ledger.lastRevision("unknown-aggregate"));
        assertFalse(ledger.reviewOpen("order-1"));
        assertTrue(ledger.deadLetters().isEmpty());
        assertTrue(ledger.reconciliation().isEmpty());
        assertEquals(fixture.events().size(), ledger.seenCount());
    }

    @Test
    void duplicateDeliveryHasNoSecondEffect() {
        ConformanceFixture fixture = ConformanceFixtures.duplicateDelivery();
        ConformanceLedger ledger = fixture.newLedger();
        PartnerEvent first = fixture.events().get(0);
        ProcessingResult firstResult = ledger.apply(first);
        ProcessingResult secondResult = ledger.apply(fixture.events().get(1));
        assertEquals(Disposition.APPLIED, firstResult.disposition());
        assertEquals(Disposition.DUPLICATE, secondResult.disposition());
        assertEquals("duplicate", secondResult.detail());
        assertEquals(1, ledger.seenCount());
        assertEquals(1, ledger.revisions("order-dup").size());
    }

    @Test
    void correctionPreservesThePriorRevisionAndReopensReview() {
        ConformanceFixture fixture = ConformanceFixtures.correctionReopensReview();
        ConformanceLedger ledger = fixture.newLedger();
        ledger.apply(fixture.events().get(0));
        ledger.apply(fixture.events().get(1));
        assertFalse(ledger.reviewOpen("order-correction"));
        ledger.apply(fixture.events().get(2));
        assertTrue(ledger.reviewOpen("order-correction"));

        List<RevisionRecord> revisions = ledger.revisions("order-correction");
        assertEquals(3, revisions.size());
        RevisionRecord original = revisions.get(0);
        assertEquals(1L, original.revision());
        assertEquals(EventKind.RESULT, original.kind());
        assertEquals("ACC-1001", original.identifier());
        assertEquals("7.1 mg/dL", original.payload());
        assertFalse(original.reviewOpened());
        RevisionRecord corrected = revisions.get(2);
        assertEquals(3L, corrected.revision());
        assertEquals(EventKind.CORRECTION, corrected.kind());
        assertTrue(corrected.reviewOpened());
        assertEquals(3L, ledger.lastRevision("order-correction"));
    }

    @Test
    void transmissionAckCannotCloseClinicalReviewButClinicalAckCan() {
        ConformanceFixture fixture = ConformanceFixtures.criticalRequiresClinicalAck();
        ConformanceLedger ledger = fixture.newLedger();
        ledger.apply(fixture.events().get(0));
        assertTrue(ledger.reviewOpen("order-critical"));
        ledger.apply(fixture.events().get(1));
        assertTrue(ledger.reviewOpen("order-critical"));
        ledger.apply(fixture.events().get(2));
        assertFalse(ledger.reviewOpen("order-critical"));
    }

    @Test
    void partialOutageRetriesThenAppliesExactlyOnce() {
        ConformanceFixture fixture = ConformanceFixtures.partialOutage();
        ConformanceLedger ledger = fixture.newLedger();
        PartnerEvent event = fixture.events().get(0);
        assertEquals(Disposition.APPLIED, ledger.apply(event).disposition());
        assertEquals(3, ledger.attempts(event));
        assertEquals(1, ledger.revisions("order-outage").size());
        assertTrue(ledger.deadLetters().isEmpty());
    }

    @Test
    void fullOutageDeadLettersWithoutCreatingAnEffect() {
        ConformanceFixture fixture = ConformanceFixtures.fullOutage();
        ConformanceLedger ledger = fixture.newLedger();
        PartnerEvent event = fixture.events().get(0);
        assertEquals(Disposition.DEAD_LETTER, ledger.apply(event).disposition());
        assertEquals(3, ledger.attempts(event));
        assertEquals(1, ledger.deadLetters().size());
        assertEquals(event, ledger.deadLetters().get(0));
        assertTrue(ledger.revisions("order-dead").isEmpty());
        assertEquals(-1L, ledger.lastRevision("order-dead"));
    }

    @Test
    void deadLetteredEventIsReprocessableAfterRecovery() {
        ConformanceFixture fixture = ConformanceFixtures.deadLetterRecovery();
        ConformanceLedger ledger = fixture.newLedger();
        PartnerEvent event = fixture.events().get(0);
        assertEquals(Disposition.DEAD_LETTER, ledger.apply(event).disposition());
        assertEquals(3, ledger.attempts(event));
        assertEquals(1, ledger.deadLetters().size());
        assertTrue(ledger.revisions("order-recovered").isEmpty());
        assertEquals(0, ledger.seenCount());

        assertEquals(Disposition.APPLIED, ledger.apply(fixture.events().get(1)).disposition());
        assertEquals(1, ledger.attempts(event));
        assertEquals(1, ledger.revisions("order-recovered").size());
        assertEquals(1, ledger.seenCount());
        assertEquals(1, ledger.deadLetters().size());
        assertEquals(Disposition.DUPLICATE, ledger.apply(event).disposition());
    }

    @Test
    void unmatchedIdentifierWaitsInTheReconciliationQueue() {
        ConformanceFixture fixture = ConformanceFixtures.unmatchedResult();
        ConformanceLedger ledger = fixture.newLedger();
        PartnerEvent event = fixture.events().get(0);
        assertEquals(Disposition.UNMATCHED, ledger.apply(event).disposition());
        assertEquals(1, ledger.reconciliation().size());
        assertEquals(event, ledger.reconciliation().get(0));
        assertTrue(ledger.revisions("order-unmatched").isEmpty());
    }

    @Test
    void knownIdentifierOnWrongAggregateWaitsForReconciliation() {
        ConformanceFixture fixture = ConformanceFixtures.wrongAggregateIdentifier();
        ConformanceLedger ledger = fixture.newLedger();
        PartnerEvent event = fixture.events().get(0);
        assertEquals(Disposition.UNMATCHED, ledger.apply(event).disposition());
        assertEquals(1, ledger.reconciliation().size());
        assertTrue(ledger.revisions("order-wrong").isEmpty());
        assertEquals(0, ledger.attempts(event));
        assertEquals(0, ledger.seenCount());
    }

    @Test
    void dedupNamespaceIsScopedToSenderAndKind() {
        ConformanceLedger ledger = ConformanceLedger.open(Map.of("ns", Set.of("ACC-1001")));
        PartnerEvent first = new PartnerEvent("same", "sender-a", EventKind.RESULT,
                "ns", 1L, "ACC-1001", null, "7.1");
        PartnerEvent otherSender = new PartnerEvent("same", "sender-b", EventKind.RESULT,
                "ns", 2L, "ACC-1001", null, "7.2");
        PartnerEvent otherKind = new PartnerEvent("same", "sender-a", EventKind.CORRECTION,
                "ns", 3L, "ACC-1001", null, "7.3");
        assertEquals(Disposition.APPLIED, ledger.apply(first).disposition());
        assertEquals(Disposition.APPLIED, ledger.apply(otherSender).disposition());
        assertEquals(Disposition.APPLIED, ledger.apply(otherKind).disposition());
        assertEquals(3, ledger.seenCount());
        assertEquals(1, ledger.attempts(first));
        assertEquals(1, ledger.attempts(otherSender));
        assertEquals(1, ledger.attempts(otherKind));
        assertEquals(Disposition.DUPLICATE, ledger.apply(first).disposition());
    }

    @Test
    void outOfOrderRevisionIsHeldWithoutOverwritingTheLatest() {
        ConformanceFixture fixture = ConformanceFixtures.outOfOrderResult();
        ConformanceLedger ledger = fixture.newLedger();
        assertEquals(Disposition.APPLIED, ledger.apply(fixture.events().get(0)).disposition());
        assertEquals(Disposition.OUT_OF_ORDER, ledger.apply(fixture.events().get(1)).disposition());
        assertEquals(1, ledger.revisions("order-stale").size());
        assertEquals(5L, ledger.lastRevision("order-stale"));
    }

    @Test
    void patientReleaseStaysBoundToTheOriginalImmutableRevision() {
        ConformanceFixture fixture = ConformanceFixtures.releaseBoundToRevision();
        ConformanceLedger ledger = fixture.newLedger();
        for (PartnerEvent event : fixture.events()) {
            ledger.apply(event);
        }
        assertEquals(1L, ledger.releasedRevision("order-release"));
        assertEquals(3L, ledger.lastRevision("order-release"));
        assertEquals(3, ledger.revisions("order-release").size());
    }

    @Test
    void specimenRecollectionKeepsBothLinkedRevisions() {
        ConformanceFixture fixture = ConformanceFixtures.specimenRecollection();
        ConformanceLedger ledger = fixture.newLedger();
        for (PartnerEvent event : fixture.events()) {
            assertEquals(Disposition.APPLIED, ledger.apply(event).disposition());
        }
        assertEquals(2, ledger.revisions("requisition-1").size());
        assertEquals("SPEC-3003", ledger.revisions("requisition-1").get(0).identifier());
        assertEquals("SPEC-3004", ledger.revisions("requisition-1").get(1).identifier());
    }

    @Test
    void trendingComparesOnlyNumericSameTestSameUnitObservations() {
        PartnerEvent numericA = observation("obs-a", "TEST-A", "mg/dL", "7.1");
        PartnerEvent numericB = observation("obs-b", "TEST-A", "mg/dL", "7.3");
        PartnerEvent qualified = observation("obs-c", "TEST-A", "mg/dL", "<5");
        PartnerEvent qualitative = observation("obs-d", "TEST-A", "mg/dL", "POSITIVE");
        PartnerEvent otherUnit = observation("obs-e", "TEST-A", "g/L", "7.2");
        PartnerEvent otherTest = observation("obs-f", "TEST-B", "mg/dL", "7.2");
        PartnerEvent noUnit = new PartnerEvent("obs-g", "partner-lab", EventKind.OBSERVATION,
                "obs", 1L, "TEST-A", null, "7.2");
        PartnerEvent notObservation = PartnerEvent.of("obs-h", EventKind.RESULT, "obs", 1L, "TEST-A", "7.2");

        assertTrue(PartnerEvent.comparable(numericA, numericB));
        assertTrue(PartnerEvent.comparable(numericA, numericA));
        assertFalse(PartnerEvent.comparable(numericA, qualified));
        assertFalse(PartnerEvent.comparable(qualified, qualitative));
        assertFalse(PartnerEvent.comparable(numericA, otherUnit));
        assertFalse(PartnerEvent.comparable(numericA, otherTest));
        assertFalse(PartnerEvent.comparable(numericA, noUnit));
        assertFalse(PartnerEvent.comparable(numericA, notObservation));
        assertFalse(PartnerEvent.comparable(notObservation, numericA));
        assertFalse(PartnerEvent.comparable(noUnit, numericA));

        List<PartnerEvent> fixtureEvents = ConformanceFixtures.trendingComparability().events();
        assertTrue(PartnerEvent.comparable(fixtureEvents.get(0), fixtureEvents.get(1)));
        assertFalse(PartnerEvent.comparable(fixtureEvents.get(0), fixtureEvents.get(2)));
    }

    @Test
    void eventKindsRoundTripAndExposeTheirConformanceRules() {
        for (EventKind kind : EventKind.values()) {
            assertEquals(kind, EventKind.from(kind.wire()));
        }
        assertEquals("transmission_ack", EventKind.TRANSMISSION_ACK.wire());
        assertThrows(IllegalArgumentException.class, () -> EventKind.from("missing"));
        assertTrue(EventKind.RESULT.requiresIdentifierMatch());
        assertTrue(EventKind.OBSERVATION.requiresIdentifierMatch());
        assertFalse(EventKind.ORDER.requiresIdentifierMatch());
        assertFalse(EventKind.CLINICAL_ACK.requiresIdentifierMatch());
        assertTrue(EventKind.RESULT.opensReview());
        assertTrue(EventKind.CORRECTION.opensReview());
        assertFalse(EventKind.TRANSMISSION_ACK.opensReview());
        assertTrue(EventKind.CORRECTION.reopensReview());
        assertFalse(EventKind.RESULT.reopensReview());
        assertTrue(EventKind.CLINICAL_ACK.isClinicalAck());
        assertFalse(EventKind.TRANSMISSION_ACK.isClinicalAck());
    }

    @Test
    void dispositionsRoundTripAndRejectNullInputs() {
        for (Disposition disposition : Disposition.values()) {
            assertEquals(disposition, Disposition.from(disposition.wire()));
        }
        assertEquals(5, Disposition.values().length);
        assertEquals("dead_letter", Disposition.DEAD_LETTER.wire());
        assertThrows(NullPointerException.class, () -> PartnerEvent.of(
                null, EventKind.ORDER, "a", 1L, "acc", "payload"));
        assertThrows(NullPointerException.class, () -> new PartnerEvent(
                "e", null, EventKind.ORDER, "a", 1L, "acc", null, "payload"));
        assertThrows(NullPointerException.class, () -> PartnerEvent.of(
                "e", EventKind.ORDER, "a", 1L, "acc", null));
        assertNull(PartnerEvent.of("e", EventKind.ORDER, "a", 1L, "acc", "p").unit());
        assertNotEquals(Disposition.APPLIED, Disposition.DUPLICATE);
    }

    @Test
    void ledgerFailsClosedOnInvalidConfigurationAndRecordsEmptyState() {
        assertThrows(IllegalArgumentException.class, () -> new ConformanceLedger(
                Map.of(), event -> true, 0));
        assertThrows(NullPointerException.class, () -> new ConformanceLedger(
                null, event -> true, 3));
        assertThrows(NullPointerException.class, () -> new ConformanceLedger(
                Map.of(), null, 3));
        ConformanceLedger ledger = ConformanceLedger.open(Map.of());
        PartnerEvent unknown = PartnerEvent.of("none", EventKind.RESULT, "none", 1L, "ANY", "p");
        assertEquals(Disposition.UNMATCHED, ledger.apply(unknown).disposition());
        assertEquals(1, ledger.reconciliation().size());
        assertTrue(ledger.revisions("none").isEmpty());
        assertFalse(ledger.reviewOpen("none"));
        assertEquals(0, ledger.attempts(unknown));
        assertNull(ledger.releasedRevision("none"));
        assertTrue(ledger.deadLetters().isEmpty());
    }

    private static PartnerEvent observation(String id, String test, String unit, String payload) {
        return new PartnerEvent(id, "partner-lab", EventKind.OBSERVATION, "obs", 1L, test, unit, payload);
    }
}
