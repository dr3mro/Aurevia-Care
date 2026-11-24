package com.aurevia.pharmacyflow.status;

import java.time.Instant;
import java.util.Set;

/**
 * Command bodies for the four pharmacy status facts (RX-02, ARC-03).
 *
 * <p>The organization, actor and active roles are supplied by the caller from
 * the active tenant context and freshly resolved membership; none is inferred
 * from a prescription resource. Each command freezes its role set at
 * construction so a caller cannot widen its permissions after submission.</p>
 */
public interface PharmacyStatusCommands {

    /**
     * Command recording that the pharmacy accepted the prescription.
     *
     * @return this immutable record
     */
    record AcceptCommand(String organizationId, String subject, Set<String> roles,
                         String prescriptionId, int expectedVersion, String idempotencyKey) {

        /**
         * Freeze the caller's role set so it cannot be changed after submission.
         */
        public AcceptCommand {
            roles = Set.copyOf(roles);
        }

        /**
         * @return the immutable role set
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command recording a pharmacy rejection with a mandatory reason.
     *
     * @return this immutable record
     */
    record RejectCommand(String organizationId, String subject, Set<String> roles,
                         String prescriptionId, int expectedVersion, String idempotencyKey,
                         String reason) {

        /**
         * Freeze the caller's role set so it cannot be changed after submission.
         */
        public RejectCommand {
            roles = Set.copyOf(roles);
        }

        /**
         * @return the immutable role set
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command asking the prescriber for clarification.
     *
     * @return this immutable record
     */
    record ClarificationCommand(String organizationId, String subject, Set<String> roles,
                                String prescriptionId, int expectedVersion, String idempotencyKey,
                                String question) {

        /**
         * Freeze the caller's role set so it cannot be changed after submission.
         */
        public ClarificationCommand {
            roles = Set.copyOf(roles);
        }

        /**
         * @return the immutable role set
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command recording dispensing evidence: who dispensed, when, and what was handed over.
     *
     * @return this immutable record
     */
    record DispenseCommand(String organizationId, String subject, Set<String> roles,
                           String prescriptionId, int expectedVersion, String idempotencyKey,
                           String dispensedBy, Instant dispensedAt, String handedOver) {

        /**
         * Freeze the caller's role set so it cannot be changed after submission.
         */
        public DispenseCommand {
            roles = Set.copyOf(roles);
        }

        /**
         * @return the immutable role set
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }
}
