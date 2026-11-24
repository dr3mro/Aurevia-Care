package com.aurevia.lab.result;

import java.util.List;
import java.util.Set;

/**
 * Command bodies for the LAB-02 domain kernel.
 *
 * <p>Organization, author and version are supplied by the caller from the active
 * tenant context and authenticated membership; none is inferred from a resource.</p>
 */
public interface LabCommands {

    /**
     * Command that captures observations against a matched specimen and order.
     *
     */
    record CaptureCommand(String organizationId, String subject, Set<String> roles, String reportId,
                          String patientId, String specimenId, String orderId,
                          List<LabObservation> observations) {

        /**
         * Copy the caller's collections so the command cannot be changed after submission.
         */
        public CaptureCommand {
            roles = Set.copyOf(roles);
            observations = List.copyOf(observations);
        }

        /**
         * Return the immutable role set.
         *
         * @return active roles
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }

        /**
         * Return the immutable observation list.
         *
         * @return observations in capture order
         */
        @Override
        public List<LabObservation> observations() {
            return List.copyOf(observations);
        }
    }

    /**
     * Command that verifies a preliminary revision and issues it as final (XCH-04).
     *
     */
    record VerifyCommand(String organizationId, String subject, Set<String> roles, String reportId,
                         int expectedVersion, String patientId, String specimenId, String orderId,
                         String verifier, String owner, String queue) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public VerifyCommand {
            roles = Set.copyOf(roles);
        }

        /**
         * Return the immutable role set.
         *
         * @return active roles
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command that appends a corrected revision and reopens review (XCH-04).
     *
     */
    record CorrectCommand(String organizationId, String subject, Set<String> roles, String reportId,
                          int expectedVersion, String patientId, String specimenId, String orderId,
                          String verifier, String owner, String queue, String reason) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public CorrectCommand {
            roles = Set.copyOf(roles);
        }

        /**
         * Return the immutable role set.
         *
         * @return active roles
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command that marks an issued revision as entered-in-error and withdraws it (CLN-05).
     *
     */
    record ErrorCommand(String organizationId, String subject, Set<String> roles, String reportId,
                        int expectedVersion, String reason) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public ErrorCommand {
            roles = Set.copyOf(roles);
        }

        /**
         * Return the immutable role set.
         *
         * @return active roles
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }
}
