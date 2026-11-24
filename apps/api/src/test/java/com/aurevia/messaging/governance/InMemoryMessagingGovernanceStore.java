package com.aurevia.messaging.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.messaging.governance.MessagingAuditContract.ConnectionReference;
import com.aurevia.messaging.governance.MessagingAuditContract.CopyRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.MessagingResource;
import com.aurevia.messaging.governance.MessagingAuditContract.ProvenanceLink;
import com.aurevia.messaging.governance.MessagingAuditContract.RecordAccessPolicy;
import com.aurevia.messaging.governance.MessagingAuditContract.RevocationEffect;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionDuty;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionPolicy;

/**
 * Synthetic, tenant-keyed in-memory adapter for MSG-07 governance facts.
 *
 * <p>Test-only fake: it lets the domain rules be exercised deterministically
 * without a database and is not a Spring bean. Production uses
 * {@link com.aurevia.messaging.governance.JdbcMessagingGovernanceStore}
 * over V62's forced-RLS and append-only tables. Every method takes the active
 * organization and never merges rows across tenants.</p>
 */
public class InMemoryMessagingGovernanceStore implements MessagingGovernanceStore {

    private final Map<String, String> roles = new ConcurrentHashMap<>();
    private final Set<String> activeSubjects = ConcurrentHashMap.newKeySet();
    private final Map<String, List<AuditEvent>> audits = new ConcurrentHashMap<>();
    private final Map<String, RetentionPolicy> retentionPolicies = new ConcurrentHashMap<>();
    private final Map<String, RetentionDuty> retentionDuties = new ConcurrentHashMap<>();
    private final Map<String, List<MessagingResource>> resources = new ConcurrentHashMap<>();
    private final Map<String, LegalHold> holds = new ConcurrentHashMap<>();
    private final Map<String, RecordAccessPolicy> recordAccessPolicies = new ConcurrentHashMap<>();
    private final Map<String, ConnectionReference> connections = new ConcurrentHashMap<>();
    private final Map<String, List<ProvenanceLink>> provenanceLinks = new ConcurrentHashMap<>();
    private final Map<String, ExportRecord> exports = new ConcurrentHashMap<>();
    private final Map<String, List<CopyRequest>> copyRequests = new ConcurrentHashMap<>();
    private final Map<String, List<RevocationEffect>> revocationEffects = new ConcurrentHashMap<>();

    /**
     * Seed a synthetic membership and role.
     *
     * @param organizationId owning organization
     * @param subject        synthetic subject
     * @param role           active role key
     * @param active         whether the membership is active
     */
    public void addMember(String organizationId, String subject, String role, boolean active) {
        roles.put(key(organizationId, subject), role);
        if (active) {
            activeSubjects.add(key(organizationId, subject));
        } else {
            activeSubjects.remove(key(organizationId, subject));
        }
    }

    /**
     * Seed a synthetic messaging resource.
     *
     * @param organizationId owning organization
     * @param id             resource identifier
     * @param resourceType   resource family
     * @param createdAt      server creation instant
     */
    public void addResource(String organizationId, String id, String resourceType,
                            java.time.Instant createdAt) {
        resources.computeIfAbsent(organizationId, ignored -> new CopyOnWriteArrayList<>())
                .add(new MessagingResource(id, organizationId, resourceType, createdAt));
    }

    /**
     * Seed a synthetic retention policy with a compact signature.
     *
     * @param organizationId owning organization
     * @param resourceType   resource family
     * @param retentionDays  days before the resource is retention-eligible
     */
    public void putRetentionPolicy(String organizationId, String resourceType, long retentionDays) {
        putRetentionPolicy(organizationId, new RetentionPolicy(organizationId, resourceType, retentionDays));
    }

    /**
     * Seed a synthetic record-access policy.
     *
     * @param organizationId             owning organization
     * @param priorConversationsReadable whether earlier conversations stay readable
     * @param policyVersion              approved policy version
     */
    public void addRecordAccessPolicy(String organizationId, boolean priorConversationsReadable,
                                      String policyVersion) {
        putRecordAccessPolicy(organizationId,
                new RecordAccessPolicy(organizationId, priorConversationsReadable, policyVersion));
    }

    /**
     * Seed a synthetic connection reference.
     *
     * @param organizationId owning organization
     * @param id             connection identifier
     * @param unitId         recipient unit
     * @param patientId      verified chart
     * @param revokedAt      revocation instant, or {@code null} while active
     */
    public void addConnection(String organizationId, String id, String unitId, String patientId,
                              java.time.Instant revokedAt) {
        addConnection(organizationId,
                new ConnectionReference(id, organizationId, unitId, patientId, revokedAt));
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return activeSubjects.contains(key(organizationId, subject));
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        String role = roles.get(key(organizationId, subject));
        if (role == null || !hasActiveMembership(organizationId, subject)) {
            return List.of();
        }
        return List.of(role);
    }

