package com.aurevia.exchange.conformance;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Catalog of synthetic partner conformance fixtures.
 *
 * <p>The scenarios cover released orders, transport and clinical
 * acknowledgments, specimen collection, reported and corrected results,
 * critical read-back, patient release, observation comparability and the three
 * delivery hazards the gates require: duplicates, corrections and partial
 * outages (XCH-01 to XCH-07, LAB-01, LAB-02, ARC-05). Every scenario is
 * deterministic and replayable without a live partner, database or credential.
 * Identifier bindings are issuer-scoped and type-qualified by prefix, and are
 * scoped to the local aggregate so a valid identifier on the wrong order is
 * reconciled rather than linked.</p>
 */
public final class ConformanceFixtures {

    private static final Map<String, Set<String>> BINDINGS = Map.ofEntries(
            Map.entry("order-1", Set.of("ACC-1001")),
            Map.entry("order-dup", Set.of("ACC-1001")),
            Map.entry("order-correction", Set.of("ACC-1001")),
            Map.entry("order-critical", Set.of("ACC-1001")),
            Map.entry("order-outage", Set.of("ACC-1001")),
            Map.entry("order-dead", Set.of("ACC-1001")),
            Map.entry("order-unmatched", Set.of("ACC-1001")),
            Map.entry("order-stale", Set.of("ACC-1001")),
            Map.entry("order-replay", Set.of("ACC-1001")),
            Map.entry("order-release", Set.of("ACC-1001")),
            Map.entry("order-recovered", Set.of("ACC-1001")),
            Map.entry("order-wrong", Set.of("ACC-1001")),
            Map.entry("requisition-1", Set.of("SPEC-3003", "SPEC-3004")),
            Map.entry("obs", Set.of("TEST-A")));

    private static final List<ConformanceFixture> ALL = List.of(
            happyOrderToResult(),
            duplicateDelivery(),
            correctionReopensReview(),
            criticalRequiresClinicalAck(),
            partialOutage(),
            fullOutage(),
            deadLetterRecovery(),
            unmatchedResult(),
            wrongAggregateIdentifier(),
            outOfOrderResult(),
            releaseBoundToRevision(),
            specimenRecollection(),
            trendingComparability());

    private ConformanceFixtures() {
    }

    /**
     * All named synthetic scenarios.
     *
     * @return immutable fixture catalog
     */
    public static List<ConformanceFixture> all() {
        return ALL;
    }

    /**
     * Look up a scenario by its stable identifier.
     *
     * @param id stable scenario identifier
     * @return the matching fixture, or empty when the id is unknown
     */
    public static Optional<ConformanceFixture> byId(String id) {
        return ALL.stream().filter(fixture -> fixture.id().equals(id)).findFirst();
    }

    /**
     * XCH-01 to XCH-04 and LAB-01 to LAB-02: a released order travels through
     * acceptance, a transport acknowledgment, collection, a reported result and
     * a clinical acknowledgment.
     *
     * @return the happy-path fixture
     */
    public static ConformanceFixture happyOrderToResult() {
        return fixture("F01", "XCH-01, XCH-02, XCH-03, XCH-04, LAB-01, LAB-02",
                "order to accepted result with a human close",
                () -> ConformanceLedger.open(BINDINGS),
                allApplied(6),
                PartnerEvent.of("o1", EventKind.ORDER, "order-1", 1L, "ACC-1001", "released"),
                PartnerEvent.of("o2", EventKind.ACCEPT, "order-1", 2L, "ACC-1001", "accepted"),
                PartnerEvent.of("o3", EventKind.TRANSMISSION_ACK, "order-1", 3L, "ACC-1001", "sent"),
                PartnerEvent.of("o4", EventKind.SPECIMEN, "order-1", 4L, "ACC-1001", "collected"),
                PartnerEvent.of("o5", EventKind.RESULT, "order-1", 5L, "ACC-1001", "7.1 mg/dL"),
                PartnerEvent.of("o6", EventKind.CLINICAL_ACK, "order-1", 6L, "ACC-1001", "ack"));
    }

    /**
     * ARC-05 and XCH-01: replaying an immutable event identifier produces a
     * single downstream effect.
     *
     * @return duplicate-delivery fixture
     */
    public static ConformanceFixture duplicateDelivery() {
        PartnerEvent event = PartnerEvent.of("dup-1", EventKind.ORDER, "order-dup", 1L,
                "ACC-1001", "released");
        return fixture("F02", "XCH-01, ARC-05",
                "duplicate event id is deduplicated in the sender and type namespace",
                () -> ConformanceLedger.open(BINDINGS),
                List.of(Disposition.APPLIED, Disposition.DUPLICATE), event, event);
    }

