package com.aurevia.patient.consolidation;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action policy for same-tenant duplicate review and consolidation (IAM-04).
 *
 * <p>Identity administration is a privacy/tenant-admin capability. Reception staff
 * register and search patients but never review or merge them. The policy is kept
 * local to this slice because the shared {@code ActionPolicy} cannot be extended
 * from the S-G0-06 scope; centralizing these actions is a follow-on outside this
 * story. Every action still requires active membership, re-derived per request.</p>
 */
@Component
public class DuplicatePolicy {

    /** Review the duplicate worklist and a dry-run pair. */
    public static final String REVIEW = "DUPLICATE_REVIEW";
    /** Open a review or approve a consolidation (second reviewer required). */
    public static final String APPROVE = "CONSOLIDATION_APPROVE";
    /** Correct an erroneous consolidation through the audited support workflow. */
    public static final String CORRECT = "CONSOLIDATION_CORRECT";

    private static final Set<String> REVIEWERS = Set.of("tenant_admin", "privacy_auditor");
    private static final Set<String> ADMIN = Set.of("tenant_admin");

    /**
     * Decide whether a role may perform an identity-administration action.
     *
     * @param action one of {@code REVIEW}, {@code APPROVE} or {@code CORRECT}
     * @param role   role key currently in force in the active organization
     * @return {@code true} when the action is allowed for the role
     */
    public boolean allows(String action, String role) {
        if (REVIEW.equals(action) || APPROVE.equals(action)) {
            return REVIEWERS.contains(role);
        }
        return CORRECT.equals(action) && ADMIN.contains(role);
    }
}
