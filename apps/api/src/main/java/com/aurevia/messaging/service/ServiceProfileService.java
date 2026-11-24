package com.aurevia.messaging.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.messaging.units.MessagingInputs;
import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileHistory;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.messaging.service.ServiceProfileContract.PublishProfileRequest;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceAvailability;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.messaging.service.ServiceProfileContract.SupervisorSnapshot;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-06 domain service for named-approved, versioned service profiles and
 * derived unit-supervisor counters.
 *
 * <p>Every protected method derives the organization and subject from the
 * request context, re-checks active membership and role, and re-checks the
 * caller's relationship to the exact unit. A profile is never served unless a
 * named approver approved it and its effective date has been reached. All
 * decisions and denials are audited in the same slice, and cross-tenant or
 * unrelated units are concealed with 404.</p>
 *
 * <p>Publish also enforces the site's approved urgent and downtime channels
 * (MSG-06): both values must appear in an effective, named-approved tenant
 * policy before a draft can be stored. A missing policy or an unapproved code
 * fails closed through the audited denial path.</p>
 */
@Service
public class ServiceProfileService {

    /** Tenant configuration key holding the comma-separated approved urgent channels. */
    private static final String URGENT_CHANNELS_KEY = "msg06.approved_urgent_channels";

    /** Tenant configuration key holding the comma-separated approved downtime channels. */
    private static final String DOWNTIME_CHANNELS_KEY = "msg06.approved_downtime_channels";

    /**
     * Conservative response target used only for supervisor surfacing when a
     * unit has no approved effective profile. Treating every awaiting-staff
     * item as already due exposes a coverage gap instead of concealing the
     * whole queue; it is not a site policy or a clinical response promise.
     */
    private static final int NO_PROFILE_RESPONSE_TARGET_MINUTES = 0;

    private final ServiceProfileStore store;
    private final ServiceProfileActions actions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param store   tenant-scoped profile store
     * @param actions bounded action matrix
     * @param clock   application clock for approval, effectiveness and snapshots
     */
    public ServiceProfileService(ServiceProfileStore store, ServiceProfileActions actions, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
    }

    /**
     * Return the effective, named-approved profile of one connected unit.
     *
     * @param unitId recipient unit identifier
     * @return the effective profile
     * @throws ApiException on invalid input, denial, concealment or no effective version
     */
    public ServiceProfile viewProfile(String unitId) {
        String unit = validateUnit(unitId);
        TenantContext context = requireContext();
        authorize(context, ServiceProfileActions.PROFILE_VIEW, unit);
        requireUnitAccess(context, ServiceProfileActions.PROFILE_VIEW, unit);
        Instant at = clock.instant();
        ServiceProfile profile = store.effectiveProfile(context.organizationId(), unit, at)
                .filter(candidate -> candidate.enablesUnitAt(at))
                .orElseThrow(() -> deny(context, ServiceProfileActions.PROFILE_VIEW, unit, 404,
                        "NOT_FOUND", "no effective service profile for this unit"));
        audit(context, ServiceProfileActions.PROFILE_VIEW, profile.id(), "SUCCESS");
        return profile;
    }

    /**
     * Return the zone-aware availability projection of one connected unit.
     *
     * @param unitId recipient unit identifier
     * @return open state and, when closed, the next handling instant
     * @throws ApiException on invalid input, denial, concealment or no effective version
     */
    public ServiceAvailability availability(String unitId) {
        String unit = validateUnit(unitId);
        TenantContext context = requireContext();
        authorize(context, ServiceProfileActions.AVAILABILITY_VIEW, unit);
        requireUnitAccess(context, ServiceProfileActions.AVAILABILITY_VIEW, unit);
        Instant at = clock.instant();
        ServiceProfile profile = store.effectiveProfile(context.organizationId(), unit, at)
                .filter(candidate -> candidate.enablesUnitAt(at))
                .orElseThrow(() -> deny(context, ServiceProfileActions.AVAILABILITY_VIEW, unit, 404,
                        "NOT_FOUND", "no effective service profile for this unit"));
        ServiceAvailability result = ServiceHoursCalculator.calculate(profile.hours(), at);
        audit(context, ServiceProfileActions.AVAILABILITY_VIEW, profile.id(), "SUCCESS");
        return result;
    }

    /**
     * Return the immutable version history of one unit's profiles.
     *
     * @param unitId recipient unit identifier
     * @return versions in ascending order
     * @throws ApiException on invalid input, denial or concealment
     */
    public ProfileHistory history(String unitId) {
        String unit = validateUnit(unitId);
        TenantContext context = requireContext();
        authorize(context, ServiceProfileActions.PROFILE_HISTORY, unit);
        if (!store.unitExists(context.organizationId(), unit)) {
            throw deny(context, ServiceProfileActions.PROFILE_HISTORY, unit, 404, "NOT_FOUND", "unit not found");
        }
        ProfileHistory history = new ProfileHistory(store.versions(context.organizationId(), unit));
        audit(context, ServiceProfileActions.PROFILE_HISTORY, unit, "SUCCESS");
        return history;
    }

