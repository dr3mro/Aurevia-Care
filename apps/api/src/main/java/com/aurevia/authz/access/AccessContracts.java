package com.aurevia.authz.access;

import java.time.Instant;

/**
 * Immutable command and projection records for the IAM-06 access-control slice.
 *
 * <p>Organization, actor, role and time are never accepted from a client: they are
 * derived from the authenticated tenant context and the injected clock. These
 * records only carry tenant-local identifiers and the human-supplied justification.</p>
 */
public final class AccessContracts {

    /**
     * Create the record holder; command and projection records are constructed through it.
     */
    public AccessContracts() {
    }

    /**
     * Command to grant time-limited emergency access to the authenticated clinician.
     *
     * @param facilityCode   tenant-local facility code the grant is scoped to
     * @param reason         human justification for the break-glass access
     * @param durationMinutes requested lifetime in minutes, 1 to 60
 * @return this record
 */
public record EmergencyGrantRequest(String facilityCode, String reason, int durationMinutes) {
    }

    /**
     * Read-only projection of one emergency access grant.
     *
     * @param id           tenant-local grant identifier
     * @param subject      clinician subject the grant belongs to
     * @param facilityCode facility the grant is scoped to
     * @param roleKey      role that qualified the grant
     * @param reason       recorded justification
     * @param grantedAt    ISO-8601 instant the grant started
     * @param expiresAt    ISO-8601 instant the grant stops being effective
     * @param status       {@code active}, {@code revoked} or {@code reviewed}
 * @return this record
 */
public record EmergencyGrantView(String id, String subject, String facilityCode, String roleKey,
                                     String reason, String grantedAt, String expiresAt, String status) {
    }

    /**
     * Command to end an emergency grant early.
     *
     * @param reason recorded reason for the early revocation
 * @return this record
 */
public record RevokeRequest(String reason) {
    }

    /**
     * Command to record an independent review of an emergency grant.
     *
     * @param note   reviewer's finding; the grantee cannot review their own grant
     * @param outcome closure outcome, or {@code null} when a legacy caller omits it
     * @return this record
     */
public record ReviewRequest(String note, String outcome) {
        /**
         * Create a review command without an explicit outcome, as legacy callers and
         * the backward-compatible {@code {"note":...}} JSON body do. The domain
         * service substitutes the explicit recorded outcome before persisting it.
         *
         * @param note reviewer's finding
         */
        public ReviewRequest(String note) {
            this(note, null);
        }
    }

    /**
     * Command to open a platform support incident.
     *
     * @param reference       tenant-local incident reference
     * @param reason          why support access is needed
     * @param windowMinutes   lifetime of the approval window in minutes, 1 to 240
 * @return this record
 */
public record SupportIncidentRequest(String reference, String reason, int windowMinutes) {
    }

    /**
     * Read-only projection of a platform support incident.
     *
     * @param reference  tenant-local incident reference
     * @param subject    support subject the incident is bound to
     * @param reason     recorded justification
     * @param status     {@code pending} or {@code approved}
     * @param createdAt  ISO-8601 instant the incident was opened
     * @param approvedBy approver subject, or {@code null}
     * @param approvedAt ISO-8601 instant of approval, or {@code null}
     * @param expiresAt  ISO-8601 instant the approval window closes
 * @return this record
 */
public record SupportIncidentView(String reference, String subject, String reason, String status,
                                      String createdAt, String approvedBy, String approvedAt,
                                      String expiresAt) {
    }

    /**
     * Command to test whether support access is currently authorized.
     *
     * @param reference tenant-local incident reference to check
 * @return this record
 */
public record SupportCheckRequest(String reference) {
    }

    /**
     * Explicit access decision with a stable, non-clinical reason code.
     *
     * @param allowed {@code true} only when every IAM-06 condition currently holds
     * @param reason  stable machine-readable reason such as {@code APPROVED_INCIDENT}
 * @return this record
 */
public record AccessDecision(boolean allowed, String reason) {
    }

    /**
     * Database projection of one emergency grant row, including review evidence.
     *
     * @param id           grant identifier as text
     * @param subject      clinician subject
     * @param facilityCode facility code the grant was scoped to
     * @param purpose      recorded business purpose
     * @param reason       recorded justification
     * @param roleKey      qualifying role key
     * @param grantedAt    grant start instant
     * @param expiresAt    grant expiry instant
     * @param revokedAt    early revocation instant, or {@code null}
     * @param reviewedBy   reviewer subject, or {@code null}
     * @param reviewedAt   review instant, or {@code null}
     * @param reviewNote   reviewer finding, or {@code null}
 * @return this record
 */
public record GrantRow(String id, String subject, String facilityCode, String purpose, String reason,
                           String roleKey, Instant grantedAt, Instant expiresAt, Instant revokedAt,
                           String reviewedBy, Instant reviewedAt, String reviewNote) {
    }

    /**
     * Database projection of one platform support incident row.
     *
     * @param id         incident identifier as text
     * @param reference  tenant-local incident reference
     * @param subject    support subject bound to the incident
     * @param reason     recorded justification
     * @param status     {@code pending} or {@code approved}
     * @param approvedBy approver subject, or {@code null}
     * @param approvedAt approval instant, or {@code null}
     * @param createdAt  creation instant
     * @param expiresAt  approval-window expiry instant
 * @return this record
 */
public record IncidentRow(String id, String reference, String subject, String reason, String status,
                              String approvedBy, Instant approvedAt, Instant createdAt, Instant expiresAt) {
    }
}
