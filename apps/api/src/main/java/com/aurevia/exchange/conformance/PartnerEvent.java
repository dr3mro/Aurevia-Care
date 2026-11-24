package com.aurevia.exchange.conformance;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One immutable synthetic partner message replayed by the conformance fixtures.
 *
 * <p>Event identity is the pair of {@code sender} and {@code eventId}; dedup is
 * scoped to that sender and the event kind, so two partners may legitimately
 * choose the same event identifier (ARC-05). {@code revision} is the per
 * aggregate version used to reject stale deliveries. {@code identifier} is the
 * issuer-scoped identifier matched before clinical data is linked. {@code unit}
 * and {@code payload} carry the observation evidence used for XCH-07
 * comparability.</p>
 *
 * @return this record
 */
public record PartnerEvent(String eventId, String sender, EventKind kind, String aggregateKey,
                           long revision, String identifier, String unit, String payload) {

    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * Compact constructor enforcing the identity fields required by every event.
     */
    public PartnerEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(aggregateKey, "aggregateKey");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * Build an event from the default synthetic laboratory partner.
     *
     * @param eventId      immutable event identifier
     * @param kind         semantic kind
     * @param aggregateKey local aggregate key
     * @param revision     per-aggregate version
     * @param identifier   issuer-scoped identifier
     * @param payload      synthetic content
     * @return the event with no measurement unit
     */
    public static PartnerEvent of(String eventId, EventKind kind, String aggregateKey,
                                  long revision, String identifier, String payload) {
        return new PartnerEvent(eventId, "partner-lab", kind, aggregateKey, revision,
                identifier, null, payload);
    }

    /**
     * Whether two observations are eligible for a shared clinical trend
     * (XCH-07). A trend requires the same test identity, a known and identical
     * unit and numeric payloads on both sides. Qualified values such as
     * {@code <5} and qualitative values such as {@code POSITIVE} stay visible
     * in the payload but are never treated as comparable measurements.
     *
     * @param left  first observation
     * @param right second observation
     * @return {@code true} only when the observations are directly comparable
     */
    public static boolean comparable(PartnerEvent left, PartnerEvent right) {
        return left.kind() == EventKind.OBSERVATION
                && right.kind() == EventKind.OBSERVATION
                && Objects.equals(left.identifier(), right.identifier())
                && left.unit() != null
                && left.unit().equals(right.unit())
                && isNumeric(left.payload())
                && isNumeric(right.payload());
    }

    /**
     * Whether the payload is a plain numeric measurement.
     *
     * @param value synthetic payload text
     * @return {@code true} for an unqualified numeric value
     */
    private static boolean isNumeric(String value) {
        return NUMERIC.matcher(value).matches();
    }
}
