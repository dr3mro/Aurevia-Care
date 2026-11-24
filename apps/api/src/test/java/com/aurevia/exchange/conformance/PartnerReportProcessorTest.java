package com.aurevia.exchange.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Entry-point tests for the synthetic partner report pipeline. Every fixture is
 * submitted through the FHIR and HL7 wire adapters and the resulting final report
 * state is compared. All data is invented for conformance rehearsal.
 */
class PartnerReportProcessorTest {

    private static final PartnerEventAdapter FHIR = new FhirPartnerEventAdapter();
    private static final PartnerEventAdapter HL7 = new Hl7PartnerEventAdapter();

    private static List<ConformanceFixture> everyFixture() {
        List<ConformanceFixture> fixtures = new ArrayList<>(ConformanceFixtures.all());
        fixtures.add(ConformanceFixtures.replayWithReason());
        return fixtures;
    }

    @Test
    void everyFixtureReachesTheSameFinalReportThroughFhirAndHl7() {
        for (ConformanceFixture fixture : everyFixture()) {
            PartnerReportProcessor fhir = PartnerReportProcessor.forFixture(fixture);
            PartnerReportProcessor hl7 = PartnerReportProcessor.forFixture(fixture);
            List<Disposition> fhirOutcomes = new ArrayList<>();
            List<Disposition> hl7Outcomes = new ArrayList<>();
            for (PartnerEvent event : fixture.events()) {
                fhirOutcomes.add(fhir.submit(event, FHIR).disposition());
                hl7Outcomes.add(hl7.submit(event, HL7).disposition());
            }
            assertEquals(fixture.expectedDispositions(), fhirOutcomes, fixture.id());
            assertEquals(fhirOutcomes, hl7Outcomes, fixture.id());

            Set<String> aggregates = new LinkedHashSet<>();
            for (PartnerEvent event : fixture.events()) {
                aggregates.add(event.aggregateKey());
            }
            for (String aggregate : aggregates) {
                assertEquals(fhir.finalReport(aggregate), hl7.finalReport(aggregate),
                        fixture.id() + " " + aggregate);
            }
        }
    }

    @Test
    void finalReportIsNeverOverwrittenByDuplicateOrOlderRevision() {
        ConformanceFixture duplicate = ConformanceFixtures.duplicateDelivery();
        PartnerReportProcessor processor = PartnerReportProcessor.forFixture(duplicate);
        assertEquals(Disposition.APPLIED, processor.submit(duplicate.events().get(0), FHIR).disposition());
        FinalReport afterFirst = processor.finalReport("order-dup");
        assertEquals(Disposition.DUPLICATE, processor.submit(duplicate.events().get(1), HL7).disposition());
        assertEquals(afterFirst, processor.finalReport("order-dup"));
        assertEquals(1, processor.finalReport("order-dup").revisionCount());

        ConformanceFixture stale = ConformanceFixtures.outOfOrderResult();
        PartnerReportProcessor reordered = PartnerReportProcessor.forFixture(stale);
        assertEquals(Disposition.APPLIED, reordered.submit(stale.events().get(0), FHIR).disposition());
        FinalReport afterLatest = reordered.finalReport("order-stale");
        assertEquals(Disposition.OUT_OF_ORDER, reordered.submit(stale.events().get(1), HL7).disposition());
        assertEquals(afterLatest, reordered.finalReport("order-stale"));
        assertEquals(5L, reordered.finalReport("order-stale").lastRevision());
        assertEquals("7.1 mg/dL", reordered.finalReport("order-stale").latestPayload());
    }

    @Test
    void unmatchedReportWaitsInTheReconciliationQueue() {
        ConformanceFixture fixture = ConformanceFixtures.unmatchedResult();
        PartnerReportProcessor processor = PartnerReportProcessor.forFixture(fixture);
        assertEquals(Disposition.UNMATCHED, processor.submit(fixture.events().get(0), FHIR).disposition());
        FinalReport report = processor.finalReport("order-unmatched");
        assertEquals(1, report.reconciliationCount());
        assertEquals(0, report.revisionCount());
        assertEquals(-1L, report.lastRevision());
    }

    @Test
    void authorizedReplayKeepsTheEventIdAndRecordsTheReason() {
        ConformanceFixture fixture = ConformanceFixtures.replayWithReason();
        assertEquals(fixture.expectedDispositions(),
                List.of(Disposition.DEAD_LETTER, Disposition.APPLIED));
        PartnerEvent event = fixture.events().get(0);
        PartnerEvent replayed = fixture.events().get(1);
        assertEquals(event, replayed);
        PartnerReportProcessor processor = PartnerReportProcessor.forFixture(fixture);
        assertEquals(Disposition.DEAD_LETTER, processor.submit(event, FHIR).disposition());
        ReplayRequest request = new ReplayRequest(event.eventId(), event.sender(), event.kind(),
                "partner outage cleared", "operator-synthetic");
        assertEquals(Disposition.APPLIED,
                processor.replayThrough(replayed, HL7, request).disposition());
        assertEquals(List.of(request), processor.replays());
        assertEquals(event.eventId(), request.eventId());
        assertEquals(1, processor.finalReport("order-replay").revisionCount());

        ReplayRequest wrong = new ReplayRequest("another-id", event.sender(), event.kind(),
                "typo", "operator-synthetic");
        assertThrows(IllegalArgumentException.class,
                () -> processor.replayThrough(replayed, FHIR, wrong));
        assertEquals(1, processor.replays().size());
    }

    @Test
    void processorRequiresALedger() {
        assertThrows(NullPointerException.class, () -> new PartnerReportProcessor(null));
    }

    @Test
    void directApplyAndReplayDelegateWithoutAWireAdapter() {
        ConformanceFixture fixture = ConformanceFixtures.partialOutage();
        PartnerReportProcessor processor = PartnerReportProcessor.forFixture(fixture);
        PartnerEvent event = fixture.events().get(0);
        assertEquals(Disposition.APPLIED, processor.apply(event).disposition());
        ReplayRequest request = new ReplayRequest(event.eventId(), event.sender(), event.kind(),
                "duplicate suppression check", "operator-synthetic");
        assertEquals(Disposition.DUPLICATE, processor.replay(event, request).disposition());
        assertEquals(1, processor.finalReport("order-outage").revisionCount());
    }
}
