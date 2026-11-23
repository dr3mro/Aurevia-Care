package com.aurevia.smartclinic.messaging.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.aurevia.smartclinic.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.WorkItemFact;

/**
 * Persistence boundary for the MSG-06 service-profile slice.
 *
 * <p>Every method carries the active organization explicitly, so a caller cannot
 * cross tenant boundaries even when local identifiers are identical. A
 * production adapter must add forced row-level security, an append-only profile
 * and approval table, and a unique effective-profile constraint; this in-memory
 * adapter is a synthetic review and unit-test boundary (G3/G5 gap).</p>
 */
public interface ServiceProfileStore {

    /** @return whether the subject has an active membership in the organization */
    boolean hasActiveMembership(String organizationId, String subject);

    /** @return role keys currently in force for the subject */
    List<String> activeRoles(String organizationId, String subject);

    /** @return whether a care unit exists in the active tenant */
    boolean unitExists(String organizationId, String unitId);

    /** @return whether a verified chart of this account has an active connection to the unit */
    boolean hasConnectedChart(String organizationId, String subject, String unitId);

    /** @return whether the subject is assigned to the unit in the active tenant */
    boolean isUnitAssigned(String organizationId, String subject, String unitId);

    /**
     * Read the site-approved channel codes of one tenant policy from the
     * authoritative versioned configuration fold.
     *
     * <p>The organization is named explicitly so a stale or wrong request
     * context can never read another tenant's policy. An effective but
     * unapproved version is invisible in the fold, so the returned set is
     * empty until a named approver approves a version; callers must fail
     * closed on an empty set.</p>
     *
     * @param organizationId active tenant identifier
     * @param key            tenant-local approved-channel configuration key
     * @return approved, trimmed channel codes; empty when none is effective
     */
    Set<String> approvedChannels(String organizationId, String key);

    /** @return a profile by opaque identifier in this tenant */
    Optional<ServiceProfile> findProfile(String organizationId, String profileId);

    /** @return the approved, effective profile with the highest version */
    Optional<ServiceProfile> effectiveProfile(String organizationId, String unitId, Instant at);

    /** @return all versions for a unit in ascending version order */
    List<ServiceProfile> versions(String organizationId, String unitId);

    /** @return the highest version published for a unit, or zero */
    int latestVersion(String organizationId, String unitId);

    /** Append one immutable version. */
    ServiceProfile insertProfile(String organizationId, ServiceProfile profile);

    /** Replace a draft with its approved, immutable copy. */
    ServiceProfile updateProfile(String organizationId, ServiceProfile profile);

    /** @return work facts scoped to the unit */
    List<WorkItemFact> workItems(String organizationId, String unitId);

    /** Append one synthetic work fact. */
    void addWorkItem(String organizationId, WorkItemFact item);

    /** Append one immutable, non-sensitive authorization fact. */
    void appendAudit(MessagingAudit audit);

    /** @return authorization facts for one tenant in insertion order */
    List<MessagingAudit> auditEvents(String organizationId);
}
