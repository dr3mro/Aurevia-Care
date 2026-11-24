package com.aurevia.therapy;

import java.util.List;
import java.util.Optional;

import com.aurevia.therapy.TherapyContract.OutcomeScale;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;

/**
 * Persistence boundary for the PT-01 therapy workflow.
 *
 * <p>Every method is scoped by the caller's active organization so two tenants
 * with identical local identifiers remain isolated. A production implementation
 * can bind these operations to a tenant-guarded repository without changing the
 * domain service.</p>
 */
public interface TherapyStore {

    /**
     * Find one episode aggregate in the active organization.
     *
     * @param organizationId active organization
     * @param episodeId      local episode identifier
     * @return the aggregate when visible
     */
    Optional<TherapyAggregate> find(String organizationId, String episodeId);

    /**
     * Persist an episode aggregate. The organization is taken from the aggregate.
     *
     * @param aggregate aggregate to persist
     */
    void save(TherapyAggregate aggregate);

    /**
     * Check whether a referral was already accepted in the active organization.
     *
     * @param organizationId active organization
     * @param referralId     local referral identifier
     * @return {@code true} when the referral already owns an episode
     */
    boolean hasReferral(String organizationId, String referralId);

    /**
     * Read one version of an outcome scale.
     *
     * @param organizationId active organization
     * @param key            scale key
     * @param version        scale version
     * @return the scale when visible
     */
    Optional<OutcomeScale> scale(String organizationId, String key, int version);

    /**
     * Read all versions of an outcome scale, oldest first.
     *
     * @param organizationId active organization
     * @param key            scale key
     * @return immutable version history
     */
    List<OutcomeScale> scales(String organizationId, String key);

    /**
     * Allocate the next version number for a scale key atomically.
     *
     * @param organizationId active organization
     * @param key            scale key
     * @return the next monotonic version
     */
    int allocateScaleVersion(String organizationId, String key);

    /**
     * Persist one version of an outcome scale.
     *
     * @param scale scale version to persist
     */
    void saveScale(OutcomeScale scale);

    /**
     * Read the tenant's append-only audit trail.
     *
     * @param organizationId active organization
     * @return immutable audit facts
     */
    List<TherapyAuditEvent> audit(String organizationId);

    /**
     * Append one audit fact.
     *
     * @param event audit fact
     */
    void appendAudit(TherapyAuditEvent event);
}
