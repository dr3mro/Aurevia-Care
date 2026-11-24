package com.aurevia.exchange.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests the immutable final report snapshot derived from a conformance ledger.
 */
class FinalReportTest {

    @Test
    void emptyLedgerProducesAnEmptyReport() {
        ConformanceLedger ledger = ConformanceLedger.open(Map.of("order-1", Set.of("ACC-1001")));
        FinalReport report = FinalReport.of(ledger, "order-1");
        assertEquals("order-1", report.aggregateKey());
        assertEquals(-1L, report.lastRevision());
        assertEquals(0, report.revisionCount());
        assertNull(report.latestPayload());
        assertFalse(report.reviewOpen());
        assertNull(report.releasedVersion());
        assertEquals(0, report.reconciliationCount());
        assertEquals(0, report.deadLetterCount());
    }

    @Test
    void appliedEventsProduceTheLatestRevisionAndOutcomeCounts() {
        ConformanceFixture fixture = ConformanceFixtures.correctionReopensReview();
        ConformanceLedger ledger = fixture.newLedger();
        for (PartnerEvent event : fixture.events()) {
            ledger.apply(event);
        }
        FinalReport report = FinalReport.of(ledger, "order-correction");
        assertEquals(3L, report.lastRevision());
        assertEquals(3, report.revisionCount());
        assertEquals("7.4 mg/dL", report.latestPayload());
        assertTrue(report.reviewOpen());
    }

    @Test
    void reconciliationAndDeadLetterQueuesAreCounted() {
        ConformanceLedger ledger = ConformanceLedger.open(Map.of());
        ledger.apply(PartnerEvent.of("u", EventKind.RESULT, "order-u", 1L, "NOPE", "p"));
        FinalReport report = FinalReport.of(ledger, "order-u");
        assertEquals(1, report.reconciliationCount());
        assertEquals(0, report.deadLetterCount());
    }
}
