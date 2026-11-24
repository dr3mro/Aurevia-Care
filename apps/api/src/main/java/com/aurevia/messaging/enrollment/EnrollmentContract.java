package com.aurevia.messaging.enrollment;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the PAT-03 privacy-safe patient access slice.
 *
 * <p>The types in this class carry no persistence or transport concerns.
 * Enrollment, recovery, lockout, step-up re-authentication and support cases
 * are modelled as separate immutable facts: a generic notification, a staff
 * contact edit or a successful delivery never changes account ownership or
 * grants chart access on its own.</p>
 */
public final class EnrollmentContract {

    private EnrollmentContract() {
    }

    /**
     * Lifecycle of a verified patient account.
     */
    public enum AccountState {
        /** The account is verified and its active chart links are usable. */
        ACTIVE("active"),
        /** Repeated failed verification locked the account until a review. */
        LOCKED("locked");

        private final String wire;

        /**
         * Create a state with its stable wire key.
         *
         */
        AccountState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored state key.
         *
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AccountState from(String value) {
            for (AccountState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown account state: " + value);
        }
    }

    /**
     * Lifecycle of a single-use enrollment invitation.
     */
    public enum InvitationState {
        /** Issued and not yet redeemed. */
        PENDING("pending"),
        /** Redeemed once; never reusable. */
        REDEEMED("redeemed");

        private final String wire;

        /**
         * Create a state with its stable wire key.
         *
         */
        InvitationState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored state key.
         *
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static InvitationState from(String value) {
            for (InvitationState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown invitation state: " + value);
        }
    }

    /**
     * The purpose for which a support case was opened.
     */
    public enum SupportKind {
        /** A disputed identity or chart association. */
        IDENTITY_DISPUTE("identity_dispute"),
        /** An accessibility accommodation request. */
        ACCESSIBILITY("accessibility"),
        /** A request for a copy of authorized records. */
        RECORD_ACCESS("record_access");

        private final String wire;

