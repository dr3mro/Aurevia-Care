package com.aurevia.exchange.response;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action policy for the XCH-03 recipient-response slice (IAM-01, XCH-03).
 *
 * <p>Recipient decisions and sender lifecycle commands are separated. A privacy
 * auditor may read the work item but cannot mutate it, and no generic role can
 * revoke a release. This policy is local to the story so the shared action
 * policy is not widened outside its scope.</p>
 */
@Component
public class ExchangeResponseActionPolicy {

    /** Accept a released order. */
    public static final String EXCHANGE_ACCEPT = "EXCHANGE_ACCEPT";
    /** Reject a released order with a reason. */
    public static final String EXCHANGE_REJECT = "EXCHANGE_REJECT";
    /** Request clarification with a question. */
    public static final String EXCHANGE_CLARIFY = "EXCHANGE_CLARIFY";
    /** Respond to a linked change or cancellation request. */
    public static final String EXCHANGE_RESPOND = "EXCHANGE_RESPOND";
    /** Propose a linked change after acceptance. */
    public static final String EXCHANGE_PROPOSE_CHANGE = "EXCHANGE_PROPOSE_CHANGE";
    /** Request cancellation after acceptance. */
    public static final String EXCHANGE_REQUEST_CANCEL = "EXCHANGE_REQUEST_CANCEL";
    /** Revoke a release and notify downstream recipients. */
    public static final String EXCHANGE_REVOKE = "EXCHANGE_REVOKE";
    /** Read the work item and its ledgers. */
    public static final String EXCHANGE_READ = "EXCHANGE_READ";
    /** Retrieve the current released revision. */
    public static final String EXCHANGE_RETRIEVE = "EXCHANGE_RETRIEVE";

    private static final Set<String> RECIPIENT_ROLES = Set.of(
            "lab_collector", "lab_verifier", "radiology_staff", "pt_clinician",
            "pharmacy_staff", "clinician");
    private static final Set<String> SENDER_ROLES = Set.of("clinician", "reception");
    private static final Set<String> REVOKE_ROLES = Set.of("clinician", "tenant_admin");
    private static final Set<String> READ_ROLES = Set.of(
            "lab_collector", "lab_verifier", "radiology_staff", "pt_clinician",
            "pharmacy_staff", "clinician", "reception", "tenant_admin", "privacy_auditor");
    private static final Set<String> RECIPIENT_ACTIONS =
            Set.of(EXCHANGE_ACCEPT, EXCHANGE_REJECT, EXCHANGE_CLARIFY, EXCHANGE_RESPOND);
    private static final Set<String> SENDER_ACTIONS =
            Set.of(EXCHANGE_PROPOSE_CHANGE, EXCHANGE_REQUEST_CANCEL);

    /**
     * Decide whether an active-organization role may perform an action.
     *
     * @param action action key such as {@code EXCHANGE_ACCEPT}
     * @param role   role key from the freshly derived active roles
     * @return {@code true} when allowed; unknown roles and actions fail closed
     */
    public boolean allows(String action, String role) {
        if (EXCHANGE_READ.equals(action) || EXCHANGE_RETRIEVE.equals(action)) {
            return READ_ROLES.contains(role);
        }
        if (EXCHANGE_REVOKE.equals(action)) {
            return REVOKE_ROLES.contains(role);
        }
        if (RECIPIENT_ACTIONS.contains(action)) {
            return RECIPIENT_ROLES.contains(role);
        }
        return SENDER_ACTIONS.contains(action) && SENDER_ROLES.contains(role);
    }
}
