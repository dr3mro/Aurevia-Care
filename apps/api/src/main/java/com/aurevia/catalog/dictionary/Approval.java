package com.aurevia.catalog.dictionary;

/**
 * Named governance approval of one catalogue or dictionary version (CLN-04, ADM-01).
 *
 * <p>The approver is data here, not an authorization decision: real tenant and
 * governance enforcement belongs to the controller, policy and audit layers.</p>
 *
 * @return this record
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
