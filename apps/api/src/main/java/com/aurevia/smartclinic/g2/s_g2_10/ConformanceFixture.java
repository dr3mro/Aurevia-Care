package com.aurevia.smartclinic.g2.s_g2_10;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One named, synthetic partner conformance scenario.
 *
 * <p>A fixture is data plus the expected processing outcome for every event and
 * a factory for a fresh receiver. It carries no patient data and no credentials;
 * all identifiers and payloads are invented for conformance rehearsal.</p>
 *
 * @param id                  stable scenario identifier
 * @param requirements        requirement IDs exercised by the scenario
 * @param description         short description of the conformance property
 * @param events              ordered synthetic partner events to replay
 * @param expectedDispositions expected outcome of each replayed event
 * @param ledger              factory for the scenario's configured receiver
 */
public record ConformanceFixture(String id, String requirements, String description,
                                 List<PartnerEvent> events, List<Disposition> expectedDispositions,
                                 Supplier<ConformanceLedger> ledger) {

    /**
     * Defensive store of the event and disposition lists.
     */
    public ConformanceFixture {
        events = List.copyOf(events);
        expectedDispositions = List.copyOf(expectedDispositions);
        Objects.requireNonNull(ledger, "ledger");
    }

    /**
     * Immutable replay sequence.
     *
     * @return the events in replay order
     */
    @Override
    public List<PartnerEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Immutable expected outcome sequence.
     *
     * @return the expected dispositions in replay order
     */
    @Override
    public List<Disposition> expectedDispositions() {
        return List.copyOf(expectedDispositions);
    }

    /**
     * Create a fresh receiver configured for this scenario.
     *
     * @return an empty ledger with the scenario's identifiers and delivery gate
     */
    public ConformanceLedger newLedger() {
        return ledger.get();
    }
}
