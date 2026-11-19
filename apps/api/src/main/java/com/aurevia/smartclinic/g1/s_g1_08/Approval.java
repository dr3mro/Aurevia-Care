package com.aurevia.smartclinic.g1.s_g1_08;

/**
 * Named governance approval of one catalogue or dictionary version (CLN-04, ADM-01).
 *
 * <p>The approver is data here, not an authorization decision: real tenant and
 * governance enforcement belongs to the controller, policy and audit layers.</p>
 *
 * @param approver   identifier of the approving reviewer
 * @param approvedAt UTC instant of approval
 * @param note       optional approval note
 */
public record Approval(String approver, String approvedAt, String note) {

    /**
     * Validate the approval identity and instant.
     *
     * @throws IllegalArgumentException when the approver or instant is missing
     */
    public Approval {
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("approver is required");
        }
        if (approvedAt == null || approvedAt.isBlank()) {
            throw new IllegalArgumentException("approvedAt is required");
        }
    }
}
