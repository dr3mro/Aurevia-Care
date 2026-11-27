package com.aurevia.exchange.result;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action policy for result routing.
 *
 * <p>The shared {@code ActionPolicy} is not modified here. Clinical
 * result content is limited to the laboratory verifier and clinician roles;
 * tenant administrators own order registration and reconciliation, and a
 * privacy auditor may review the reconciliation queue but not clinical
 * content. Membership is re-read inside each protected transaction, so a
 * revocation takes effect on the next request.</p>
 */
@Component
public class ResultActionPolicy {

    /** Register a synthetic expected-order projection. */
    public static final String ORDER_REGISTER = "XCH04_ORDER_REGISTER";
    /** Import an inbound result and route it. */
    public static final String RESULT_IMPORT = "XCH04_RESULT_IMPORT";
    /** Read a routed result revision and its lineage. */
    public static final String RESULT_READ = "XCH04_RESULT_READ";
    /** Acknowledge one exact result revision. */
    public static final String REVIEW_ACK = "XCH04_REVIEW_ACK";
    /** Review the reconciliation queue. */
    public static final String RECONCILIATION_READ = "XCH04_RECONCILIATION_READ";
    /** Resolve a reconciliation entry by matching, rejecting or reassigning it. */
    public static final String RECONCILIATION_RESOLVE = "XCH04_RECONCILIATION_RESOLVE";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            ORDER_REGISTER, Set.of("tenant_admin"),
            RESULT_IMPORT, Set.of("lab_verifier"),
            RESULT_READ, Set.of("lab_verifier", "clinician"),
            REVIEW_ACK, Set.of("lab_verifier", "clinician"),
            RECONCILIATION_READ, Set.of("lab_verifier", "tenant_admin", "privacy_auditor"),
            RECONCILIATION_RESOLVE, Set.of("lab_verifier", "tenant_admin"));

    /**
     * Decide whether an active role may perform an action.
     *
     * @param action action key such as the result-import action
     * @param role   role key active in the current organization
     * @return {@code true} only for an explicitly allowed pair
     */
    public boolean allows(String action, String role) {
        if (action == null || role == null) {
            return false;
        }
        return ALLOWED.getOrDefault(action, Set.of()).contains(role);
    }
}