    /**
     * XCH-04 and LAB-02: a correction appends a new revision and reopens a
     * review that a clinical acknowledgment had closed.
     *
     * @return correction fixture
     */
    public static ConformanceFixture correctionReopensReview() {
        return fixture("F03", "XCH-04, LAB-02",
                "correction preserves the original revision and reopens review",
                () -> ConformanceLedger.open(BINDINGS),
                allApplied(3),
                PartnerEvent.of("c1", EventKind.RESULT, "order-correction", 1L,
                        "ACC-1001", "7.1 mg/dL"),
                PartnerEvent.of("c2", EventKind.CLINICAL_ACK, "order-correction", 2L,
                        "ACC-1001", "ack"),
                PartnerEvent.of("c3", EventKind.CORRECTION, "order-correction", 3L,
                        "ACC-1001", "7.4 mg/dL"));
    }

    /**
     * XCH-02 and XCH-05: a transport acknowledgment cannot close a critical
     * review; the named owner's clinical read-back can. Deadline and escalation
     * attempt evidence is delivered by S-G2-05; this fixture proves only the
     * acknowledgment distinction.
     *
     * @return critical-result fixture
     */
    public static ConformanceFixture criticalRequiresClinicalAck() {
        return fixture("F04", "XCH-02, XCH-05 (acknowledgment distinction)",
                "critical result needs a human acknowledgment, not a delivery acknowledgment",
                () -> ConformanceLedger.open(BINDINGS),
                allApplied(3),
                PartnerEvent.of("k1", EventKind.CRITICAL, "order-critical", 1L,
                        "ACC-1001", "panic value"),
                PartnerEvent.of("k2", EventKind.TRANSMISSION_ACK, "order-critical", 2L,
                        "ACC-1001", "delivered"),
                PartnerEvent.of("k3", EventKind.CLINICAL_ACK, "order-critical", 3L,
                        "ACC-1001", "read-back"));
    }

    /**
     * ARC-05: a partial outage fails the first two deliveries and succeeds on
     * the third with one applied revision.
     *
     * @return partial-outage fixture
     */
    public static ConformanceFixture partialOutage() {
        Supplier<ConformanceLedger> ledger = () -> {
            AtomicInteger calls = new AtomicInteger();
            Predicate<PartnerEvent> recovers = event -> calls.incrementAndGet() > 2;
            return new ConformanceLedger(BINDINGS, recovers, 4);
        };
        return fixture("F05", "ARC-05, XCH-04",
                "partial partner outage retries with bounded backoff then applies once",
                ledger, allApplied(1),
                PartnerEvent.of("p1", EventKind.RESULT, "order-outage", 1L,
                        "ACC-1001", "7.1 mg/dL"));
    }

    /**
     * ARC-05: a sustained outage exhausts the retry budget and dead-letters the
     * event without creating an effect.
     *
     * @return dead-letter fixture
     */
    public static ConformanceFixture fullOutage() {
        return fixture("F06", "ARC-05",
                "sustained outage dead-letters the event after the bounded retry budget",
                () -> new ConformanceLedger(BINDINGS, event -> false, 3),
                List.of(Disposition.DEAD_LETTER),
                PartnerEvent.of("d1", EventKind.RESULT, "order-dead", 1L,
                        "ACC-1001", "7.1 mg/dL"));
    }

    /**
     * ARC-05 and XCH-04: an event that dead-lettered during an outage is
     * reprocessable when the partner recovers; the immutable id is not
     * consumed by the failed delivery.
     *
     * @return dead-letter recovery fixture
     */
    public static ConformanceFixture deadLetterRecovery() {
        Supplier<ConformanceLedger> ledger = () -> {
            AtomicInteger calls = new AtomicInteger();
            Predicate<PartnerEvent> recoversAfterOutage = event -> calls.incrementAndGet() > 3;
            return new ConformanceLedger(BINDINGS, recoversAfterOutage, 3);
        };
        PartnerEvent event = PartnerEvent.of("rec-1", EventKind.RESULT, "order-recovered", 1L,
                "ACC-1001", "7.1 mg/dL");
        return fixture("F07", "ARC-05, XCH-04",
                "dead-lettered event replays to one applied effect after the outage clears",
                ledger, List.of(Disposition.DEAD_LETTER, Disposition.APPLIED), event, event);
    }

    /**
     * XCH-04 and LAB-01: a result with an unmatched identifier waits in the
     * reconciliation queue instead of linking to the wrong patient.
     *
     * @return reconciliation fixture
     */
    public static ConformanceFixture unmatchedResult() {
        return fixture("F08", "XCH-04, LAB-01",
                "unmatched accession waits for reconciliation",
                () -> ConformanceLedger.open(BINDINGS),
                List.of(Disposition.UNMATCHED),
                PartnerEvent.of("u1", EventKind.RESULT, "order-unmatched", 1L,
                        "ACC-UNKNOWN", "7.1 mg/dL"));
    }

