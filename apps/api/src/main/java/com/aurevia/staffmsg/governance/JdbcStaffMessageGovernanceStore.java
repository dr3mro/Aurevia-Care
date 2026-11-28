package com.aurevia.staffmsg.governance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.messaging.governance.MessagingGovernanceStore;
import com.aurevia.staffmsg.attachments.StaffAttachmentStore;
import com.aurevia.staffmsg.mail.MailContract.SentMail;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.AuditEvent;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.ConversationExport;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.MessageView;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.StaffAction;
import com.aurevia.staffmsg.instant.InstantConversationStore;
import com.aurevia.staffmsg.mail.MailStore;
import com.aurevia.staffmsg.model.StaffMessagingContract.AttachmentLink;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * Durable adapter for staff-message governance over the existing forced-RLS
 * tables.
 *
 * <p>Membership, roles, holds, retention policies and export metadata are owned
 * by the authoritative
 * {@link com.aurevia.messaging.governance.MessagingGovernanceStore} over V62; mail, folders,
 * conversations and attachment links are owned by their V91/V95 stores. This
 * adapter composes those boundaries and writes only the staff audit fact, whose
 * {@code StaffAction} vocabulary is wider than the patient-facing action enum.
 * Every statement runs in the tenant-guarded transaction and names the active
 * organization; forced RLS is the second isolation boundary.</p>
 */
@Repository
public class JdbcStaffMessageGovernanceStore implements StaffMessageGovernanceStore {

    /** Audit resource scope recorded for staff-message governance facts. */
    public static final String AUDIT_SCOPE = "STAFF_MESSAGE";

    private static final RowMapper<AuditEvent> AUDIT_MAPPER = JdbcStaffMessageGovernanceStore::mapAudit;
    private static final String EXPORT_SCOPE = "conversation";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MessagingGovernanceStore governance;
    private final MailStore mail;
    private final InstantConversationStore conversations;
    private final StaffAttachmentStore attachments;

