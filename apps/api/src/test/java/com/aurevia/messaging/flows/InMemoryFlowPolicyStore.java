package com.aurevia.messaging.flows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.messaging.flows.PatientFlowContract.AppLocale;
import com.aurevia.messaging.flows.PatientFlowContract.AppPlatform;
import com.aurevia.messaging.flows.PatientFlowContract.FlowAudit;
import com.aurevia.messaging.flows.PatientFlowContract.NoticeKind;

/**
 * Synthetic, tenant-keyed in-memory adapter for the patient-flow policy.
 *
 * <p>This adapter exists so the presentation domain can be exercised
 * deterministically without a database. It is deliberately not the
 * authoritative store: the production adapter persists policy and audit with
 * forced tenant row-level security. Every method takes the active organization
 * and never merges rows across tenants. This class is test-scope only.</p>
 */
public class InMemoryFlowPolicyStore implements FlowPolicyStore {

    private final Map<String, Boolean> memberships = new ConcurrentHashMap<>();
    private final Map<String, Integer> policyVersions = new ConcurrentHashMap<>();
    private final Map<String, List<NoticeKind>> approvedNotices = new ConcurrentHashMap<>();
    private final Map<String, List<FlowAudit>> audits = new ConcurrentHashMap<>();

    /**
     * Seed a synthetic patient membership.
     *
     * @param organizationId owning organization
     * @param subject        synthetic subject
     * @param active         whether the membership is active
     */
    public void addMember(String organizationId, String subject, boolean active) {
        memberships.put(key(organizationId, subject), active);
    }

    /**
     * Seed an approved policy version for one locale and platform.
     *
     * @param organizationId owning organization
     * @param locale         resolved locale
     * @param platform       resolved platform
     * @param version        approved policy version
     */
    public void setPolicyVersion(String organizationId, AppLocale locale, AppPlatform platform, int version) {
        policyVersions.put(key(organizationId, locale.wire() + "|" + platform.wire()), version);
    }

    /**
     * Seed the generic notice kinds approved for one organization.
     *
     * @param organizationId owning organization
     * @param notices        approved notice kinds
     */
    public void setApprovedNotices(String organizationId, List<NoticeKind> notices) {
        approvedNotices.put(organizationId, List.copyOf(notices));
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return Boolean.TRUE.equals(memberships.get(key(organizationId, subject)));
    }

    @Override
    public Integer policyVersion(String organizationId, AppLocale locale, AppPlatform platform) {
        return policyVersions.get(key(organizationId, locale.wire() + "|" + platform.wire()));
    }

    @Override
    public List<NoticeKind> approvedNotices(String organizationId) {
        return List.copyOf(approvedNotices.getOrDefault(organizationId, List.of()));
    }

    @Override
    public void appendAudit(FlowAudit audit) {
        audits.computeIfAbsent(audit.organizationId(), ignored -> new CopyOnWriteArrayList<>()).add(audit);
    }

    @Override
    public List<FlowAudit> auditEvents(String organizationId) {
        return List.copyOf(audits.getOrDefault(organizationId, List.of()));
    }

    private static String key(String organizationId, String identifier) {
        return organizationId + "|" + identifier;
    }
}
