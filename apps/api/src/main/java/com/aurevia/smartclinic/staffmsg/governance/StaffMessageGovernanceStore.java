package com.aurevia.smartclinic.staffmsg.governance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.AuditEvent;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.ConversationExport;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.MessageView;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.AttachmentLink;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * Persistence boundary for staff-message governance (MSG-07, ARC-02).
 *
 * <p>Every operation carries the active organization explicitly so two tenants
 * with identical local identifiers remain isolated across audit, holds,
 * retention, search, folder placement and exports. An implementation must scope
 * every read to that organization and must never expose a message body through
 * the audit or export metadata paths.</p>
 */
public interface StaffMessageGovernanceStore {

    /**
     * Check active membership in the organization.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return {@code true} only when the membership is active
     */
    boolean hasActiveMembership(String organizationId, String subject);

    /**
     * List role keys currently in force for a subject.
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
     * Read the tenant's append-only audit trail in insertion order.
     *
     * @param organizationId active organization
     * @return audit facts in insertion order
     */
    List<AuditEvent> auditEvents(String organizationId);

    /**
     * List every legal hold in a tenant.
     *
     * @param organizationId active organization
     * @return holds in insertion order
     */
    List<LegalHold> legalHolds(String organizationId);

    /**
     * Store or replace one legal hold.
     *
     * @param organizationId owning organization
     * @param hold           hold to store
     */
    void putLegalHold(String organizationId, LegalHold hold);

    /**
     * Read the tenant retention policy for one resource family.
     *
     * @param organizationId active organization
     * @param resourceType   resource family
     * @return the policy, or empty when no approved policy is configured
     */
    Optional<RetentionPolicy> retentionPolicy(String organizationId, String resourceType);

    /**
     * List every durable message in a tenant for tenant-only search.
     *
     * @param organizationId active organization
     * @return message projections in this tenant
     */
    List<MessageView> messages(String organizationId);

    /**
     * List the full membership history of a conversation.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @return memberships including leavers
     */
    List<StaffParticipant> participants(String organizationId, String conversationId);

    /**
     * Load one conversation in the active organization.
     *
     * @param organizationId active organization
     * @param conversationId conversation identifier
     * @return the conversation, or empty when absent or foreign
     */
    Optional<StaffConversation> conversation(String organizationId, String conversationId);

    /**
     * Read one participant's folder placement for every message.
     *
     * @param organizationId active organization
     * @param subject        viewer subject
     * @return message identifier to folder
     */
    Map<String, MailFolder> folders(String organizationId, String subject);

    /**
     * Place a message in one participant's folder. A hide uses
     * {@link com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.MailFolder#TRASH}
     * and leaves the durable message row untouched.
     *
     * @param organizationId active organization
     * @param messageId      message identifier
     * @param subject        viewer subject
     * @param folder         target folder
     */
    void setFolder(String organizationId, String messageId, String subject, MailFolder folder);

    /**
     * List the attachment links bound to one message, carrying each checksum.
     *
     * @param organizationId active organization
     * @param messageId      owning message
     * @return attachment links in this tenant
     */
    List<AttachmentLink> attachmentLinks(String organizationId, String messageId);

    /**
     * Persist the non-sensitive metadata of a conversation export. Attachment
     * bytes and the assembled checksum snapshot are returned to the caller only;
     * the durable export keeps provenance, scope, actor and expiry.
     *
     * @param organizationId owning organization
     * @param export         export metadata
     */
    void putExport(String organizationId, ConversationExport export);
}
