package com.aurevia.messaging.flows;

import java.util.List;

import com.aurevia.messaging.flows.PatientFlowContract.AppLocale;
import com.aurevia.messaging.flows.PatientFlowContract.AppPlatform;
import com.aurevia.messaging.flows.PatientFlowContract.FlowAudit;
import com.aurevia.messaging.flows.PatientFlowContract.NoticeKind;

/**
 * Tenant-scoped store boundary for the patient-flow presentation policy.
 *
 * <p>Every method takes the active organization explicitly. A production
 * adapter must persist these records under forced tenant row-level security;
 * this synthetic adapter exists only for deterministic verification.</p>
 */
public interface FlowPolicyStore {

    /**
     * Whether the subject has an active patient membership in the organization.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return true only while membership is active
     */
    boolean hasActiveMembership(String organizationId, String subject);

    /**
     * Look up the approved policy version for one organization, locale and platform.
     *
     * @param organizationId active organization
     * @param locale         resolved locale
     * @param platform       resolved platform
     * @return the approved version, or {@code null} when none is published
     */
    Integer policyVersion(String organizationId, AppLocale locale, AppPlatform platform);

    /**
     * Look up the generic notice kinds approved for one organization.
     *
     * @param organizationId active organization
     * @return approved notice kinds, empty when none are published
     */
    List<NoticeKind> approvedNotices(String organizationId);

    /**
     * Append a non-sensitive flow access fact.
     *
     * @param audit fact to append
     */
    void appendAudit(FlowAudit audit);

    /**
     * Read the audit facts for one organization.
     *
     * @param organizationId active organization
     * @return append-only facts in insertion order
     */
    List<FlowAudit> auditEvents(String organizationId);
}
