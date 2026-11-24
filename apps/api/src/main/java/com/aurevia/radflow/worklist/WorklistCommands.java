package com.aurevia.radflow.worklist;

import java.time.Instant;
import java.util.Set;

import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;

/**
 * Command bodies for the RAD-01 performing-site worklist kernel.
 *
 * <p>Organization, actor and roles are supplied by the caller from the active
 * tenant context and freshly resolved membership; none is inferred from a
 * resource. Each command freezes its role set at construction so a caller cannot
 * widen its permissions after submission.</p>
 */
public interface WorklistCommands {

    /**
     * Command that receives an imaging order onto the performing-site worklist.
     *
     * @return this record
     */
    record ReceiveCommand(String organizationId, String subject, Set<String> roles, String orderId,
                          String performingSiteId, String patientId, String orderCode, Urgency urgency,
                          String clinicalQuestion, String referrer) {

        /** Copy the caller's role set so the command cannot be changed after submission. */
        public ReceiveCommand {
            roles = Set.copyOf(roles);
        }

        /** @return the immutable role set */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command that records the study time, an approved modality and the accession.
     *
     * @return this record
     */
    record ScheduleCommand(String organizationId, String subject, Set<String> roles, String orderId,
                           String performingSiteId, int expectedVersion, Instant scheduledAt,
                           Modality modality, String accession, String accessionIssuer) {

        /** Copy the caller's role set so the command cannot be changed after submission. */
        public ScheduleCommand {
            roles = Set.copyOf(roles);
        }

        /** @return the immutable role set */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command that links an external study after verifying patient and accession labels.
     *
     * @return this record
     */
    record LinkStudyCommand(String organizationId, String subject, Set<String> roles, String orderId,
                            String performingSiteId, int expectedVersion, String studyInstanceUid,
                            String patientId, String accession, String workstationRef) {

        /** Copy the caller's role set so the command cannot be changed after submission. */
        public LinkStudyCommand {
            roles = Set.copyOf(roles);
        }

        /** @return the immutable role set */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }

    /**
     * Command that hands a performed study off as a reported study.
     *
     * @return this record
     */
    record ReportCommand(String organizationId, String subject, Set<String> roles, String orderId,
                         String performingSiteId, int expectedVersion) {

        /** Copy the caller's role set so the command cannot be changed after submission. */
        public ReportCommand {
            roles = Set.copyOf(roles);
        }

        /** @return the immutable role set */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }
    }
}
