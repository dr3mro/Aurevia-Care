package com.aurevia.radflow.report;

import java.util.Set;

import com.aurevia.radiology.Interpretation;

/**
 * Command bodies for the report delivery kernel.
 *
 * <p>Organization, actor and roles are supplied by the caller from the active
 * tenant context and freshly resolved membership; none is inferred from the
 * interpretation. Each command freezes its role set at construction so a caller
 * cannot widen its permissions after submission.</p>
 */
public interface ReportCommands {

    /**
     * Command that delivers a signed initial interpretation to the referring clinician.
     *
     * @return this record
     */
    record PublishCommand(String organizationId, String subject, Set<String> roles, String orderId,
                          String referrer, Interpretation interpretation) {

        /** Copy the caller's role set so the command cannot be changed after submission. */
        public PublishCommand {
            roles = Set.copyOf(roles);
        }

        /** @return the immutable role set */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command that links a signed addendum revision and opens a fresh acknowledgment receipt.
     *
     * @return this record
     */
    record AddendumCommand(String organizationId, String subject, Set<String> roles, String orderId,
                           Interpretation addendum) {

        /** Copy the caller's role set so the command cannot be changed after submission. */
        public AddendumCommand {
            roles = Set.copyOf(roles);
        }

        /** @return the immutable role set */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command that records the referring clinician's acknowledgment of one addendum revision.
     *
     * @return this record
     */
    record AcknowledgeCommand(String organizationId, String subject, String orderId, int version) {
    }
}