    /**
     * Create the durable governance store.
     *
     * @param jdbc          shared named-parameter JDBC template
     * @param transactions  tenant-guarded transaction template
     * @param governance    authoritative governance-fact store
     * @param mail          durable published-mail store
     * @param conversations durable instant-conversation store
     * @param attachments   durable staff-attachment store
     */
    public JdbcStaffMessageGovernanceStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
                                           MessagingGovernanceStore governance, MailStore mail,
                                           InstantConversationStore conversations,
                                           StaffAttachmentStore attachments) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.governance = governance;
        this.mail = mail;
        this.conversations = conversations;
        this.attachments = attachments;
    }
    /**
     * Check active membership in the organization.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return {@code true} only when the membership is active
     */
    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return governance.hasActiveMembership(organizationId, subject);
    }
    /**
     * Read the role keys currently effective for a subject.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated subject from the tenant context
     * @return the effective role keys, empty when the subject has none
     */
    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        return governance.activeRoles(organizationId, subject);
    }
    /**
     * Append one immutable audit fact.
     *
     * @param event fact to append
     */
    @Override
    public void appendAudit(AuditEvent event) {
        // Staff facts use the canonical tamper-evident audit ledger, not the
        // patient-shaped messaging table: a wider StaffAction wire key there
        // would break the patient governance reader's AuditAction.from mapping.
        // The actor is supplied by the tenant audit trigger from app.actor, so
        // no caller can attribute a fact to another user.
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.audit_event
                    (organization_id, id, occurred_at, actor_id, action, resource_type,
                     resource_id, purpose, scope, correlation_id, outcome)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:occurredAt AS timestamptz),
                        NULL, :action, :resourceType, :resourceId, :purpose, :scope,
                        :correlationId, :outcome)
                """, new MapSqlParameterSource()
                        .addValue("organizationId", event.organizationId())
                        .addValue("id", event.id())
                        .addValue("occurredAt", dateTime(event.occurredAt()))
                        .addValue("action", event.action().wire())
                        .addValue("resourceType", AUDIT_SCOPE)
                        .addValue("resourceId", event.resourceId())
                        .addValue("purpose", event.purpose())
                        .addValue("scope", event.unitId())
                        .addValue("correlationId", event.correlationId())
                        .addValue("outcome", event.outcome().wire())));
    }
    /**
     * Read the tenant's append-only audit trail in insertion order.
     *
     * @param organizationId active organization
     * @return audit facts in insertion order
     */
    @Override
    public List<AuditEvent> auditEvents(String organizationId) {
        // Filter by purpose so unrelated clinical audit facts in the shared
        // append-only ledger are never mapped into this governance trail.
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, actor_id::text, action, resource_id,
                       scope, correlation_id, outcome, occurred_at
                  FROM public.audit_event
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND purpose = :purpose
                 ORDER BY seq
                """, Map.of("organizationId", organizationId,
                        "purpose", StaffMessageGovernanceService.PURPOSE), AUDIT_MAPPER));
    }
    /**
     * List every legal hold in a tenant.
     *
     * @param organizationId active organization
     * @return holds in insertion order
     */
    @Override
    public List<LegalHold> legalHolds(String organizationId) {
        return governance.legalHolds(organizationId);
    }
    /**
     * Store or replace one legal hold.
     *
     * @param organizationId owning organization
     * @param hold           hold to store
     */
    @Override
    public void putLegalHold(String organizationId, LegalHold hold) {
        governance.putLegalHold(organizationId, hold);
    }
    /**
     * Read the tenant retention policy for one resource family.
     *
     * @param organizationId active organization
     * @param resourceType   resource family
     * @return the policy, or empty when no approved policy is configured
     */
    @Override
    public Optional<RetentionPolicy> retentionPolicy(String organizationId, String resourceType) {
        return governance.retentionPolicy(organizationId, resourceType);
    }
    /**
     * List every published message in a tenant. Participation and folder filters
     * are applied by the kernel, not here, so read policy stays in one place.
     *
     * @param organizationId active tenant
     * @return all published messages in insertion order
     */
    @Override
    public List<MessageView> messages(String organizationId) {
        // The mail store's projection carries everything search needs; the
        // conversation id falls back to the message id for a standalone mail.
        return mail.messages(organizationId).stream()
                .map(this::messageView)
                .toList();
    }
    /**
     * List the full membership history of a conversation, including leavers.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @return all memberships ordered by join time
     */
    @Override
    public List<StaffParticipant> participants(String organizationId, String conversationId) {
        return conversations.participants(organizationId, conversationId);
    }
    /**
     * Load one conversation by identifier inside the active organization.
     *
     * @param organizationId active organization
     * @param conversationId conversation identifier
     * @return the conversation, or empty when absent or foreign
     */
    @Override
    public Optional<StaffConversation> conversation(String organizationId, String conversationId) {
        return conversations.conversation(organizationId, conversationId);
    }
    /**
     * Return the folder placement of one viewer for every message.
     *
     * @param organizationId active tenant
     * @param subject       viewer subject
     * @return message identifier to folder, absent entries default to inbox
     */
    @Override
    public Map<String, MailFolder> folders(String organizationId, String subject) {
        return mail.folders(organizationId, subject);
    }
    /**
     * Place one message in one viewer's folder.
     *
     * @param organizationId active tenant
     * @param messageId      message identifier
     * @param subject       viewer subject
     * @param folder         target folder
     */
    @Override
    public void setFolder(String organizationId, String messageId, String subject, MailFolder folder) {
        mail.setFolder(organizationId, messageId, subject, folder);
    }
    /**
     * List the attachment links bound to one message, carrying each checksum.
     *
     * @param organizationId active organization
     * @param messageId      owning message
     * @return attachment links in this tenant
     */
    @Override
    public List<AttachmentLink> attachmentLinks(String organizationId, String messageId) {
        // attachmentsForDraft deliberately queries staff_attachment_link by
        // message_id, so it is the checksum source without any duplicated SQL.
        return attachments.attachmentsForDraft(organizationId, messageId).stream()
                .map(link -> new AttachmentLink(organizationId, link.id(), messageId, link.documentId(),
                        link.checksum(), link.scanState()))
                .toList();
    }
    /**
     * Persist the non-sensitive metadata of a conversation export. Attachment
     * bytes and the assembled checksum snapshot are returned to the caller only;
     * the durable export keeps provenance, scope, actor and expiry.
     *
     * @param organizationId owning organization
     * @param export         export metadata
     */
    @Override
    public void putExport(String organizationId, ConversationExport export) {
        governance.putExport(organizationId, new ExportRecord(export.id(), export.organizationId(),
                EXPORT_SCOPE, export.createdBy(), export.createdAt(), export.expiresAt(),
                export.provenance()));
    }

    /**
     * Project a durable mail into the tenant-search view.
     *
     * @param sentMail published mail projection
     * @return the search view
     */
    private MessageView messageView(SentMail sentMail) {
        String conversationId = sentMail.threadId() == null ? sentMail.id() : sentMail.threadId();
        return new MessageView(sentMail.id(), conversationId, sentMail.senderSubject(),
                sentMail.subject(), sentMail.sentAt(), sentMail.body());
    }

    /**
     * Map one audit row back to a staff governance fact.
     *
     * @param resultSet result row
     * @param rowNumber row index
     * @return the audit fact
     * @throws SQLException when a column cannot be read
     */
    private static AuditEvent mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        // actor_id is the durable subject identifier stamped by the V3 audit
        // trigger from the transaction actor, never a caller-supplied value.
        return new AuditEvent(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("actor_id"), StaffMessageGovernanceService.PURPOSE,
                StaffAction.from(resultSet.getString("action")), resultSet.getString("resource_id"),
                resultSet.getString("scope"), resultSet.getString("correlation_id"),
                Outcome.from(resultSet.getString("outcome")), instant(resultSet, "occurred_at"));
    }

    /**
     * Bind a server instant as an offset timestamp for pgjdbc.
     *
     * @param instant server instant
     * @return the UTC offset representation
     */
    private static OffsetDateTime dateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Read a non-null timestamptz column as a server instant.
     *
     * @param resultSet result row
     * @param column    column label
     * @return the instant
     * @throws SQLException when the column cannot be read
     */
    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
