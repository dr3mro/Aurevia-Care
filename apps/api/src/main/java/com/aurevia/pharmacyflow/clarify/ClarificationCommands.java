package com.aurevia.pharmacyflow.clarify;

import java.util.Set;

/**
 * Command bodies for the two clarification transitions (RX-02, ARC-03).
 *
 * <p>The organization, actor and active roles are supplied by the caller from
 * the active tenant context and freshly resolved membership; none is inferred
 * from a prescription resource. Each command freezes its role set at
 * construction so a caller cannot widen its permissions after submission.</p>
 */
public interface ClarificationCommands {

    /**
     * Command opening a clarification request on a prescription.
     *
     * @return this immutable command
     */
    record OpenCommand(String organizationId, String subject, Set<String> roles, String prescriptionId,
                       String question, String idempotencyKey) {

        /**
         * Freeze the caller's role set so it cannot change after submission.
         */
        public OpenCommand {
            roles = Set.copyOf(roles);
        }
    }

    /**
     * Command answering an open clarification request.
     *
     * @return this immutable command
     */
    record AnswerCommand(String organizationId, String subject, Set<String> roles, String threadId,
                         int expectedVersion, String answerBody, String amendedRevisionId,
                         String idempotencyKey) {

        /**
         * Freeze the caller's role set so it cannot change after submission.
         */
        public AnswerCommand {
            roles = Set.copyOf(roles);
        }
    }
}
