package com.aurevia.smartclinic.g4.s_g4_01;

import java.time.Instant;
import java.util.Set;

import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.Modality;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.Urgency;

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
     * @param organizationId       active tenant
     * @param subject              authenticated actor recorded as intake author
     * @param roles                roles active in the current organization
     * @param orderId              tenant-local opaque order identifier
     * @param patientId            matched patient identifier
     * @param encounterId          source encounter identifier, or {@code null}
     * @param orderCode            local imaging order code
     * @param urgency              recorded clinical urgency
     * @param clinicalQuestion     clinical question for the study
     * @param sourceActor          actor that authored the order
     * @param destination          receiving radiology destination
     * @param responsibleClinician named responsible clinician
     * @param workQueue            responsible work queue
     * @param referrer             clinician to notify about signed revisions
     */
    record IntakeCommand(String organizationId, String subject, Set<String> roles, String orderId,
                         String patientId, String encounterId, String orderCode, Urgency urgency,
                         String clinicalQuestion, String sourceActor, String destination,
                         String responsibleClinician, String workQueue, String referrer) {

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
     * @param organizationId active tenant
     * @param subject        authenticated actor
     * @param roles          roles active in the current organization
     * @param orderId        tenant-local order identifier
     * @param expectedVersion version read by the caller, used as a precondition
     * @param scheduledAt    scheduled study time
     * @param modality       requested imaging modality
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
     * @param organizationId active tenant
     * @param subject        authenticated actor
     * @param roles          roles active in the current organization
     * @param orderId        tenant-local order identifier
     * @param expectedVersion version read by the caller, used as a precondition
     * @param accession      issuer-scoped accession identifier
     * @param accessionIssuer issuer of the accession
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
     * @param organizationId active tenant
     * @param subject        authenticated actor
     * @param roles          roles active in the current organization
     * @param orderId        tenant-local order identifier
     * @param expectedVersion version read by the caller, used as a precondition
     * @param studyInstanceUid external DICOM Study Instance UID
     * @param patientId      patient identifier verified against the order
     * @param accession      accession verified against the order
     * @param workstationRef partner diagnostic workstation reference
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
     * Command that signs an interpretation of an associated study.
     *
     * @param organizationId active tenant
     * @param subject        authenticated interpreting subject
     * @param roles          roles active in the current organization
     * @param orderId        tenant-local order identifier
     * @param expectedVersion version read by the caller, used as a precondition
     * @param patientId      patient identifier re-verified at signing
     * @param accession      accession re-verified at signing
     * @param studyInstanceUid external study re-verified at signing
     * @param technique      imaging technique documented by the interpreter
     * @param comparison     comparison studies documented by the interpreter
     * @param findings       documented findings
     * @param impression     documented impression
     * @param followUp       documented follow-up
     * @param templateVersion version of the interpretation template used
     * @param attestation    signer attestation
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
     * @param organizationId active tenant
     * @param subject        authenticated interpreting subject
     * @param roles          roles active in the current organization
     * @param orderId        tenant-local order identifier
     * @param expectedVersion version read by the caller, used as a precondition
     * @param patientId      patient identifier re-verified at signing
     * @param studyInstanceUid external study re-verified at signing
     * @param findings       addendum findings
     * @param impression     addendum impression
     * @param reason         required reason for the addendum
     * @param attestation    signer attestation
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
     * @param organizationId active tenant
     * @param subject        authenticated actor
     * @param roles          roles active in the current organization
     * @param orderId        tenant-local order identifier
     * @param expectedVersion version read by the caller, used as a precondition
     * @param patientId      patient identifier re-verified before recording
     * @param studyInstanceUid external study re-verified before recording
     * @param finding        critical finding communicated
     * @param recipient      recipient of the communication
     * @param readBack       recorded read-back evidence
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
