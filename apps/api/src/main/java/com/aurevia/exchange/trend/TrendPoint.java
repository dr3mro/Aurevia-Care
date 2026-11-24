package com.aurevia.exchange.trend;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One comparable point in a numeric trend, carrying every XCH-07 display field.
 *
 * <p>The unit is the source unit reported with the observation; it is equal to
 * the rule's canonical unit by construction, so no conversion is hidden. The
 * reference range keeps its own reported unit and may be {@code null} when no
 * approved range was supplied. No derived or review status is included: a point
 * on a trend is not an acknowledgment of a critical result.</p>
 *
 * @param observationId      source observation identifier
 * @param testCode           test identity
 * @param comparabilityGroup assay/method group
 * @param value              numeric value
 * @param unit               reported source unit
 * @param referenceLow       lower reference bound, or {@code null}
 * @param referenceHigh      upper reference bound, or {@code null}
 * @param referenceUnit      reference range unit, or {@code null}
 * @param effectiveAt        clinical instant used for ordering
 * @param interpretation     source interpretation, shown verbatim
 * @param sourceSystem       originating system
 * @param sourceVersion      originating revision/version
 * @return this record
 */
public record TrendPoint(String observationId, String testCode, String comparabilityGroup,
                         BigDecimal value, String unit, BigDecimal referenceLow,
                         BigDecimal referenceHigh, String referenceUnit, Instant effectiveAt,
                         String interpretation, String sourceSystem, String sourceVersion) {
}
