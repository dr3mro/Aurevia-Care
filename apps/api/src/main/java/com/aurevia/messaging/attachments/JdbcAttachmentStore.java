package com.aurevia.messaging.attachments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict;

/**
 * PostgreSQL-backed persistence boundary for message attachments. Every
 * operation is confined to the active tenant.
 *
 * <p>V82 owns the three tenant tables and their forced row-level security.
 * Every operation runs in the tenant-guarded transaction, which binds
 * {@code app.current_org()} after the transaction opens; forced RLS therefore
 * confines each statement to the active organization. The {@code organizationId}
 * argument identifies that same active tenant and is written explicitly on
 * inserts so the policy's {@code WITH CHECK} rejects a mismatch.</p>
 *
 * <p>A draft is bound to its uploader and verified connection on its first
 * insert. The guarded upsert only advances lifecycle columns and returns no row
 * when an existing draft would be rebound, so a file accepted under one
 * connection can never be reused under another. A database trigger enforces the
 * same invariant for every role, including the migration owner.</p>
 */
@Repository
public class JdbcAttachmentStore implements AttachmentStore {

    private static final RowMapper<ConnectionGrant> GRANT_MAPPER = JdbcAttachmentStore::mapGrant;
    private static final RowMapper<Attachment> ATTACHMENT_MAPPER = JdbcAttachmentStore::mapAttachment;
    private static final RowMapper<AttachmentEvent> EVENT_MAPPER = JdbcAttachmentStore::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL attachment store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcAttachmentStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ConnectionGrant> grant(String organizationId, String connectionId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, connection_id, unit_id, patient_id, active
                  FROM public.attachment_connection_grant
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND connection_id = :connectionId
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("connectionId", connectionId), GRANT_MAPPER)
                .stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public void putGrant(ConnectionGrant grant) {
        transactions.execute(status -> jdbc.update("""
                INSERT INTO public.attachment_connection_grant
                    (organization_id, connection_id, unit_id, patient_id, active)
                VALUES (CAST(:organizationId AS uuid), :connectionId, :unitId, :patientId, :active)
                ON CONFLICT (organization_id, connection_id) DO UPDATE SET
                    unit_id = EXCLUDED.unit_id,
                    patient_id = EXCLUDED.patient_id,
                    active = EXCLUDED.active
                """, new MapSqlParameterSource()
                        .addValue("organizationId", grant.organizationId())
                        .addValue("connectionId", grant.connectionId())
                        .addValue("unitId", grant.unitId())
                        .addValue("patientId", grant.patientId())
                        .addValue("active", grant.active())));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Attachment> attachment(String organizationId, String id) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, version, uploader_subject, connection_id,
                       unit_id, patient_id, file_name, file_type, size_bytes, checksum, object_key,
                       object_version, verdict, state, message_id, recipient_unit_id, created_at
                  FROM public.attachment
                 WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", id), ATTACHMENT_MAPPER)
                .stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public void putAttachment(Attachment attachment) {
        List<String> rows = transactions.execute(status -> jdbc.query("""
                INSERT INTO public.attachment
                    (organization_id, id, version, uploader_subject, connection_id, unit_id,
                     patient_id, file_name, file_type, size_bytes, checksum, object_key,
                     object_version, verdict, state, message_id, recipient_unit_id, created_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :version, :uploaderSubject,
                        :connectionId, :unitId, :patientId, :fileName, :fileType, :sizeBytes,
                        :checksum, :objectKey, :objectVersion, :verdict, :state, :messageId,
                        :recipientUnitId, CAST(:createdAt AS timestamptz))
                ON CONFLICT (organization_id, id) DO UPDATE SET
                    version = EXCLUDED.version,
                    verdict = EXCLUDED.verdict,
                    state = EXCLUDED.state,
                    message_id = EXCLUDED.message_id,
                    recipient_unit_id = EXCLUDED.recipient_unit_id
                WHERE attachment.uploader_subject = EXCLUDED.uploader_subject
                  AND attachment.connection_id = EXCLUDED.connection_id
                  AND attachment.unit_id = EXCLUDED.unit_id
                  AND attachment.patient_id = EXCLUDED.patient_id
                RETURNING id::text
                """, new MapSqlParameterSource()
                        .addValue("organizationId", attachment.organizationId())
                        .addValue("id", attachment.id())
                        .addValue("version", attachment.version())
                        .addValue("uploaderSubject", attachment.uploaderSubject())
                        .addValue("connectionId", attachment.connectionId())
                        .addValue("unitId", attachment.unitId())
                        .addValue("patientId", attachment.patientId())
                        .addValue("fileName", attachment.fileName())
                        .addValue("fileType", attachment.fileType().contentType())
                        .addValue("sizeBytes", attachment.sizeBytes())
                        .addValue("checksum", attachment.checksum())
                        .addValue("objectKey", attachment.objectKey())
                        .addValue("objectVersion", attachment.objectVersion())
                        .addValue("verdict", attachment.verdict().wire())
                        .addValue("state", attachment.state().wire())
                        .addValue("messageId", attachment.messageId())
                        .addValue("recipientUnitId", attachment.recipientUnitId())
                        .addValue("createdAt", dateTime(attachment.createdAt())),
                        (resultSet, rowNumber) -> resultSet.getString("id")));
        if (rows.isEmpty()) {
            throw new ApiException(409, "WRONG_CONVERSATION",
                    "a draft cannot be rebound to another uploader or conversation");
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Attachment> attachments(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, version, uploader_subject, connection_id,
                       unit_id, patient_id, file_name, file_type, size_bytes, checksum, object_key,
                       object_version, verdict, state, message_id, recipient_unit_id, created_at
                  FROM public.attachment
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY created_at, id
                """, new MapSqlParameterSource().addValue("organizationId", organizationId),
                ATTACHMENT_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public void appendEvent(AttachmentEvent event) {
        transactions.execute(status -> jdbc.update("""
                INSERT INTO public.attachment_event
                    (organization_id, id, attachment_id, event_type, actor_subject,
                     outcome, detail, occurred_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :attachmentId,
                        :eventType, :actorSubject, :outcome, :detail,
                        CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", event.organizationId())
                        .addValue("id", event.id())
                        .addValue("attachmentId", event.attachmentId())
                        .addValue("eventType", event.eventType().wire())
                        .addValue("actorSubject", event.actorSubject())
                        .addValue("outcome", event.outcome())
                        .addValue("detail", event.detail())
                        .addValue("occurredAt", dateTime(event.occurredAt()))));
    }

    /** {@inheritDoc} */
    @Override
    public List<AttachmentEvent> events(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, attachment_id, event_type, actor_subject,
                       outcome, detail, occurred_at
                  FROM public.attachment_event
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY seq
                """, new MapSqlParameterSource().addValue("organizationId", organizationId),
                EVENT_MAPPER));
    }

    private static ConnectionGrant mapGrant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ConnectionGrant(resultSet.getString("organization_id"),
                resultSet.getString("connection_id"), resultSet.getString("unit_id"),
                resultSet.getString("patient_id"), resultSet.getBoolean("active"));
    }

    private static Attachment mapAttachment(ResultSet resultSet, int rowNumber) throws SQLException {
        FileType type = Objects.requireNonNull(
                FileType.fromContentType(resultSet.getString("file_type")), "file_type");
        return new Attachment(resultSet.getString("organization_id"), resultSet.getString("id"),
                resultSet.getInt("version"), resultSet.getString("uploader_subject"),
                resultSet.getString("connection_id"), resultSet.getString("unit_id"),
                resultSet.getString("patient_id"), resultSet.getString("file_name"), type,
                resultSet.getLong("size_bytes"), resultSet.getString("checksum"),
                resultSet.getString("object_key"), resultSet.getInt("object_version"),
                ScanVerdict.from(resultSet.getString("verdict")),
                AttachmentState.from(resultSet.getString("state")),
                resultSet.getString("message_id"), resultSet.getString("recipient_unit_id"),
                instant(resultSet, "created_at"));
    }

    private static AttachmentEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AttachmentEvent(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("attachment_id"),
                EventType.from(resultSet.getString("event_type")),
                resultSet.getString("actor_subject"), resultSet.getString("outcome"),
                resultSet.getString("detail"), instant(resultSet, "occurred_at"));
    }

    /**
     * Convert a server instant to the driver's timestamptz representation.
     *
     * @param instant server instant
     * @return an offset date time
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
