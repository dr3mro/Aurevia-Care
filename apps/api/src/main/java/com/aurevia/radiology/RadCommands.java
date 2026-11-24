package com.aurevia.radiology;

import java.time.Instant;
import java.util.Set;

import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;

/**
 * Command bodies for the RAD-01 imaging domain kernel.
 *
 * <p>Organization, actor, roles and version preconditions are supplied by the
 * caller from the active tenant context and authenticated membership; none is
 * inferred from a resource. Role sets are copied at construction so a command
 * cannot be changed after submission.</p>
 */
public interface RadCommands {

    /**
     * Command that receives an imaging order with urgency and clinical question.
     *
     */
    record IntakeCommand(String organizationId, String subject, Set<String> roles, String orderId,
                         String patientId, String encounterId, String orderCode, Urgency urgency,
                         String clinicalQuestion, String sourceActor, String destination,
                         String responsibleClinician, String workQueue, String referrer,
                         Modality modality) {

        /**
         * Receive an order without a pre-selected modality; scheduling records it later.
         *
         * @param organizationId       active tenant
         * @param subject              authenticated actor
         * @param roles                active roles
         * @param orderId              tenant-local order identifier
         * @param patientId            matched patient identifier
         * @param encounterId          source encounter
         * @param orderCode            local order code
         * @param urgency              clinical urgency
         * @param clinicalQuestion     question for the reader
         * @param sourceActor           ordering actor
         * @param destination          receiving destination
         * @param responsibleClinician named owner when urgency requires one
         * @param workQueue            responsible work queue
         * @param referrer             referring subject
         */
        public IntakeCommand(String organizationId, String subject, Set<String> roles, String orderId,
                             String patientId, String encounterId, String orderCode, Urgency urgency,
                             String clinicalQuestion, String sourceActor, String destination,
                             String responsibleClinician, String workQueue, String referrer) {
            this(organizationId, subject, roles, orderId, patientId, encounterId, orderCode, urgency,
                    clinicalQuestion, sourceActor, destination, responsibleClinician, workQueue,
                    referrer, null);
        }

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public IntakeCommand {
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
     * Command that records a study time and approved modality.
     *
     */
    record ScheduleCommand(String organizationId, String subject, Set<String> roles, String orderId,
                           int expectedVersion, Instant scheduledAt, Modality modality) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public ScheduleCommand {
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
     * Command that assigns an issuer-scoped accession.
     *
     */
    record AccessionCommand(String organizationId, String subject, Set<String> roles, String orderId,
                            int expectedVersion, String accession, String accessionIssuer) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public AccessionCommand {
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
     * Command that associates an external study after verifying patient and accession.
     *
     */
    record AssociateStudyCommand(String organizationId, String subject, Set<String> roles,
                                 String orderId, int expectedVersion, String studyInstanceUid,
                                 String patientId, String accession, String workstationRef) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public AssociateStudyCommand {
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
     * Command that resolves a study-link review after a patient or accession mismatch.
     *
     */
    record StudyReviewCommand(String organizationId, String subject, Set<String> roles,
                              String orderId, String studyInstanceUid, String resolution) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public StudyReviewCommand {
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
     * Command that signs an interpretation of an associated study.
     *
     */
    record SignCommand(String organizationId, String subject, Set<String> roles, String orderId,
                       int expectedVersion, String patientId, String accession,
                       String studyInstanceUid, String technique, String comparison,
                       String findings, String impression, String followUp, String templateVersion,
                       String attestation) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public SignCommand {
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
     * Command that links a signed addendum to a prior interpretation.
     *
     */
    record AddendumCommand(String organizationId, String subject, Set<String> roles, String orderId,
                           int expectedVersion, String patientId, String studyInstanceUid,
                           String findings, String impression, String reason, String attestation) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public AddendumCommand {
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
     * Command that records a critical communication about an associated study.
     *
     */
    record CriticalCommunicationCommand(String organizationId, String subject, Set<String> roles,
                                        String orderId, int expectedVersion, String patientId,
                                        String studyInstanceUid, String finding, String recipient,
                                        String readBack) {

        /**
         * Copy the caller's role set so the command cannot be changed after submission.
         */
        public CriticalCommunicationCommand {
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
