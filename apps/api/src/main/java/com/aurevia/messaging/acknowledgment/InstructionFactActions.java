package com.aurevia.messaging.acknowledgment;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for instruction facts.
 *
 * <p>Patient actions (first view, acknowledgment) are separated from staff
 * actions (publication, dispatch, follow-up review). A tenant administrator may
 * hold the staff action permissions, but an owning-unit action still needs unit
 * assignment as object authorization. An administrator may read the follow-up
 * queue for an assigned unit; it can never record a patient first view or receipt
 * acknowledgment, which are patient-only facts. The matrix is paired with fresh
 * membership, role, verified-chart and connection checks on every protected
 * request.</p>
 */
@Component
public class InstructionFactActions {

    /** Publish an instruction revision. */
    public static final String PUBLISH = "PUBLISH";
    /** Dispatch or retry a generic notification. */
    public static final String DISPATCH = "DISPATCH";
    /** Record a first in-app view. */
    public static final String FIRST_VIEW = "FIRST_VIEW";
    /** Acknowledge receipt of one exact revision. */
    public static final String ACKNOWLEDGE = "ACKNOWLEDGE";
    /** Read the overdue follow-up queue. */
    public static final String FOLLOW_UP_LIST = "FOLLOW_UP_LIST";
    /** Resolve an overdue follow-up with a reason. */
    public static final String FOLLOW_UP_RESOLVE = "FOLLOW_UP_RESOLVE";

    private static final Set<String> OWNING_UNIT_STAFF = Set.of(
            "clinician", "lab_collector", "lab_verifier", "radiology_staff", "pt_clinician",
            "pharmacy_staff");

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        return switch (action) {
            case PUBLISH, DISPATCH, FOLLOW_UP_LIST, FOLLOW_UP_RESOLVE ->
                    OWNING_UNIT_STAFF.contains(role) || "tenant_admin".equals(role);
            case FIRST_VIEW, ACKNOWLEDGE -> "patient".equals(role);
            default -> false;
        };
    }
}
