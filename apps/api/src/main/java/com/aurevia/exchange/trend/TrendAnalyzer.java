package com.aurevia.exchange.trend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Pure comparability decision for XCH-07.
 *
 * <p>Eligibility is decided before any numeric operation: a result must match
 * the requested test identity and comparability group, be a final revision, be
 * unqualified, be numeric, and be reported in the canonical unit. Every other
 * observation is returned in the visible exclusion list with a reason. This
 * class performs no I/O and generates no clinical interpretation.</p>
 */
@Component
public class TrendAnalyzer {

    private static final Comparator<TrendObservation> BY_TIME_THEN_ID =
            Comparator.comparing(TrendObservation::effectiveAt).thenComparing(TrendObservation::id);

    /**
     * Classify the observations of one patient against an effective rule.
     *
     * @param testCode     test identity requested by the caller
     * @param rule         effective comparability rule, or {@code null} when none
     *                     is established; all observations remain visible
     * @param observations observations of the patient in the active tenant
     * @return comparable points and visible exclusions, both ordered by
     *         effective time and identifier
     */
    public TrendView analyze(String testCode, ComparabilityRule rule, List<TrendObservation> observations) {
        if (rule == null) {
            return excludedView(testCode, null, 0, observations,
                    ExclusionReason.COMPARABILITY_NOT_ESTABLISHED);
        }
        if (!rule.trendable()) {
            return excludedView(testCode, rule.canonicalUnit(), rule.version(), observations,
                    ExclusionReason.NOT_TRENDABLE);
        }
        List<TrendPoint> points = new ArrayList<>();
        List<ExcludedObservation> excluded = new ArrayList<>();
        for (TrendObservation observation : observations) {
            ExclusionReason reason = classify(rule, observation);
            if (reason == null) {
                points.add(toPoint(observation));
            } else {
                excluded.add(new ExcludedObservation(observation, reason));
            }
        }
        points.sort(Comparator.comparing(TrendPoint::effectiveAt).thenComparing(TrendPoint::observationId));
        excluded.sort((left, right) -> BY_TIME_THEN_ID.compare(left.observation(), right.observation()));
        return new TrendView(testCode, rule.canonicalUnit(), rule.version(), points, excluded);
    }

    private static TrendView excludedView(String testCode, String unit, int version,
                                          List<TrendObservation> observations, ExclusionReason reason) {
        List<ExcludedObservation> excluded = new ArrayList<>();
        for (TrendObservation observation : observations) {
            excluded.add(new ExcludedObservation(observation, reason));
        }
        excluded.sort((left, right) -> BY_TIME_THEN_ID.compare(left.observation(), right.observation()));
        return new TrendView(testCode, unit, version, List.of(), excluded);
    }

    private static ExclusionReason classify(ComparabilityRule rule, TrendObservation observation) {
        if (!rule.testCode().equals(observation.testCode())) {
            return ExclusionReason.DIFFERENT_TEST;
        }
        if (!rule.comparabilityGroup().equals(observation.comparabilityGroup())) {
            return ExclusionReason.DIFFERENT_COMPARABILITY_GROUP;
        }
        if (!TrendObservation.FINAL.equals(observation.revisionStatus())) {
            return ExclusionReason.NON_FINAL_REVISION;
        }
        if (observation.qualifier() != Qualifier.NONE) {
            return ExclusionReason.QUALIFIED_NUMERIC;
        }
        if (observation.value() == null) {
            return ExclusionReason.QUALITATIVE;
        }
        if (!rule.canonicalUnit().equals(observation.unit())) {
            return ExclusionReason.INCOMPATIBLE_UNIT;
        }
        return null;
    }

    private static TrendPoint toPoint(TrendObservation observation) {
        return new TrendPoint(observation.id(), observation.testCode(), observation.comparabilityGroup(),
                observation.value(), observation.unit(), observation.referenceLow(),
                observation.referenceHigh(), observation.referenceUnit(), observation.effectiveAt(),
                observation.interpretation(), observation.sourceSystem(), observation.sourceVersion());
    }
}
