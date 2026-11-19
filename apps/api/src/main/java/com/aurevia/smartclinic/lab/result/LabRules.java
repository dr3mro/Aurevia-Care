package com.aurevia.smartclinic.lab.result;

import java.util.Set;

import com.aurevia.smartclinic.lab.result.LabTypes.ObservationKind;
import com.aurevia.smartclinic.lab.result.LabTypes.ObservationPresence;

/**
 * Pure rules for laboratory comparability and role boundaries.
 *
 * <p>Comparability follows XCH-07: only exact measured numbers for the same test
 * identity and the same source unit may be compared or trended. Qualified numbers,
 * qualitative results, unknown and absent observations and incompatible units stay
 * visible in the report but are never treated as comparable measurements, and no
 * unit conversion is attempted. The role checks mirror the section 2 role table;
 * whether collector and verifier must differ is site policy (G5), not a hard-coded rule.</p>
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
     * Whether two observations may be compared or trended together.
     *
     * @param first  first observation, possibly {@code null}
     * @param second second observation, possibly {@code null}
     * @return {@code true} when both are eligible and share test identity and unit
     */
    static boolean comparable(LabObservation first, LabObservation second) {
        if (!eligible(first) || !eligible(second)) {
            return false;
        }
        return first.code().equals(second.code()) && first.unit().equals(second.unit());
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
