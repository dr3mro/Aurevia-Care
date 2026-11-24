package com.aurevia.exchange.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entry point that replays synthetic partner fixture events into a conformance
 * ledger and exposes their observable final report state.
 *
 * <p>Events may be applied directly or carried through a FHIR or HL7 wire
 * adapter. A replay requires an authorized reason and keeps the immutable event
 * identity. Dedup in the sender and type namespace, stale revision holding and
 * processing-outcome recording are delegated to the conformance ledger so the
 * rules are defined once (ARC-05, XCH-04).</p>
 */
public final class PartnerReportProcessor {

    private final ConformanceLedger ledger;
    private final List<ReplayRequest> replays = new ArrayList<>();

    /**
     * Create a processor over a fresh conformance ledger.
     *
     * @param ledger ledger that owns the durable conformance rules
     */
    public PartnerReportProcessor(ConformanceLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /**
     * Create a processor with the ledger a named fixture declares.
     *
     * @param fixture synthetic conformance fixture
     * @return processor over a fresh fixture ledger
     */
    public static PartnerReportProcessor forFixture(ConformanceFixture fixture) {
        return new PartnerReportProcessor(fixture.newLedger());
    }

    /**
     * Apply one event directly and record its processing outcome.
     *
     * @param event event to apply
     * @return recorded disposition and reason
     */
    public ProcessingResult apply(PartnerEvent event) {
        return ledger.apply(event);
    }

    /**
     * Replay an event with an authorized reason, preserving its immutable identity.
     *
     * @param event   event to replay
     * @param request authorization and reason for the replay
     * @return recorded disposition and reason
     */
    public ProcessingResult replay(PartnerEvent event, ReplayRequest request) {
        authorizeReplay(event, request);
        return apply(event);
    }

    /**
     * Submit one event through a wire adapter, then apply the decoded event.
     *
     * @param event   event to carry across the wire
     * @param adapter wire adapter to use
     * @return recorded disposition and reason
     */
    public ProcessingResult submit(PartnerEvent event, PartnerEventAdapter adapter) {
        return apply(adapter.roundTrip(event));
    }

    /**
     * Replay an authorized event through a wire adapter without changing its id.
     *
     * @param event   event to replay
     * @param adapter wire adapter to use
     * @param request authorization and reason for the replay
     * @return recorded disposition and reason
     */
    public ProcessingResult replayThrough(PartnerEvent event, PartnerEventAdapter adapter,
                                          ReplayRequest request) {
        authorizeReplay(event, request);
        return submit(event, adapter);
    }

    /**
     * Snapshot of the final report state of one aggregate.
     *
     * @param aggregateKey local aggregate key
     * @return immutable final report
     */
    public FinalReport finalReport(String aggregateKey) {
        return FinalReport.of(ledger, aggregateKey);
    }

    /**
     * Authorized replay requests recorded in order.
     *
     * @return immutable replay log
     */
    public List<ReplayRequest> replays() {
        return List.copyOf(replays);
    }

    private void authorizeReplay(PartnerEvent event, ReplayRequest request) {
        if (!request.matches(event)) {
            throw new IllegalArgumentException("replay event id does not match the request");
        }
        replays.add(request);
    }
}
