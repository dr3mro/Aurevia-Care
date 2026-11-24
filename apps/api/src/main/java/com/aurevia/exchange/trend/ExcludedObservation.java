package com.aurevia.exchange.trend;

/**
 * An observation shown to the reviewer but deliberately kept out of the
 * comparable numeric trend (XCH-07).
 *
 * <p>The original observation is preserved unchanged, including its qualifier,
 * qualitative text, unit, range, time and interpretation, so an exclusion is
 * never a silent disappearance.</p>
 *
 * @param observation the original observation
 * @param reason      why it is not part of the trend
 * @return this record
 */
public record ExcludedObservation(TrendObservation observation, ExclusionReason reason) {
}
