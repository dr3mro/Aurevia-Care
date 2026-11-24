package com.aurevia.lab.result;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.aurevia.api.ApiException;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;

/**
 * Approved, effective-dated laboratory configuration for one tenant (ADM-01, LAB-02).
 *
 * <p>Lookup selects the highest approved version whose effective window contains
 * the evaluation instant. A future-dated version never applies early and no
 * implicit default threshold exists: an empty or unapproved catalog yields no
 * range and no critical classification.</p>
 */
public final class LabPolicyCatalog {

    private final List<ReferenceRange> ranges;
    private final List<CriticalPolicy> criticalPolicies;

    /**
     * Create a catalog from approved configuration entries.
     *
     * @throws ApiException when either collection is missing
     */
    public LabPolicyCatalog(List<ReferenceRange> ranges, List<CriticalPolicy> criticalPolicies) {
        if (ranges == null || criticalPolicies == null) {
            throw new ApiException(422, "INVALID_POLICY_CATALOG", "policy collections are required");
        }
        this.ranges = List.copyOf(ranges);
        this.criticalPolicies = List.copyOf(criticalPolicies);
    }

    /**
     * Resolve the newest approved effective range for a test identity and unit.
     *
     * @return the effective range, or empty when no approved range applies
     */
    public Optional<ReferenceRange> effectiveRange(String code, String unit, Instant at) {
        return ranges.stream()
                .filter(range -> range.code().equals(code) && range.unit().equals(unit)
                        && range.effectiveAt(at))
                .max(Comparator.comparingInt(ReferenceRange::version));
    }

    /**
     * Resolve the newest approved effective critical policy for a test identity.
     *
     * @return the effective policy, or empty when no approved policy applies
     */
    public Optional<CriticalPolicy> effectiveCritical(String code, Instant at) {
        return criticalPolicies.stream()
                .filter(policy -> policy.code().equals(code) && policy.effectiveAt(at))
                .max(Comparator.comparingInt(CriticalPolicy::version));
    }

    /**
     * One versioned, approved reference range for a test identity and unit (LAB-02, ADM-01).
     *
     * @return the approved reference range
     */
    public record ReferenceRange(String code, String unit, BigDecimal low, BigDecimal high,
                                 int version, boolean approved, Instant effectiveFrom,
                                 Instant effectiveTo) {

        /**
         * Reject an incomplete or inverted range before it can be evaluated.
         *
         * @throws ApiException when identity, bounds, version or effective start is invalid
         */
        public ReferenceRange {
            if (code == null || code.isBlank() || unit == null || unit.isBlank()
                    || low == null || high == null || low.compareTo(high) >= 0
                    || version < 1 || effectiveFrom == null) {
                throw new ApiException(422, "INVALID_REFERENCE_RANGE", "invalid approved reference range");
            }
        }

        /**
         * Whether this range is approved and effective at the caller-supplied instant.
         *
         * @return {@code true} when the range may be applied
         */
        public boolean effectiveAt(Instant at) {
            return approved && !at.isBefore(effectiveFrom)
                    && (effectiveTo == null || at.isBefore(effectiveTo));
        }

        /**
         * Interpret the caller-supplied measured value against this range.
         *
         * @return the abnormal flag for the value
         */
        public AbnormalFlag flag(BigDecimal value) {
            if (value.compareTo(low) < 0) {
                return AbnormalFlag.LOW;
            }
            if (value.compareTo(high) > 0) {
                return AbnormalFlag.HIGH;
            }
            return AbnormalFlag.NONE;
        }
    }

    /**
     * One versioned, approved critical-result policy for a test identity (LAB-02, XCH-05).
     *
     * @return the approved critical-result policy
     */
    public record CriticalPolicy(String code, int version, BigDecimal low, BigDecimal high,
                                 boolean approved, Instant effectiveFrom, Instant effectiveTo,
                                 String owner, String queue, Duration dueAfter, String escalation) {

        /**
         * Reject an incomplete or inverted policy before it can classify a result.
         *
         * @throws ApiException when identity, bounds, owner, due window or effective start is invalid
         */
        public CriticalPolicy {
            if (code == null || code.isBlank() || low == null || high == null
                    || low.compareTo(high) >= 0 || version < 1 || effectiveFrom == null
                    || owner == null || owner.isBlank() || queue == null || queue.isBlank()
                    || dueAfter == null || dueAfter.isZero() || dueAfter.isNegative()) {
                throw new ApiException(422, "INVALID_CRITICAL_POLICY", "invalid approved critical policy");
            }
        }

        /**
         * Whether this policy is approved and effective at the caller-supplied instant.
         *
         * @return {@code true} when the policy may classify a result
         */
        public boolean effectiveAt(Instant at) {
            return approved && !at.isBefore(effectiveFrom)
                    && (effectiveTo == null || at.isBefore(effectiveTo));
        }

        /**
         * Decide whether the caller-supplied measured value meets the approved bounds.
         *
         * @return {@code true} when the value is at or outside a critical bound
         */
        public boolean isCritical(BigDecimal value) {
            return value.compareTo(low) <= 0 || value.compareTo(high) >= 0;
        }
    }
}