        /**
         * Create a support kind with its stable wire key.
         *
         */
        SupportKind(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase wire key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored support kind.
         *
         * @return the matching support kind
         * @throws IllegalArgumentException when the key is unknown
         */
        public static SupportKind from(String value) {
            for (SupportKind candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown support kind: " + value);
        }
    }

    /**
     * State of a support case. Resolution is terminal; a new request is a new case.
     */
    public enum CaseState {
        /** Opened and awaiting assignment. */
        OPEN("open"),
        /** Assigned to a named owner for review. */
        IN_REVIEW("in_review"),
        /** Reviewed and resolved by the owning unit. */
        RESOLVED("resolved");

        private final String wire;

        /**
         * Create a case state with its stable wire key.
         *
         */
        CaseState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase wire key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Decide whether a forward transition is permitted.
         *
         * @return {@code true} for the two forward transitions or a no-op
         */
        public boolean canTransitionTo(CaseState next) {
            if (this == next) {
                return true;
            }
            return (this == OPEN && next == IN_REVIEW) || (this == IN_REVIEW && next == RESOLVED);
        }

        /**
         * Parse a stored case state.
         *
         * @return the matching case state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static CaseState from(String value) {
            for (CaseState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown case state: " + value);
        }
    }

    /**
     * Sensitive change that requires renewed authentication.
     */
    public enum SensitiveAction {
        /** Remove one verified chart link; future access to it stops immediately. */
        UNLINK_CHART("unlink_chart"),
        /** Replace the recovery contact without changing account ownership. */
        CHANGE_CONTACT("change_contact");

        private final String wire;

        /**
         * Create a sensitive action with its stable wire key.
         *
         */
        SensitiveAction(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase wire key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored sensitive action.
         *
         * @return the matching sensitive action
         * @throws IllegalArgumentException when the key is unknown
         */
        public static SensitiveAction from(String value) {
            for (SensitiveAction candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown sensitive action: " + value);
        }
    }

    /**
     * Append-only event vocabulary. Every fact is recorded separately and never
     * merged into a delivery or a generic notification.
     */
    public enum EventType {
        /** A site-verified invitation was issued. */
        INVITATION_ISSUED("invitation_issued"),
        /** An invitation was redeemed into a verified account. */
        ENROLL_SUCCESS("enroll_success"),
        /** A recovery or enrollment verification failed. */
        VERIFICATION_FAILED("verification_failed"),
        /** The account reached its failed-attempt threshold. */
        ACCOUNT_LOCKED("account_locked"),
        /** An authorized reviewer cleared a lockout. */
        ACCOUNT_UNLOCKED("account_unlocked"),
        /** A single-use recovery code was issued to a verified contact. */
        RECOVERY_ISSUED("recovery_issued"),
        /** Re-verification restored access. */
        RECOVERY_SUCCESS("recovery_success"),
        /** A short-lived re-authentication token was issued. */
        REAUTH_ISSUED("reauth_issued"),
        /** A sensitive change was applied after step-up authentication. */
        SENSITIVE_CHANGE("sensitive_change"),
        /** A staff contact edit left account ownership intact. */
        CONTACT_CHANGED("contact_changed"),
        /** A chart link was removed and future access ended. */
        CHART_UNLINKED("chart_unlinked"),
        /** A support case was opened. */
        CASE_OPENED("case_opened"),
        /** A support case was assigned to a named owner. */
        CASE_ASSIGNED("case_assigned"),
        /** A support case was resolved. */
        CASE_RESOLVED("case_resolved"),
        /** A chart access check was denied. */
        ACCESS_DENIED("access_denied");

        private final String wire;

        /**
         * Create an event type with its stable wire key.
         *
         */
        EventType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase event key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored event key.
         *
         * @return the matching event type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static EventType from(String value) {
            for (EventType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown access event: " + value);
        }
    }

    /**
     * The verified care relationship that alone may justify a chart link.
     *
     * <p>This type deliberately has no demographic fields: a shared phone, a
     * name or a date of birth can never link a record or an account on its own
     * (IAM-05). The site must mark the relationship as verified.</p>
     *
     * @return the immutable value
     */
    public record RelationshipRef(String relationshipId, boolean siteVerified) {
    }

    /**
     * A single-use, expiring enrollment invitation bound to one verified chart.
     *
     * @return the immutable value
     */
    public record Invitation(String code, String organizationId, String chartId, String factDigest,
                             Instant expiresAt, InvitationState state, int failedAttempts,
                             String issuedBy, Instant issuedAt) {
    }

    /**
     * A verified patient account with independently verified chart links.
     *
     * @return the immutable value
     */
    public record PatientAccount(String id, String organizationId, String subject, Set<String> linkedChartIds,
                                 String factDigest, AccountState state, int failedAttempts,
                                 String contactDigest, Instant createdAt) {
        /**
         * Freeze the chart link set so callers cannot add access after construction.
         *
         * @throws NullPointerException when linkedChartIds is null
         */
        public PatientAccount {
            linkedChartIds = Set.copyOf(linkedChartIds);
        }

        /**
         * Return the immutable active chart links.
         *
         * @return active chart identifiers
         */
        @Override
        public Set<String> linkedChartIds() {
            return Set.copyOf(linkedChartIds);
        }
    }

    /**
     * A short-lived, single-use credential for recovery or step-up re-authentication.
     *
     * @return the immutable value
     */
    public record AccessToken(String token, String organizationId, String subject, String purpose,
                              Instant expiresAt, boolean used) {
    }

    /**
     * A support case for an identity dispute, accessibility need or record access request.
     *
     * @return the immutable value
     */
    public record SupportCase(String id, String organizationId, String patientId, SupportKind kind,
                              String summary, CaseState state, String assignee, Instant openedAt) {
    }

    /**
     * One immutable access fact.
     *
     * @return the immutable value
     */
    public record AccessEvent(String id, String organizationId, String subject, EventType eventType,
                              String resourceId, String outcome, String detail, Instant occurredAt) {
    }

    /**
     * A generic notification. The type has no clinical text field, so a patient
     * notification can never carry a chart identifier, a diagnosis or a reason.
     *
     * @return the immutable value
     */
    public record GenericNotification(String subject, String organizationId, String eventType, Instant occurredAt) {
    }

    /**
     * The outcome of a chart access check.
     *
     * @return the immutable value
     */
    public record AccessDecision(boolean allowed, String reason, String chartId) {
    }

    /**
     * An account together with its support cases and access audit trail.
     *
     * @return the immutable value
     */
    public record AccountView(PatientAccount account, List<SupportCase> cases, List<AccessEvent> events) {
        /**
         * Defensively copy both lists.
         *
         */
        public AccountView {
            cases = List.copyOf(cases);
            events = List.copyOf(events);
        }
    }

    /**
     * Issue a site-verified single-use invitation for one chart.
     *
     * @param chartId       verified chart identifier
     * @param relationship  site-verified relationship that authorizes the link
     * @param factDigest    digest of the site-verified identity facts
     * @param ttlSeconds    requested validity window in seconds
     * @return the issue command
     */
    public record IssueInvitationRequest(String chartId, RelationshipRef relationship,
                                         String factDigest, Long ttlSeconds) {
    }

    /**
     * Redeem an invitation with the matching identity facts.
     *
     * @param code       invitation code
     * @param factDigest digest of the presented identity facts
     * @return the enrollment command
     */
    public record EnrollRequest(String code, String factDigest) {
    }

    /**
     * Restore access with a single-use recovery code and re-verified facts.
     *
     * @param accountId  account identifier
     * @param code       single-use recovery code
     * @param factDigest digest of the presented identity facts
     * @return the recovery command
     */
    public record RecoverRequest(String accountId, String code, String factDigest) {
    }

    /**
     * Apply a sensitive change after renewed authentication.
     *
     * @param accountId account identifier
     * @param action    sensitive action being confirmed
     * @param token     fresh re-authentication token
     * @param value     chart identifier for an unlink or contact digest for a change
     * @return the sensitive-change command
     */
    public record SensitiveChangeRequest(String accountId, SensitiveAction action, String token, String value) {
    }

    /**
     * Replace the recovery contact on behalf of a patient.
     *
     * @param accountId    account identifier
     * @param contactDigest digest of the new contact, never the raw value
     * @return the contact-change command
     */
    public record ChangeContactRequest(String accountId, String contactDigest) {
    }

    /**
     * Open a support case for an identity dispute, accessibility need or record request.
     *
     * @param patientId subject or patient the case concerns
     * @param kind      support purpose
     * @param summary   non-sensitive summary
     * @return the open-case command
     */
    public record OpenCaseRequest(String patientId, SupportKind kind, String summary) {
    }
}
