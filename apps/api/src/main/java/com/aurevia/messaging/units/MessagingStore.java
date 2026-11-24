package com.aurevia.messaging.units;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;

/**
 * Persistence boundary for the MSG-01 / ARC-07 slice.
 *
 * <p>Every read and write carries the active organization explicitly, so a
 * caller cannot cross tenant boundaries even when local identifiers are
 * identical. Implementations must scope results by that organization and must
 * enforce durable uniqueness of one connection per verified patient, unit and
 * source reference.</p>
 */
public interface MessagingStore {

    /**
     * Check whether the subject has an active membership in the organization.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return {@code true} when the membership is active
     */
    boolean hasActiveMembership(String organizationId, String subject);

    /**
     * List the role keys currently in force for the subject.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return active role keys
     */
    List<String> activeRoles(String organizationId, String subject);

    /**
     * List the patient charts independently verified for this account (IAM-05).
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return verified patient identifiers
     */
    Set<String> verifiedPatientCharts(String organizationId, String subject);

    /**
     * Check that a patient is known in the active organization.
     *
     * @param organizationId active organization
     * @param patientId      patient identifier
     * @return {@code true} when the patient exists in this tenant
     */
    boolean patientExists(String organizationId, String patientId);

    /**
     * Find a care unit in the active organization only.
     *
     * @param organizationId active organization
     * @param unitId         unit identifier
     * @return the unit when visible in this tenant
     */
    Optional<CareUnit> findUnit(String organizationId, String unitId);

    /**
     * List all eligible relationships verified for a patient in this tenant.
     *
     * @param organizationId active organization
     * @param patientId      patient identifier
     * @return relationships, including expired or consumed ones for validation
     */
    List<EligibleRelationship> relationshipsFor(String organizationId, String patientId);

    /**
     * Find one eligible relationship by its opaque identifier.
     *
     * @param organizationId active organization
     * @param relationshipId relationship identifier
     * @return the relationship when visible in this tenant
     */
    Optional<EligibleRelationship> relationship(String organizationId, String relationshipId);

    /**
     * Mark a single-use invitation as consumed.
     *
     * @param organizationId active organization
     * @param relationshipId relationship identifier
     */
    void consumeRelationship(String organizationId, String relationshipId);

    /**
     * Find the connection already granted for a verified source reference.
     *
     * @param organizationId active organization
     * @param patientId      patient identifier
     * @param unitId         recipient unit
     * @param reference      verified source reference
     * @return the existing connection, if any
     */
    Optional<PatientUnitConnection> findConnectionByReference(
            String organizationId, String patientId, String unitId, String reference);

    /**
     * List connections granted to a patient in this tenant.
     *
     * @param organizationId active organization
     * @param patientId      patient identifier
     * @return the patient's connections
     */
    List<PatientUnitConnection> connectionsFor(String organizationId, String patientId);

    /**
     * Find one connection by identifier in this tenant.
     *
     * @param organizationId active organization
     * @param connectionId   connection identifier
     * @return the connection when visible in this tenant
     */
    Optional<PatientUnitConnection> findConnection(String organizationId, String connectionId);

    /**
     * Insert a new active connection, enforcing durable business uniqueness.
     *
     * @param organizationId active organization
     * @param connection     connection to store
     * @return the authoritative stored connection; a concurrent caller with the
     *         same business key receives the already-stored connection
     */
    PatientUnitConnection insertConnection(String organizationId, PatientUnitConnection connection);

    /**
     * Record revocation of an existing connection.
     *
     * @param organizationId active organization
     * @param connectionId   connection identifier
     * @param revokedAt      server revocation instant
     */
    void revokeConnection(String organizationId, String connectionId, Instant revokedAt);

    /**
     * Append one immutable, non-sensitive authorization fact.
     *
     * @param audit fact to append
     */
    void appendAudit(MessagingAudit audit);

    /**
     * Read the audit trail of one tenant.
     *
     * @param organizationId active organization
     * @return recorded facts in insertion order
     */
    List<MessagingAudit> auditEvents(String organizationId);
}
