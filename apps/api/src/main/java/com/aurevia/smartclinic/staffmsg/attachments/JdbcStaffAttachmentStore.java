package com.aurevia.smartclinic.staffmsg.attachments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.documents.DocumentContract.Artifact;
import com.aurevia.smartclinic.documents.DocumentContract.ArtifactState;
import com.aurevia.smartclinic.documents.DocumentContract.DocumentEvent;
import com.aurevia.smartclinic.documents.DocumentContract.EventType;
import com.aurevia.smartclinic.documents.DocumentContract.MediaType;
import com.aurevia.smartclinic.documents.DocumentContract.ScanVerdict;
import com.aurevia.smartclinic.documents.DocumentStore;
import com.aurevia.smartclinic.documents.storage.ObjectKey;
import com.aurevia.smartclinic.documents.storage.ObjectStorePort;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ScanState;

/**
 * PostgreSQL-backed persistence for staff-message attachments (MSG-03, CLN-07).
 *
 * <p>The link between a message or draft and a document lives in the
 * tenant-isolated {@code staff_attachment_link} table created by the staff
 * messaging migration. File metadata and the audit ledger are persisted through
 * the document store, and original bytes through the private object-store port,
 * so an attachment survives a restart without a new table. Every read takes the
 * active organization explicitly and runs in the tenant-guarded transaction.</p>
 */
@Repository
public class JdbcStaffAttachmentStore implements StaffAttachmentStore {

    private static final RowMapper<Link> LINK_MAPPER = JdbcStaffAttachmentStore::mapLink;

    private static final Map<AuditType, EventType> AUDIT_TO_EVENT = Map.of(
            AuditType.UPLOADED, EventType.UPLOAD_ACCEPTED,
            AuditType.SCANNED_CLEAN, EventType.SCAN_CLEAN,
            AuditType.SCANNED_QUARANTINED, EventType.SCAN_FAILED,
            AuditType.SENT, EventType.UPLOAD_COMPLETED,
            AuditType.DOWNLOADED, EventType.DOWNLOADED,
            AuditType.ACCESS_DENIED, EventType.ACCESS_DENIED);
    private static final Map<EventType, AuditType> EVENT_TO_AUDIT = Map.of(
            EventType.UPLOAD_ACCEPTED, AuditType.UPLOADED,
            EventType.SCAN_CLEAN, AuditType.SCANNED_CLEAN,
            EventType.SCAN_FAILED, AuditType.SCANNED_QUARANTINED,
            EventType.UPLOAD_COMPLETED, AuditType.SENT,
            EventType.DOWNLOADED, AuditType.DOWNLOADED,
            EventType.ACCESS_DENIED, AuditType.ACCESS_DENIED);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DocumentStore documents;
    private final ObjectStorePort objects;