    @Override
    public void appendAudit(AuditEvent event) {
        audits.computeIfAbsent(event.organizationId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public List<AuditEvent> auditEvents(String organizationId) {
        return List.copyOf(audits.getOrDefault(organizationId, List.of()));
    }

    @Override
    public List<AuditEvent> auditEvents(String organizationId, String resourceId) {
        List<AuditEvent> found = new ArrayList<>();
        for (AuditEvent event : auditEvents(organizationId)) {
            if (resourceId.equals(event.resourceId())) {
                found.add(event);
            }
        }
        return List.copyOf(found);
    }

    @Override
    public void putRetentionPolicy(String organizationId, RetentionPolicy policy) {
        retentionPolicies.put(key(organizationId, policy.resourceType()), policy);
    }

    @Override
    public Optional<RetentionPolicy> retentionPolicy(String organizationId, String resourceType) {
        return Optional.ofNullable(retentionPolicies.get(key(organizationId, resourceType)));
    }

    @Override
    public List<MessagingResource> resources(String organizationId) {
        return List.copyOf(resources.getOrDefault(organizationId, List.of()));
    }

    @Override
    public void putRetentionDuty(String organizationId, RetentionDuty duty) {
        retentionDuties.put(key(organizationId, duty.resourceId()), duty);
    }

    @Override
    public Optional<RetentionDuty> retentionDuty(String organizationId, String resourceId) {
        return Optional.ofNullable(retentionDuties.get(key(organizationId, resourceId)));
    }

    @Override
    public void putLegalHold(String organizationId, LegalHold hold) {
        holds.put(key(organizationId, hold.id()), hold);
    }

    @Override
    public Optional<LegalHold> legalHold(String organizationId, String holdId) {
        return Optional.ofNullable(holds.get(key(organizationId, holdId)));
    }

    @Override
    public List<LegalHold> legalHolds(String organizationId) {
        List<LegalHold> found = new ArrayList<>();
        for (Map.Entry<String, LegalHold> entry : holds.entrySet()) {
            if (entry.getKey().startsWith(organizationId + "|")) {
                found.add(entry.getValue());
            }
        }
        return List.copyOf(found);
    }

    @Override
    public void putRecordAccessPolicy(String organizationId, RecordAccessPolicy policy) {
        recordAccessPolicies.put(organizationId, policy);
    }

    /**
     * Remove a seeded record-access policy to exercise the fail-closed default.
     *
     * @param organizationId owning organization
     */
    public void clearRecordAccessPolicy(String organizationId) {
        recordAccessPolicies.remove(organizationId);
    }

    @Override
    public RecordAccessPolicy recordAccessPolicy(String organizationId) {
        // Fail closed: without an approved policy, prior conversations are not
        // treated as readable and the provenance version is explicitly unknown.
        return recordAccessPolicies.getOrDefault(organizationId,
                new RecordAccessPolicy(organizationId, false, "UNKNOWN"));
    }

    @Override
    public void addConnection(String organizationId, ConnectionReference connection) {
        connections.put(key(organizationId, connection.id()), connection);
    }

    @Override
    public java.util.List<ConnectionReference> allConnections(String organizationId) {
        java.util.List<ConnectionReference> found = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ConnectionReference> entry : connections.entrySet()) {
            if (entry.getKey().startsWith(organizationId + "|")) {
                found.add(entry.getValue());
            }
        }
        return java.util.List.copyOf(found);
    }

    @Override
    public Optional<ConnectionReference> connection(String organizationId, String connectionId) {
        return Optional.ofNullable(connections.get(key(organizationId, connectionId)));
    }

    @Override
    public void putProvenanceLink(String organizationId, ProvenanceLink link) {
        provenanceLinks.computeIfAbsent(key(organizationId, link.messageId()),
                ignored -> new CopyOnWriteArrayList<>()).add(link);
    }

    @Override
    public List<ProvenanceLink> provenanceLinks(String organizationId, String messageId) {
        return List.copyOf(provenanceLinks.getOrDefault(key(organizationId, messageId), List.of()));
    }

    @Override
    public void putExport(String organizationId, ExportRecord export) {
        exports.put(key(organizationId, export.id()), export);
    }

    @Override
    public Optional<ExportRecord> export(String organizationId, String exportId) {
        return Optional.ofNullable(exports.get(key(organizationId, exportId)));
    }

    @Override
    public void putCopyRequest(String organizationId, CopyRequest request) {
        copyRequests.computeIfAbsent(organizationId, ignored -> new CopyOnWriteArrayList<>()).add(request);
    }

    @Override
    public List<CopyRequest> copyRequests(String organizationId) {
        return List.copyOf(copyRequests.getOrDefault(organizationId, List.of()));
    }

    @Override
    public void putRevocationEffect(String organizationId, RevocationEffect effect) {
        revocationEffects.computeIfAbsent(key(organizationId, effect.connectionId()),
                ignored -> new CopyOnWriteArrayList<>()).add(effect);
    }

    @Override
    public List<RevocationEffect> revocationEffects(String organizationId, String connectionId) {
        return List.copyOf(revocationEffects.getOrDefault(key(organizationId, connectionId), List.of()));
    }

    private static String key(String organizationId, String identifier) {
        return organizationId + "|" + identifier;
    }
}