    /**
     * Publish a new, unapproved service-profile version.
     *
     * <p>The urgent and downtime channels must be approved for this tenant by
     * an effective named configuration approval. An unapproved or blank code is
     * refused with a structured {@code 422 UNAPPROVED_CHANNEL} and an audited
     * denial before any draft row is written.</p>
     *
     * @param request validated publish command
     * @return the stored draft profile
     * @throws ApiException on invalid input, an unapproved channel, denial,
     *                      concealment or a stale version
     */
    public ServiceProfile publish(PublishProfileRequest request) {
        TenantContext context = requireContext();
        authorize(context, ServiceProfileActions.PROFILE_PUBLISH, "PROFILE");
        String unit = MessagingInputs.uuid(request.unitId(), "unitId");
        if (!store.unitExists(context.organizationId(), unit)) {
            throw deny(context, ServiceProfileActions.PROFILE_PUBLISH, unit, 404, "NOT_FOUND", "unit not found");
        }
        int expected = ServiceProfileInputs.version(request.expectedVersion(), "expectedVersion");
        int current = store.latestVersion(context.organizationId(), unit);
        if (expected != current) {
            throw deny(context, ServiceProfileActions.PROFILE_PUBLISH, unit, 412, "STALE_VERSION",
                    "the profile version changed");
        }
        // Check channels before parsing free text: a blank or unapproved value
        // must reach the audited denial path, not escape from the input parser.
        requireApprovedChannel(context, unit, request.urgentChannel(), URGENT_CHANNELS_KEY, "urgentChannel");
        requireApprovedChannel(context, unit, request.downtimeChannel(), DOWNTIME_CHANNELS_KEY, "downtimeChannel");
        ZoneId zone = ServiceProfileInputs.zone(request.timeZone());
        List<ServiceWindow> windows = ServiceProfileInputs.windows(request.windows());
        List<String> topics = ServiceProfileInputs.list(request.topics(), "topics", 20, 64);
        List<LocalizedText> instructions = ServiceProfileInputs.instructions(request.urgentInstructions());
        ServiceProfile draft = new ServiceProfile(MessagingInputs.newId(), context.organizationId(), unit,
                current + 1, ProfileStatus.DRAFT, new ServiceHours(zone, windows),
                ServiceProfileInputs.minutes(request.responseTargetMinutes(), "responseTargetMinutes", 1440),
                topics, instructions,
                ServiceProfileInputs.safeText(request.queueOwner(), "queueOwner", 128),
                ServiceProfileInputs.list(request.backupCoverage(), "backupCoverage", 10, 128),
                ServiceProfileInputs.safeText(request.escalationChannel(), "escalationChannel", 128),
                ServiceProfileInputs.safeText(request.urgentChannel(), "urgentChannel", 128),
                ServiceProfileInputs.safeText(request.downtimeChannel(), "downtimeChannel", 128),
                context.subject(), null, null, null,
                ServiceProfileInputs.instant(request.effectiveFrom(), "effectiveFrom"), clock.instant());
        ServiceProfile stored = store.insertProfile(context.organizationId(), draft);
        audit(context, ServiceProfileActions.PROFILE_PUBLISH, stored.id(), "SUCCESS");
        return stored;
    }

    /**
     * Name-approve a draft profile with a distinct approver and a version
     * precondition. Approval is immutable: an approved version cannot be
     * re-approved or edited.
     *
     * @param profileId       profile identifier
     * @param expectedVersion version from the parsed If-Match precondition
     * @param note            named approval rationale
     * @return the approved profile
     * @throws ApiException on invalid input, denial, concealment or a stale state
     */
    public ServiceProfile approve(String profileId, int expectedVersion, String note) {
        String id = MessagingInputs.uuid(profileId, "profileId");
        String approvalNote = ServiceProfileInputs.safeText(note, "approvalNote", 500);
        TenantContext context = requireContext();
        authorize(context, ServiceProfileActions.PROFILE_APPROVE, id);
        ServiceProfile current = store.findProfile(context.organizationId(), id)
                .orElseThrow(() -> deny(context, ServiceProfileActions.PROFILE_APPROVE, id, 404,
                        "NOT_FOUND", "profile not found"));
        if (current.version() != expectedVersion) {
            throw deny(context, ServiceProfileActions.PROFILE_APPROVE, id, 412, "STALE_VERSION",
                    "the profile version changed");
        }
        if (current.status() != ProfileStatus.DRAFT) {
            throw deny(context, ServiceProfileActions.PROFILE_APPROVE, id, 409, "ALREADY_APPROVED",
                    "the profile is already approved");
        }
        if (context.subject().equals(current.createdBy())) {
            throw deny(context, ServiceProfileActions.PROFILE_APPROVE, id, 409, "SAME_APPROVER",
                    "approval requires a different named approver");
        }
        Instant at = clock.instant();
        ServiceProfile approved = new ServiceProfile(current.id(), current.organizationId(), current.unitId(),
                current.version(), ProfileStatus.APPROVED, current.hours(), current.responseTargetMinutes(),
                current.topics(), current.urgentInstructions(), current.queueOwner(), current.backupCoverage(),
                current.escalationChannel(), current.urgentChannel(), current.downtimeChannel(),
                current.createdBy(), context.subject(), at, approvalNote, current.effectiveFrom(), current.createdAt());
        ServiceProfile stored = store.updateProfile(context.organizationId(), approved);
        audit(context, ServiceProfileActions.PROFILE_APPROVE, stored.id(), "SUCCESS");
        return stored;
    }