    /**
     * Create the durable staff-attachment store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     * @param documents    document metadata and audit ledger
     * @param objects      private object-store port for original bytes
     */
    public JdbcStaffAttachmentStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
                                    DocumentStore documents, ObjectStorePort objects) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.documents = documents;
        this.objects = objects;
    }

    @Override
    public Optional<StaffAttachment> attachment(String organizationId, String id) {
        return transactions.execute(status -> {
            Optional<Link> link = jdbc.query("""
                    SELECT organization_id::text, id::text, message_id::text, document_id::text,
                           checksum, scan_state, created_at
                      FROM public.staff_attachment_link
                     WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                    """, new MapSqlParameterSource()
                            .addValue("organizationId", organizationId)
                            .addValue("id", id), LINK_MAPPER)
                    .stream().findFirst();
            if (link.isEmpty()) {
                return Optional.<StaffAttachment>empty();
            }
            return compose(organizationId, link.get());
        });
    }

    @Override
    public List<StaffAttachment> attachmentsForDraft(String organizationId, String draftId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, message_id::text, document_id::text,
                       checksum, scan_state, created_at
                  FROM public.staff_attachment_link
                 WHERE organization_id = CAST(:organizationId AS uuid) AND message_id = :draftId
                 ORDER BY created_at, id
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("draftId", draftId), LINK_MAPPER)
                .stream()
                .map(link -> compose(organizationId, link).orElse(null))
                .filter(Objects::nonNull)
                .toList());
    }

    @Override
    public void putAttachment(StaffAttachment attachment) {
        transactions.executeWithoutResult(status -> {
            List<String> rows = jdbc.query("""
                    INSERT INTO public.staff_attachment_link
                        (organization_id, id, message_id, document_id, checksum, scan_state)
                    VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:messageId AS uuid),
                            CAST(:documentId AS uuid), :checksum, :scanState)
                    ON CONFLICT (organization_id, id) DO UPDATE SET
                        message_id = EXCLUDED.message_id,
                        scan_state = EXCLUDED.scan_state,
                        checksum = EXCLUDED.checksum
                    RETURNING id::text
                    """, new MapSqlParameterSource()
                            .addValue("organizationId", attachment.organizationId())
                            .addValue("id", attachment.id())
                            .addValue("messageId", attachment.sent()
                                    ? attachment.messageId() : attachment.draftId())
                            .addValue("documentId", attachment.documentId())
                            .addValue("checksum", attachment.checksum())
                            .addValue("scanState", attachment.scanState().wire()),
                            (resultSet, rowNumber) -> resultSet.getString("id"));
            if (rows.isEmpty()) {
                throw new ApiException(409, "UNREACHABLE_WRITE", "the attachment link write did not commit");
            }
            putMetadata(attachment);
        });
    }

    @Override
    public int countForDraft(String organizationId, String draftId) {
        return transactions.execute(status -> jdbc.queryForObject("""
                SELECT count(*) FROM public.staff_attachment_link
                 WHERE organization_id = CAST(:organizationId AS uuid) AND message_id = :draftId
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("draftId", draftId), Integer.class));
    }

    @Override
    public boolean isRecipient(String organizationId, String messageId, String subject) {
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.staff_message msg
                      JOIN public.staff_participant part
                        ON part.organization_id = msg.organization_id
                       AND part.conversation_id = msg.conversation_id
                     WHERE msg.organization_id = CAST(:organizationId AS uuid)
                       AND msg.id = CAST(:messageId AS uuid)
                       AND part.staff_subject = :subject
                )
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("messageId", messageId)
                        .addValue("subject", subject), Boolean.class)));
    }

    @Override
    public void putBytes(String organizationId, String objectKey, byte[] content) {
        ObjectKey key = keyOf(organizationId, objectKey);
        objects.putInQuarantine(key, content);
        objects.promote(key, StaffAttachmentInputs.checksum(content));
    }

    @Override
    public Optional<byte[]> bytes(String organizationId, String objectKey, String checksum) {
        return objects.getVerified(keyOf(organizationId, objectKey), checksum);
    }

    @Override
    public void appendAudit(AttachmentAudit audit) {
        documents.appendEvent(new DocumentEvent(audit.id(), audit.organizationId(), audit.attachmentId(),
                AUDIT_TO_EVENT.get(audit.type()), audit.actorSubject(), audit.outcome(),
                audit.detail(), audit.occurredAt()));
    }

    @Override
    public List<AttachmentAudit> audits(String organizationId) {
        return documents.events(organizationId).stream()
                .filter(event -> AUDIT_TO_EVENT.containsValue(event.eventType()))
                .map(event -> new AttachmentAudit(event.id(), event.organizationId(),
                        event.resourceId(), EVENT_TO_AUDIT.get(event.eventType()),
                        event.actorSubject(), event.outcome(), event.detail(), event.occurredAt()))
                .toList();
    }

    private Optional<StaffAttachment> compose(String organizationId, Link link) {
        Optional<Artifact> artifact = documents.artifact(organizationId, link.documentId());
        if (artifact.isEmpty()) {
            return Optional.empty();
        }
        Artifact file = artifact.get();
        boolean sent = file.state() == ArtifactState.COMPLETED;
        StaffAttachment result = new StaffAttachment(organizationId, link.id(), file.uploaderSubject(),
                file.contextId(), link.documentId(), file.fileName(), file.fileType().contentType(),
                file.sizeBytes(), link.checksum(), file.objectKey(), file.objectVersion(),
                link.scanState(), sent ? link.messageId() : null, sent, link.createdAt());
        return Optional.of(result);
    }

    private void putMetadata(StaffAttachment attachment) {
        ArtifactState state = attachment.sent() ? ArtifactState.COMPLETED
                : (attachment.scanState() == ScanState.CLEAN ? ArtifactState.CLEAN
                : (attachment.scanState() == ScanState.QUARANTINED ? ArtifactState.FAILED
                : ArtifactState.QUARANTINED));
        ScanVerdict verdict = attachment.scanState() == ScanState.CLEAN ? ScanVerdict.CLEAN
                : (attachment.scanState() == ScanState.QUARANTINED ? ScanVerdict.FAILED : ScanVerdict.PENDING);
        MediaType type = Objects.requireNonNull(
                MediaType.fromContentType(attachment.contentType()), "content type");
        Artifact artifact = new Artifact(attachment.organizationId(), attachment.documentId(), 1,
                attachment.uploaderSubject(), attachment.draftId(), null, attachment.fileName(), type,
                attachment.sizeBytes(), attachment.checksum(), attachment.objectKey(),
                attachment.objectVersion(), verdict, state, attachment.createdAt());
        if (documents.artifact(attachment.organizationId(), attachment.documentId()).isPresent()) {
            documents.replaceArtifactIfVersion(attachment.organizationId(), attachment.documentId(),
                    1, artifact);
        } else {
            documents.putArtifact(artifact);
        }
    }

    private static ObjectKey keyOf(String organizationId, String objectKey) {
        String[] segments = objectKey.split("/");
        String objectId = segments.length > 2 ? segments[2] : objectKey;
        return new ObjectKey(organizationId, objectId, 1);
    }

    private static Link mapLink(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Link(resultSet.getString("organization_id"), resultSet.getString("id"),
                resultSet.getString("message_id"), resultSet.getString("document_id"),
                resultSet.getString("checksum"), ScanState.from(resultSet.getString("scan_state")),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    /** Link-row projection of the tenant attachment table. */
    private record Link(String organizationId, String id, String messageId, String documentId,
                        String checksum, ScanState scanState, Instant createdAt) {
    }
}
