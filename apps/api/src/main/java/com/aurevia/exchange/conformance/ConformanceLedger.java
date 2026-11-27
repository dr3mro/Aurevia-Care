package com.aurevia.exchange.conformance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Deterministic, in-memory receiver used by the synthetic partner conformance
 * fixtures. It reproduces the delivery rules a real consumer must satisfy:
 * deduplication in the sender and type namespace, per-aggregate version
 * ordering, identifier matching against the aggregate's trusted local identity,
 * bounded retry of transient outages, dead-lettering of exhausted events and
 * immutable revision history with review reopening.
 *
 * <p>Only a successfully applied event consumes its identity. An unmatched,
 * stale or dead-lettered event stays reprocessable after reconciliation,
 * recovery or a state correction, so a replay preserves event identity without
 * being silently discarded as a duplicate. No database or external partner is
 * involved; each ledger instance is private to one fixture run.</p>
 */
public final class ConformanceLedger {

    private final Map<String, Set<String>> identifierBindings;
    private final Predicate<PartnerEvent> deliveryGate;
    private final int maxAttempts;

    private final Set<String> seen = new HashSet<>();
    private final Map<String, Long> appliedRevision = new HashMap<>();
    private final Map<String, List<RevisionRecord>> history = new HashMap<>();
    private final Map<String, Boolean> reviewOpen = new HashMap<>();
    private final Map<String, Long> releasedRevision = new HashMap<>();
    private final Map<String, Integer> attempts = new HashMap<>();
    private final List<PartnerEvent> reconciliationQueue = new ArrayList<>();
    private final List<PartnerEvent> deadLetters = new ArrayList<>();

    /**
     * Create a ledger with explicit identity bindings, delivery gate and retry budget.
     *
     * @param identifierBindings aggregate key to issuer-scoped identifiers that
     *                           may legitimately link to that aggregate
     * @param deliveryGate       returns {@code true} when one delivery succeeds
     * @param maxAttempts        bounded retry budget, at least one
     */
    public ConformanceLedger(Map<String, Set<String>> identifierBindings,
                             Predicate<PartnerEvent> deliveryGate, int maxAttempts) {
        Objects.requireNonNull(identifierBindings, "identifierBindings");
        Map<String, Set<String>> copied = new HashMap<>();
        identifierBindings.forEach((key, ids) -> copied.put(key, Set.copyOf(ids)));
        this.identifierBindings = Map.copyOf(copied);
        this.deliveryGate = Objects.requireNonNull(deliveryGate, "deliveryGate");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * Open a ledger with no injected outage and the standard retry budget.
     *
     * @param identifierBindings aggregate key to issuer-scoped identifiers
     * @return an empty ledger
     */
    public static ConformanceLedger open(Map<String, Set<String>> identifierBindings) {
        return new ConformanceLedger(identifierBindings, event -> true, 3);
    }

    /**
     * Apply one partner event under the conformance rules.
     *
     * @param event immutable partner event
     * @return recorded disposition for the fixture to assert
     */
    public ProcessingResult apply(PartnerEvent event) {
        // Identity is scoped to sender, kind and event id; two partners may reuse an id.
        String identity = dedupKey(event);
        if (seen.contains(identity)) {
            return new ProcessingResult(Disposition.DUPLICATE, "duplicate");
        }
        if (event.kind().requiresIdentifierMatch() && !matchesLocalIdentity(event)) {
            // Hold the event for reconciliation; never link clinical data on an
            // identifier that is not bound to this aggregate.
            reconciliationQueue.add(event);
            return new ProcessingResult(Disposition.UNMATCHED, "identifier");
        }
        Long current = appliedRevision.get(event.aggregateKey());
        if (current != null && event.revision() <= current) {
            return new ProcessingResult(Disposition.OUT_OF_ORDER, "stale");
        }

        int attemptsMade = 0;
        boolean delivered = false;
        while (attemptsMade < maxAttempts) {
            attemptsMade++;
            if (deliveryGate.test(event)) {
                delivered = true;
                break;
            }
        }
        attempts.put(identity, attemptsMade);
        if (!delivered) {
            // Keep the identity unconsumed so a replay after recovery can still apply.
            deadLetters.add(event);
            return new ProcessingResult(Disposition.DEAD_LETTER, "outage");
        }

        // Only a committed effect consumes the immutable event identity.
        seen.add(identity);
        appliedRevision.put(event.aggregateKey(), event.revision());
        history.computeIfAbsent(event.aggregateKey(), key -> new ArrayList<>())
                .add(new RevisionRecord(event.revision(), event.kind(), event.identifier(),
                        event.payload(), event.kind().reopensReview()));
        // Only a clinical acknowledgment closes review; a delivery ack or release
        // never does, so a transport fact cannot silently close a clinical task.
        if (event.kind().opensReview()) {
            reviewOpen.put(event.aggregateKey(), true);
        } else if (event.kind().isClinicalAck()) {
            reviewOpen.put(event.aggregateKey(), false);
        } else if (event.kind() == EventKind.RELEASE) {
            releasedRevision.put(event.aggregateKey(), Long.parseLong(event.payload()));
        }
        return new ProcessingResult(Disposition.APPLIED, "delivered");
    }

    /**
     * All preserved revisions of one aggregate, oldest first.
     *
     * @param aggregateKey local aggregate key
     * @return immutable revision history
     */
    public List<RevisionRecord> revisions(String aggregateKey) {
        return List.copyOf(history.getOrDefault(aggregateKey, List.of()));
    }

    /**
     * Latest applied per-aggregate version.
     *
     * @param aggregateKey local aggregate key
     * @return latest revision, or -1 when the aggregate is unknown
     */
    public long lastRevision(String aggregateKey) {
        return appliedRevision.getOrDefault(aggregateKey, -1L);
    }

    /**
     * Whether an aggregate currently has an open clinical review.
     *
     * @param aggregateKey local aggregate key
     * @return {@code true} when a clinical review is open
     */
    public boolean reviewOpen(String aggregateKey) {
        return reviewOpen.getOrDefault(aggregateKey, Boolean.FALSE);
    }

    /**
     * Delivery attempts recorded for the latest apply of an event identity.
     *
     * @param event event whose sender, kind and identifier define the namespace
     * @return attempt count, zero when the event was never delivered
     */
    public int attempts(PartnerEvent event) {
        return attempts.getOrDefault(dedupKey(event), 0);
    }

    /**
     * Events held for identifier reconciliation.
     *
     * @return immutable reconciliation queue snapshot
     */
    public List<PartnerEvent> reconciliation() {
        return List.copyOf(reconciliationQueue);
    }

    /**
     * Events whose retry budget was exhausted. The record is retained for the
     * dead-letter review workflow even if a later replay succeeds.
     *
     * @return immutable dead-letter snapshot
     */
    public List<PartnerEvent> deadLetters() {
        return List.copyOf(deadLetters);
    }

    /**
     * Number of distinct event identities that produced an effect.
     *
     * @return applied event count
     */
    public int seenCount() {
        return seen.size();
    }

    /**
     * Immutable revision a patient release is bound to.
     *
     * @param aggregateKey local aggregate key
     * @return released revision, or {@code null} when no release was applied
     */
    public Long releasedRevision(String aggregateKey) {
        return releasedRevision.get(aggregateKey);
    }

    private boolean matchesLocalIdentity(PartnerEvent event) {
        Set<String> allowed = identifierBindings.get(event.aggregateKey());
        return allowed != null && allowed.contains(event.identifier());
    }

    private static String dedupKey(PartnerEvent event) {
        return event.sender() + "|" + event.kind().wire() + "|" + event.eventId();
    }
}
