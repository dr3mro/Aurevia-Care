package com.aurevia.exchange.release;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action policy for release decisions (XCH-06, IAM-01).
 *
 * <p>Release, withdrawal, correction and hold review are clinical decisions, so
 * they require a clinician or tenant administrator. Single-resource reads allow
 * the wider care-team roles; a privacy auditor can review but never decide. The
 * patient-scoped revision query is narrower still: it is a care-team read and not
 * a directory enumeration surface. A verified patient is authorized by the live
 * chart-link check, not by a staff role. This slice owns its policy so it does
 * not widen the shared G0/G1 action matrix.</p>
 */
@Component
public class ReleaseActionPolicy {

    /** Authorize a patient release. */
    public static final String AUTHORIZE = "RELEASE_AUTHORIZE";
    /** Lift a held release after review. */
    public static final String LIFT_HOLD = "RELEASE_LIFT_HOLD";
    /** Withdraw a release. */
    public static final String WITHDRAW = "RELEASE_WITHDRAW";
    /** Correct a release and supersede the prior revision. */
    public static final String CORRECT = "RELEASE_CORRECT";
    /** Read one release. */
    public static final String READ = "RELEASE_READ";
    /** Read the decision history of one release. */
    public static final String HISTORY = "RELEASE_HISTORY";
    /** Query the revisions a patient may currently access. */
    public static final String ACCESS = "RELEASE_ACCESS_QUERY";

    private static final Set<String> DECIDERS = Set.of("clinician", "tenant_admin");
    private static final Set<String> READERS =
            Set.of("clinician", "tenant_admin", "reception", "privacy_auditor");
    private static final Set<String> PATIENT_SCOPED_READERS = Set.of("clinician", "tenant_admin");

    /**
     * Decide whether a role may perform a release action.
     *
     * @param action one of the action constants on this class
     * @param role   role key assigned in the active organization
     * @return {@code true} when the action is allowed
     */
    public boolean allows(String action, String role) {
        if (role == null) {
            return false;
        }
        return switch (action) {
            case AUTHORIZE, LIFT_HOLD, WITHDRAW, CORRECT -> DECIDERS.contains(role);
            case READ, HISTORY -> READERS.contains(role);
            case ACCESS -> PATIENT_SCOPED_READERS.contains(role);
            default -> false;
        };
    }
}
