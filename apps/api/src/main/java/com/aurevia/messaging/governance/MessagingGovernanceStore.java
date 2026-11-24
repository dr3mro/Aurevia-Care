package com.aurevia.messaging.governance;

import java.util.List;
import java.util.Optional;

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
 * Persistence boundary for MSG-07 governance facts.
 *
 * <p>Every operation carries the active organization explicitly, so two tenants
 * with identical local identifiers remain isolated across audit, holds,
 * retention, exports, provenance, copy requests and revocation effects. An
 * implementation must scope results to that organization and must not expose a
 * clinical payload through these governance reads.</p>
 */
public interface MessagingGovernanceStore {

    /**
     * Check active membership in the organization.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return {@code true} when the membership is active
     */
    boolean hasActiveMembership(String organizationId, String subject);

    /**
     * List the role keys currently in force for a subject.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return active role keys
     */
    List<String> activeRoles(String organizationId, String subject);

    /**
     * Append one immutable audit fact.
     *
     * @param event fact to append
     */
    void appendAudit(AuditEvent event);

    /**
     * Read the tenant's entire append-only audit trail.
     *
     * @param organizationId active organization
     * @return audit facts in insertion order
     */
    List<AuditEvent> auditEvents(String organizationId);

    /**
     * Read the tenant's audit trail filtered to one resource.
     *
     * @param organizationId active organization
     * @param resourceId     exact resource filter
     * @return matching audit facts in insertion order
     */
    List<AuditEvent> auditEvents(String organizationId, String resourceId);

    /**
     * Record a retention policy for a resource family.
     *
     * @param organizationId owning organization
     * @param policy         policy to store
     */
    void putRetentionPolicy(String organizationId, RetentionPolicy policy);

    /**
     * Read the retention policy for one resource family.
     *
     * @param organizationId active organization
     * @param resourceType   resource family
     * @return the policy, if configured
     */
    Optional<RetentionPolicy> retentionPolicy(String organizationId, String resourceType);

    /**
     * List all tracked resources in a tenant.
     *
     * @param organizationId active organization
     * @return resources in insertion order
     */
    List<MessagingResource> resources(String organizationId);

    /**
     * Store the distinct sender and recipient retention duties for a resource.
     *
     * @param organizationId owning organization
     * @param duty           duty pair to store
     */
    void putRetentionDuty(String organizationId, RetentionDuty duty);

    /**
     * Read the retention duties for one resource.
     *
     * @param organizationId active organization
     * @param resourceId     resource identifier
     * @return the duty pair, if recorded
     */
    java.util.Optional<RetentionDuty> retentionDuty(String organizationId, String resourceId);

    /**
     * Store one legal hold.
     *
     * @param organizationId owning organization
     * @param hold           hold to store
     */
    void putLegalHold(String organizationId, LegalHold hold);

    /**
     * Find one legal hold by identifier.
     *
     * @param organizationId active organization
     * @param holdId         hold identifier
     * @return the hold, if present
     */
    Optional<LegalHold> legalHold(String organizationId, String holdId);

    /**
     * List all legal holds in a tenant.
     *
     * @param organizationId active organization
     * @return holds in insertion order
     */
    List<LegalHold> legalHolds(String organizationId);

    /**
     * Set the verified record-access policy for a tenant.
     *
     * @param organizationId owning organization
     * @param policy         approved policy
     */
    void putRecordAccessPolicy(String organizationId, RecordAccessPolicy policy);

    /**
     * Read the verified record-access policy for a tenant.
     *
     * @param organizationId active organization
     * @return the record-access policy
     */
    RecordAccessPolicy recordAccessPolicy(String organizationId);

    /**
     * Record one tenant-scoped connection reference.
     *
     * @param organizationId    owning organization
     * @param connection        connection to record
     */
    void addConnection(String organizationId, ConnectionReference connection);

    /**
     * List all connection references in a tenant, for object-level binding checks.
     *
     * @param organizationId active organization
     * @return connections in this tenant
     */
    java.util.List<ConnectionReference> allConnections(String organizationId);

    /**
     * Find one connection reference by identifier.
     *
     * @param organizationId active organization
     * @param connectionId   connection identifier
     * @return the connection, if present
     */
    Optional<ConnectionReference> connection(String organizationId, String connectionId);

    /**
     * Store one chart-provenance link.
     *
     * @param organizationId owning organization
     * @param link           link to store
     */
    void putProvenanceLink(String organizationId, ProvenanceLink link);

    /**
     * List provenance links for one source message or instruction.
     *
     * @param organizationId active organization
     * @param messageId      source message identifier
     * @return links in insertion order
     */
    List<ProvenanceLink> provenanceLinks(String organizationId, String messageId);

    /**
     * Store one expiring export record.
     *
     * @param organizationId owning organization
     * @param export         export to store
     */
    void putExport(String organizationId, ExportRecord export);

    /**
     * Find one export by identifier.
     *
     * @param organizationId active organization
     * @param exportId       export identifier
     * @return the export, if present
     */
    Optional<ExportRecord> export(String organizationId, String exportId);

    /**
     * Store one routed copy request.
     *
     * @param organizationId owning organization
     * @param request        copy request to store
     */
    void putCopyRequest(String organizationId, CopyRequest request);

    /**
     * List copy requests in a tenant.
     *
     * @param organizationId active organization
     * @return copy requests in insertion order
     */
    List<CopyRequest> copyRequests(String organizationId);

    /**
     * Store one revocation effect.
     *
     * @param organizationId owning organization
     * @param effect         effect to store
     */
    void putRevocationEffect(String organizationId, RevocationEffect effect);

    /**
     * List recorded revocation effects for one connection.
     *
     * @param organizationId active organization
     * @param connectionId   connection identifier
     * @return effects in insertion order
     */
    List<RevocationEffect> revocationEffects(String organizationId, String connectionId);
}