    /**
     * Return derived supervisor counters for an assigned unit.
     *
     * @param unitId recipient unit identifier
     * @return the derived snapshot
     * @throws ApiException on invalid input, denial or concealment
     */
    public SupervisorSnapshot supervisorSnapshot(String unitId) {
        String unit = validateUnit(unitId);
        TenantContext context = requireContext();
        authorize(context, ServiceProfileActions.SUPERVISOR_VIEW, unit);
        requireUnitAccess(context, ServiceProfileActions.SUPERVISOR_VIEW, unit);
        int target = store.effectiveProfile(context.organizationId(), unit, clock.instant())
                .map(ServiceProfile::responseTargetMinutes)
                .orElse(NO_PROFILE_RESPONSE_TARGET_MINUTES);
        SupervisorSnapshot snapshot = SupervisorSnapshotCalculator.compute(context.organizationId(), unit,
                target, store.workItems(context.organizationId(), unit), clock.instant());
        audit(context, ServiceProfileActions.SUPERVISOR_VIEW, unit, "SUCCESS");
        return snapshot;
    }

    /**
     * Require a time-sensitive care channel to be one the site approved in its
     * versioned configuration fold.
     *
     * <p>Fails closed: a blank code, an absent policy or any code outside the
     * approved set is refused through the service's audited denial path, which
     * records a {@code DENIED} audit for the unit. Time-sensitive care must never
     * be directed at an unapproved channel.</p>
     *
     * @param context   authenticated tenant context
     * @param unit      recipient unit used as the audit resource
     * @param channel   supplied channel code, possibly absent
     * @param policyKey tenant-local approved-channel configuration key
     * @param field     request field name used in the denial message
     * @throws ApiException when the channel is blank or not site-approved
     */
    private void requireApprovedChannel(TenantContext context, String unit, String channel,
                                        String policyKey, String field) {
        String candidate = Objects.toString(channel, "").strip();
        Set<String> approved = store.approvedChannels(context.organizationId(), policyKey);
        if (candidate.isEmpty() || !approved.contains(candidate)) {
            throw deny(context, ServiceProfileActions.PROFILE_PUBLISH, unit, 422,
                    "UNAPPROVED_CHANNEL", field + " is not an approved site channel");
        }
    }

    private String validateUnit(String unitId) {
        return MessagingInputs.uuid(unitId, "unitId");
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private void authorize(TenantContext context, String action, String resourceId) {
        if (!store.hasActiveMembership(context.organizationId(), context.subject())) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "no active membership");
        }
        boolean permitted = store.activeRoles(context.organizationId(), context.subject()).stream()
                .anyMatch(role -> actions.allows(action, role));
        if (!permitted) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    private void requireUnitAccess(TenantContext context, String action, String unit) {
        if (!store.unitExists(context.organizationId(), unit)) {
            throw deny(context, action, unit, 404, "NOT_FOUND", "unit not found");
        }
        List<String> roles = store.activeRoles(context.organizationId(), context.subject());
        boolean permitted = roles.contains("patient")
                ? store.hasConnectedChart(context.organizationId(), context.subject(), unit)
                : roles.contains("tenant_admin") || roles.contains("privacy_auditor")
                        || store.isUnitAssigned(context.organizationId(), context.subject(), unit);
        if (!permitted) {
            throw deny(context, action, unit, 404, "NOT_FOUND", "unit not found");
        }
    }

    private ApiException deny(TenantContext context, String action, String resourceId,
                              int status, String code, String message) {
        audit(context, action, resourceId, status == 404 ? "NOT_FOUND" : status == 409 ? "CONFLICT" : "DENIED");
        return new ApiException(status, code, message);
    }

    private void audit(TenantContext context, String action, String resourceId, String outcome) {
        store.appendAudit(new MessagingAudit(context.organizationId(), context.subject(),
                action, resourceId, outcome, clock.instant()));
    }
}
