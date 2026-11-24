package com.aurevia.lab.result;

import java.util.Set;

import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;

/**
 * Pure rules for laboratory comparability and role boundaries.
 *
 * <p>Comparability follows XCH-07: only exact measured numbers for the same test
 * identity may be compared or trended, and their units must match unless the
 * caller supplies a {@link com.aurevia.lab.result.LabConversionCatalog}
 * with a documented, versioned conversion for that exact direction. Qualified
 * numbers, qualitative results and unknown or absent observations stay visible in
 * the report but are never treated as comparable measurements; the engine never
 * guesses a conversion. The role checks mirror the section 2 role table; whether
 * collector and verifier must differ is site policy (G5), not a hard-coded rule.</p>
 */
public interface LabRules {

    /** Role allowed to capture observations and specimens. */
    String COLLECTOR = "lab_collector";
    /** Role allowed to verify, correct and withdraw reports. */
    String VERIFIER = "lab_verifier";

    /**
     * Whether one observation may take part in numeric comparison.
     *
     * @param observation candidate observation, possibly {@code null}
     * @return {@code true} only for a present exact numeric observation
     */
    static boolean eligible(LabObservation observation) {
        return observation != null
                && observation.presence() == ObservationPresence.PRESENT
                && observation.kind() == ObservationKind.NUMERIC;
    }

    /**
     * Whether two observations may be compared or trended together with no
     * documented conversions available.
     *
     * @param first  first observation, possibly {@code null}
     * @param second second observation, possibly {@code null}
     * @return {@code true} only when both are eligible and share identity and unit
     */
    static boolean comparable(LabObservation first, LabObservation second) {
        return comparable(first, second, LabConversionCatalog.none());
    }

    /**
     * Whether two observations may be joined for a numeric trend.
     *
     * <p>Both observations must be exact measured numbers of the same test. Their
     * source units must match, or the supplied catalog must document a conversion
     * between them in either direction. Qualified and qualitative results are
     * never joined, and the original observations are never rewritten.</p>
     *
     * @param first       first observation, possibly {@code null}
     * @param second      second observation, possibly {@code null}
     * @param conversions approved documented conversions; empty means exact units only
     * @return {@code true} when the two observations may be compared
     */
    static boolean comparable(LabObservation first, LabObservation second,
                              LabConversionCatalog conversions) {
        if (!eligible(first) || !eligible(second)) {
            return false;
        }
        if (!first.code().equals(second.code())) {
            return false;
        }
        if (first.unit().equals(second.unit())) {
            return true;
        }
        return conversions.find(first.code(), first.unit(), second.unit()).isPresent()
                || conversions.find(first.code(), second.unit(), first.unit()).isPresent();
    }

    /**
     * Whether the subject may capture observations.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for a laboratory collector
     */
    static boolean mayCapture(Set<String> roles) {
        return roles.contains(COLLECTOR);
    }

    /**
     * Whether the subject may verify, correct or withdraw a report.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for a laboratory verifier
     */
    static boolean mayVerify(Set<String> roles) {
        return roles.contains(VERIFIER);
    }
}