    /**
     * XCH-04 and LAB-01: an issuer-scoped identifier that is valid elsewhere
     * but not bound to this aggregate is a conflict, not a match.
     *
     * @return wrong-aggregate identifier fixture
     */
    public static ConformanceFixture wrongAggregateIdentifier() {
        return fixture("F09", "XCH-04, LAB-01",
                "known identifier on the wrong aggregate waits for reconciliation",
                () -> ConformanceLedger.open(BINDINGS),
                List.of(Disposition.UNMATCHED),
                PartnerEvent.of("w1", EventKind.RESULT, "order-wrong", 1L,
                        "SPEC-3004", "7.1 mg/dL"));
    }

    /**
     * ARC-05 and XCH-04: a reordered event is held without overwriting a newer
     * applied revision.
     *
     * @return out-of-order fixture
     */
    public static ConformanceFixture outOfOrderResult() {
        return fixture("F10", "ARC-05, XCH-04",
                "reordered delivery is held and cannot overwrite the latest revision",
                () -> ConformanceLedger.open(BINDINGS),
                List.of(Disposition.APPLIED, Disposition.OUT_OF_ORDER),
                PartnerEvent.of("s1", EventKind.RESULT, "order-stale", 5L,
                        "ACC-1001", "7.1 mg/dL"),
                PartnerEvent.of("s2", EventKind.RESULT, "order-stale", 4L,
                        "ACC-1001", "7.0 mg/dL"));
    }

    /**
     * ARC-05 and XCH-04: an event that dead-lettered during a partial outage is
     * replayed with an authorized reason and keeps its immutable event identity.
     *
     * @return replay-with-reason fixture
     */
    public static ConformanceFixture replayWithReason() {
        Supplier<ConformanceLedger> ledger = () -> {
            AtomicInteger calls = new AtomicInteger();
            Predicate<PartnerEvent> recoversAfterOutage = event -> calls.incrementAndGet() > 3;
            return new ConformanceLedger(BINDINGS, recoversAfterOutage, 3);
        };
        PartnerEvent event = PartnerEvent.of("rep-1", EventKind.RESULT, "order-replay", 1L,
                "ACC-1001", "7.1 mg/dL");
        return fixture("F14", "ARC-05, XCH-04",
                "authorized replay with a reason keeps the event id after an outage",
                ledger, List.of(Disposition.DEAD_LETTER, Disposition.APPLIED), event, event);
    }

    /**
     * XCH-06: patient release is bound to the immutable original revision, so
     * a later correction cannot silently change what the patient can obtain.
     *
     * @return release fixture
     */
    public static ConformanceFixture releaseBoundToRevision() {
        return fixture("F11", "XCH-06",
                "patient release references an immutable revision across later corrections",
                () -> ConformanceLedger.open(BINDINGS),
                allApplied(3),
                PartnerEvent.of("r1", EventKind.RESULT, "order-release", 1L,
                        "ACC-1001", "7.1 mg/dL"),
                PartnerEvent.of("r2", EventKind.RELEASE, "order-release", 2L,
                        "ACC-1001", "1"),
                PartnerEvent.of("r3", EventKind.CORRECTION, "order-release", 3L,
                        "ACC-1001", "7.4 mg/dL"));
    }

    /**
     * LAB-01: a recollected specimen keeps a linked revision and its own
     * issuer-scoped identifier instead of overwriting the rejected specimen.
     *
     * @return specimen recollection fixture
     */
    public static ConformanceFixture specimenRecollection() {
        return fixture("F12", "LAB-01",
                "rejected specimen and linked recollection stay distinguishable",
                () -> ConformanceLedger.open(BINDINGS),
                allApplied(2),
                PartnerEvent.of("g1", EventKind.SPECIMEN, "requisition-1", 1L,
                        "SPEC-3003", "rejected: hemolysed"),
                PartnerEvent.of("g2", EventKind.SPECIMEN, "requisition-1", 2L,
                        "SPEC-3004", "recollected"));
    }

    /**
     * XCH-07 and LAB-02: numeric observations share a test and unit while
     * qualified and differently typed observations stay visible but incomparable.
     *
     * @return trending fixture
     */
    public static ConformanceFixture trendingComparability() {
        return fixture("F13", "XCH-07, LAB-02",
                "only same-test, same-unit numeric observations are trendable",
                () -> ConformanceLedger.open(BINDINGS),
                allApplied(3),
                new PartnerEvent("t1", "partner-lab", EventKind.OBSERVATION, "obs", 1L,
                        "TEST-A", "mg/dL", "7.1"),
                new PartnerEvent("t2", "partner-lab", EventKind.OBSERVATION, "obs", 2L,
                        "TEST-A", "mg/dL", "7.3"),
                new PartnerEvent("t3", "partner-lab", EventKind.OBSERVATION, "obs", 3L,
                        "TEST-A", "g/L", "7.2"));
    }

    private static ConformanceFixture fixture(String id, String requirements, String description,
                                              Supplier<ConformanceLedger> ledger,
                                              List<Disposition> expected, PartnerEvent... events) {
        return new ConformanceFixture(id, requirements, description, List.of(events), expected, ledger);
    }

    private static List<Disposition> allApplied(int count) {
        return java.util.Collections.nCopies(count, Disposition.APPLIED);
    }
}
