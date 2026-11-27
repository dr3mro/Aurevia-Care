package com.aurevia.pharmacyflow.substitution;

import java.util.Set;

/**
 * Command bodies for the two substitution transitions.
 *
 * <p>The organization, actor and active roles are supplied by the caller from
 * the active tenant context and freshly resolved membership. Each command
 * freezes its role set at construction so a caller cannot widen its
 * permissions after submission.</p>
 */
public interface SubstitutionCommands {

    /**
     * Command proposing a tagged catalog alternative on a prescription.
     *
     * @return this immutable command
     */
    record ProposeCommand(String organizationId, String subject, Set<String> roles, String prescriptionId,
                          String currentEntryId, String proposedEntryId, String reason, String note,
                          String overrideReason, String idempotencyKey) {

        /**
         * Freeze the caller's role set so it cannot change after submission.
         */
        public ProposeCommand {
            roles = Set.copyOf(roles);
        }
    }

    /**
     * Command deciding a pending proposal as its named prescriber.
     *
     * @return this immutable command
     */
    record DecideCommand(String organizationId, String subject, Set<String> roles, String proposalId,
                         int expectedVersion, boolean approved, String attestation, String note,
                         String idempotencyKey) {

        /**
         * Freeze the caller's role set so it cannot change after submission.
         */
        public DecideCommand {
            roles = Set.copyOf(roles);
        }
    }
}
