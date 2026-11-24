package com.aurevia.messaging.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the MSG-06 service-profile slice.
 *
 * <p>Every record here is a passive, immutable value. Governance decisions
 * (tenant derivation, membership and role checks, approval, audit) live in
 * {@link com.aurevia.messaging.service.ServiceProfileService}; time math lives in
 * {@link com.aurevia.messaging.service.ServiceHoursCalculator}. A profile is tenant-owned and carries its
 * organization explicitly in every store call, so identical local identifiers
 * in two tenants remain isolated.</p>
 */
public final class ServiceProfileContract {

    private ServiceProfileContract() {
    }

    /** Governance state of a published service profile (ADM-01). */
    public enum ProfileStatus {
        /** Saved but not yet approved; never served to patients or supervisors. */
        DRAFT("draft"),
        /** Named-approved and eligible to become effective at its effective date. */
        APPROVED("approved");

        private final String wire;

        /**
         * Bind the stable wire key for this status.
         *
         * @param wire lowercase key used in JSON and persisted values
         */
        ProfileStatus(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase status key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored or supplied status key.
         *
         * @return the matching status
         * @throws IllegalArgumentException when the key is unknown
         */
        public static ProfileStatus from(String value) {
            for (ProfileStatus candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown profile status: " + value);
        }
    }

    /** Lifecycle of a staff work item that feeds the supervisor snapshot. */
    public enum WorkState {
        /** Waiting for a staff member to pick up the item. */
        AWAITING_STAFF("awaiting_staff"),
        /** Waiting for the patient; no staff response is due yet. */
        AWAITING_PATIENT("awaiting_patient"),
        /** Handled; excluded from all open-work counters. */
        RESOLVED("resolved"),
        /** Closed and read-only; excluded from all open-work counters. */
        CLOSED("closed");

        private final String wire;

        /**
         * Bind the stable wire key for this state.
         *
         * @param wire lowercase key used in JSON and persisted values
         */
        WorkState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored or supplied state key.
         *
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static WorkState from(String value) {
            for (WorkState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown work state: " + value);
        }
    }

    /**
     * One approved piece of guidance copy in a supported language.
     *
     * @return the immutable value
     */
    public record LocalizedText(String language, String value) {
    }

    /**
     * A local wall-clock service window. An end time that is not after the start
     * time means the window wraps into the next local day; equal times are
     * rejected by validation so "24/7" can never be implied accidentally.
     *
     * @return the immutable window
     */
    public record ServiceWindow(DayOfWeek day, LocalTime start, LocalTime end) {

        /**
         * Whether this window crosses local midnight.
         *
         * @return {@code true} when the end time is on the following local day
         */
        public boolean wraps() {
            return !end.isAfter(start);
        }
    }

    /**
     * Service hours in the unit's IANA time zone.
     *
     * @return the immutable hours value
     */
    public record ServiceHours(ZoneId zone, List<ServiceWindow> windows) {

        /**
         * Defensively copy the window list.
         *
         */
        public ServiceHours {
            windows = List.copyOf(windows);
        }
    }

    /**
     * Availability projection computed from service hours at one instant.
     *
     * @return the immutable projection; {@code nextHandlingAt} is {@code null}
     *         when the unit is open or publishes no windows
     */
    public record ServiceAvailability(boolean open, Instant nextHandlingAt, String timeZone) {
    }

    /**
     * A versioned, named-approved MSG-06 service profile for one care unit.
     *
     * <p>Published and approved versions are immutable; a corrected profile is a
     * new version. Only an approved profile whose effective date has been reached
     * is ever served.</p>
     *
     * @return the immutable profile
     */
    public record ServiceProfile(String id, String organizationId, String unitId, int version,
                                 ProfileStatus status, ServiceHours hours, int responseTargetMinutes,
                                 List<String> topics, List<LocalizedText> urgentInstructions,
                                 String queueOwner, List<String> backupCoverage, String escalationChannel,
                                 String urgentChannel, String downtimeChannel, String createdBy,
                                 String approvedBy, Instant approvedAt, String approvalNote,
                                 Instant effectiveFrom, Instant createdAt) {

        /**
         * Defensively copy all lists.
         *
         */
        public ServiceProfile {
            topics = List.copyOf(topics);
            urgentInstructions = List.copyOf(urgentInstructions);
            backupCoverage = List.copyOf(backupCoverage);
        }

        /**
         * Whether this version is approved and its effective date has arrived.
         *
         * @return {@code true} only for an approved, effective version
         */
        public boolean effectiveAt(Instant at) {
            return status == ProfileStatus.APPROVED && !at.isBefore(effectiveFrom);
        }

        /**
         * Whether this profile enables the unit for patient messaging at one instant
         * (MSG-06). An approved, effective profile alone is not enough: the unit is
         * only enabled while it still names a queue owner and at least one named
         * backup. This is a domain-level invariant that defends against a persisted
         * profile whose staffing has been lost or only partially migrated; it does
         * not rely on the store having enforced the same rule.
         *
         * @param at evaluation instant
         * @return {@code true} only when the unit may be shown as enabled to patients
         */
        public boolean enablesUnitAt(Instant at) {
            return effectiveAt(at) && queueOwner != null && !queueOwner.isBlank()
                    && backupCoverage.stream().anyMatch(name -> !name.isBlank());
        }

        /**
         * Compute the response deadline for work opened at the given instant.
         *
         * @return the elapsed-time response deadline
         */
        public Instant dueAt(Instant openedAt) {
            return openedAt.plus(Duration.ofMinutes(responseTargetMinutes));
        }
    }

    /** The immutable version history of one unit's service profiles. */
    public record ProfileHistory(List<ServiceProfile> versions) {

        /**
         * Defensively copy the version list.
         *
         */
        public ProfileHistory {
            versions = List.copyOf(versions);
        }
    }

    /**
     * A non-sensitive work fact used to derive supervisor counters. It carries
     * no patient identifier, topic text or clinical payload.
     *
     * @return the immutable fact
     */
    public record WorkItemFact(String organizationId, String id, String unitId, WorkState state,
                               String assignee, Instant createdAt, Instant lastStaffResponseAt,
                               boolean notificationFailed) {
    }

    /**
     * Derived supervisor counters for one unit at one instant.
     *
     * @return the immutable snapshot
     */
    public record SupervisorSnapshot(String organizationId, String unitId, int backlog, int unassigned,
                                     int failedNotifications, int overdueResponses, Instant generatedAt) {
    }

    /**
     * Publish command. Tenant, subject and timestamps are derived by the server;
     * only explicit policy values are accepted from the caller.
     *
     * @return the immutable command
     */
    public record PublishProfileRequest(String unitId, String timeZone, List<String> windows,
                                        Long responseTargetMinutes,
                                        String queueOwner, List<String> backupCoverage,
                                        String escalationChannel, String urgentChannel, String downtimeChannel,
                                        List<String> topics, List<LocalizedText> urgentInstructions,
                                        String effectiveFrom, Integer expectedVersion) {

        /**
         * Defensively copy all list fields.
         *
         */
        public PublishProfileRequest {
            windows = List.copyOf(windows);
            backupCoverage = List.copyOf(backupCoverage);
            topics = List.copyOf(topics);
            urgentInstructions = List.copyOf(urgentInstructions);
        }
    }
}
