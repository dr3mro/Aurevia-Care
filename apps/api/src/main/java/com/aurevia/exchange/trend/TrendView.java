package com.aurevia.exchange.trend;

import java.util.List;

/**
 * The result of a trend read: comparable points plus every excluded observation.
 *
 * <p>The two lists are immutable. The counts satisfy the XCH-07 safety check
 * that nothing disappeared: {@code points.size() + excluded.size()} equals the
 * number of observations considered.</p>
 *
 * @return this record
 */
public record TrendView(String testCode, String unit, int ruleVersion, List<TrendPoint> points,
                        List<ExcludedObservation> excluded) {

    /**
     * Copy both lists on construction and on access so callers cannot mutate the
     * view or reach a list they hold a reference to.
     */
    public TrendView {
        points = List.copyOf(points);
        excluded = List.copyOf(excluded);
    }

    /**
     * Return the comparable points.
     *
     * @return an immutable list of points
     */
    @Override
    public List<TrendPoint> points() {
        return List.copyOf(points);
    }

    /**
     * Return the excluded observations.
     *
     * @return an immutable list of visible, non-comparable observations
     */
    @Override
    public List<ExcludedObservation> excluded() {
        return List.copyOf(excluded);
    }
}
