package com.aurevia.smartclinic.messaging.service;

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
 * {@link com.aurevia.smartclinic.messaging.service.ServiceProfileService}; time math lives in
 * {@link com.aurevia.smartclinic.messaging.service.ServiceHoursCalculator}. A profile is tenant-owned and carries its
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
         * @param value stored key
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
         * @param value stored key
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
     * @param language BCP-47 language tag ({@code en} or {@code ar})
     * @param value    approved, non-sensitive static text
     * @return the immutable value
     */
    public record LocalizedText(String language, String value) {
    }

    /**
     * A local wall-clock service window. An end time that is not after the start
     * time means the window wraps into the next local day; equal times are
     * rejected by validation so "24/7" can never be implied accidentally.
     *
     * @param day   weekday the window starts on
     * @param start inclusive local start time
     * @param end   exclusive local end time
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
     * @param zone    IANA zone identifier; never a fixed offset or the server default
     * @param windows ordered service windows
     * @return the immutable hours value
     */
    public record ServiceHours(ZoneId zone, List<ServiceWindow> windows) {

        /**
         * Defensively copy the window list.
         *
         * @param zone    service time zone
         * @param windows service windows
         */
        public ServiceHours {
            windows = List.copyOf(windows);
        }
    }

    /**
     * Availability projection computed from service hours at one instant.
     *
     * @param open           whether the unit is inside a service window now
     * @param nextHandlingAt absolute UTC instant the routine queue next opens,
     *                       or {@code null} when open or when no windows exist
     * @param timeZone       facility IANA zone label for display
     * @return the immutable projection
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
     * @param id                  opaque profile identifier
     * @param organizationId      owning tenant
     * @param unitId              recipient care unit
     * @param version             monotonically increasing per unit
     * @param status              governance state
     * @param hours               service hours and facility zone
     * @param responseTargetMinutes target for a first staff response
     * @param topics              approved topic keys for this unit
     * @param urgentInstructions  approved static urgent guidance by language
     * @param queueOwner          named owner of the unit work queue
     * @param backupCoverage      named backup owners
     * @param escalationChannel   site-approved key for overdue escalation
     * @param urgentChannel       site-approved key for time-sensitive care
     * @param downtimeChannel     site-approved key for downtime contact
     * @param createdBy           authenticated subject that published the profile
     * @param approvedBy          distinct named approver, or {@code null} while draft
     * @param approvedAt          approval instant, or {@code null} while draft
     * @param approvalNote        named approval rationale
     * @param effectiveFrom       instant from which the profile is effective
     * @param createdAt           server creation instant
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
         * @param id                  profile identifier
         * @param organizationId      owning tenant
         * @param unitId              recipient unit
         * @param version             profile version
         * @param status              governance state
         * @param hours               service hours
         * @param responseTargetMinutes response target
         * @param topics              approved topics
         * @param urgentInstructions  urgent guidance
         * @param queueOwner          named queue owner
         * @param backupCoverage      named backups
         * @param escalationChannel   site-approved channel key for overdue escalation
         * @param urgentChannel       urgent channel key
         * @param downtimeChannel     downtime channel key
         * @param createdBy           publisher subject
         * @param approvedBy          approver subject
         * @param approvedAt          approval instant
         * @param approvalNote        approval rationale
         * @param effectiveFrom       effective instant
         * @param createdAt           creation instant
         */
        public ServiceProfile {
            topics = List.copyOf(topics);
            urgentInstructions = List.copyOf(urgentInstructions);
            backupCoverage = List.copyOf(backupCoverage);
        }

        /**
         * Whether this version is approved and its effective date has arrived.
         *
         * @param at instant to evaluate
         * @return {@code true} only for an approved, effective version
         */
        public boolean effectiveAt(Instant at) {
            return status == ProfileStatus.APPROVED && !at.isBefore(effectiveFrom);
        }

        /**
         * Compute the response deadline for work opened at the given instant.
         *
         * @param openedAt work-opening instant
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
         * @param versions versions in ascending order
         */
        public ProfileHistory {
            versions = List.copyOf(versions);
        }
    }

    /**
     * A non-sensitive work fact used to derive supervisor counters. It carries
     * no patient identifier, topic text or clinical payload.
     *
     * @param organizationId      owning tenant
     * @param id                  opaque work item identifier
     * @param unitId              recipient unit
     * @param state               workflow state
     * @param assignee            named assignee, or {@code null} when unassigned
     * @param createdAt           work-opening instant
     * @param lastStaffResponseAt last staff response instant, or {@code null}
     * @param notificationFailed  whether a notification dispatch failed
     * @return the immutable fact
     */
    public record WorkItemFact(String organizationId, String id, String unitId, WorkState state,
                               String assignee, Instant createdAt, Instant lastStaffResponseAt,
                               boolean notificationFailed) {
    }

    /**
     * Derived supervisor counters for one unit at one instant.
     *
     * @param organizationId      owning tenant
     * @param unitId              recipient unit
     * @param backlog             open work items
     * @param unassigned          open items with no named assignee
     * @param failedNotifications open items with a failed notification
     * @param overdueResponses    open items awaiting a response past their deadline
     * @param generatedAt         snapshot instant
     * @return the immutable snapshot
     */
    public record SupervisorSnapshot(String organizationId, String unitId, int backlog, int unassigned,
                                     int failedNotifications, int overdueResponses, Instant generatedAt) {
    }

    /**
     * Publish command. Tenant, subject and timestamps are derived by the server;
     * only explicit policy values are accepted from the caller.
     *
     * @param unitId                 recipient unit
     * @param timeZone                IANA zone identifier
     * @param windows                 service windows as {@code DAY|HH:mm|HH:mm}
     * @param responseTargetMinutes   first-response target
     * @param queueOwner              named queue owner
     * @param backupCoverage          named backups
     * @param escalationChannel       site-approved channel key used for overdue escalation
     * @param urgentChannel           site-approved urgent channel key
     * @param downtimeChannel         site-approved downtime channel key
     * @param topics                  approved topic keys
     * @param urgentInstructions      approved static guidance by language
     * @param effectiveFrom           effective instant
     * @param expectedVersion         compare-and-set expected new version
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
         * @param unitId                 recipient unit
         * @param timeZone                IANA zone
         * @param windows                 service windows
         * @param responseTargetMinutes   response target
         * @param queueOwner              queue owner
         * @param backupCoverage          backups
         * @param escalationChannel       site-approved channel key for overdue escalation
         * @param urgentChannel           urgent channel key
         * @param downtimeChannel         downtime channel key
         * @param topics                  topics
         * @param urgentInstructions      urgent guidance
         * @param effectiveFrom           effective instant
         * @param expectedVersion         expected version
         */
        public PublishProfileRequest {
            windows = List.copyOf(windows);
            backupCoverage = List.copyOf(backupCoverage);
            topics = List.copyOf(topics);
            urgentInstructions = List.copyOf(urgentInstructions);
        }
    }
}
