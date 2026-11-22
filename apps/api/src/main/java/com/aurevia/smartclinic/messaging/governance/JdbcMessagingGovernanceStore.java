package com.aurevia.smartclinic.messaging.governance;

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

import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ConnectionReference;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CopyRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.MessagingResource;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ProvenanceLink;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RecordAccessPolicy;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RevocationEffect;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDuty;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionPolicy;

/**
 * PostgreSQL-backed persistence boundary for MSG-07 messaging governance
 * (ARC-01, ARC-02, OPS-02).
 *
 * <p>V62 owns the eleven tenant tables and their forced row-level security.
 * Every operation runs in the tenant-guarded transaction, which binds
 * {@code app.current_org} and {@code app.actor} after the transaction opens;
 * forced RLS therefore confines each statement to the active organization and
 * every read also names the active organization explicitly. Writes carry the
 * method's {@code organizationId} so the policy's {@code WITH CHECK} rejects a
 * mismatch.</p>
 *
 * <p>Membership and roles are owned by the tenancy module: this adapter reads
 * the authoritative {@code public.has_active_membership} and
 * {@code public.active_roles} folds rather than projecting a second copy, so a
 * deactivation takes effect on the next request. Append-only ledgers are
 * ordered by a database-owned sequence; current-state configuration is
 * upserted. No method copies a clinical payload into these governance facts.</p>
 */
@Repository
public class JdbcMessagingGovernanceStore implements MessagingGovernanceStore {

    private static final RowMapper<AuditEvent> AUDIT_MAPPER = JdbcMessagingGovernanceStore::mapAudit;
    private static final RowMapper<RetentionPolicy> POLICY_MAPPER = JdbcMessagingGovernanceStore::mapPolicy;
    private static final RowMapper<MessagingResource> RESOURCE_MAPPER = JdbcMessagingGovernanceStore::mapResource;
    private static final RowMapper<RetentionDuty> DUTY_MAPPER = JdbcMessagingGovernanceStore::mapDuty;
    private static final RowMapper<LegalHold> HOLD_MAPPER = JdbcMessagingGovernanceStore::mapHold;
    private static final RowMapper<RecordAccessPolicy> ACCESS_MAPPER = JdbcMessagingGovernanceStore::mapAccess;
    private static final RowMapper<ConnectionReference> CONNECTION_MAPPER = JdbcMessagingGovernanceStore::mapConnection;
    private static final RowMapper<ProvenanceLink> PROVENANCE_MAPPER = JdbcMessagingGovernanceStore::mapProvenance;
    private static final RowMapper<ExportRecord> EXPORT_MAPPER = JdbcMessagingGovernanceStore::mapExport;
    private static final RowMapper<CopyRequest> COPY_MAPPER = JdbcMessagingGovernanceStore::mapCopy;
    private static final RowMapper<RevocationEffect> REVOCATION_MAPPER = JdbcMessagingGovernanceStore::mapRevocation;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL governance store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcMessagingGovernanceStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        // organizationId is intentionally unused: the guarded transaction binds
        // the active tenant and the fold is tenant-scoped by RLS.
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)",
                Map.of("subject", subject), Boolean.class)));
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        // The fold returns every role currently in force for an active member;
        // DISTINCT and validity filtering are owned by the tenancy module.
        return transactions.execute(status -> jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject) ORDER BY role_key",
                Map.of("subject", subject), String.class));
    }

    @Override
    public void appendAudit(AuditEvent event) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_audit_event
                    (organization_id, id, subject, purpose, action, resource_id, unit_id,
                     correlation_id, outcome, occurred_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :subject, :purpose, :action,
                        :resourceId, :unitId, :correlationId, :outcome, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", event.organizationId())
                        .addValue("id", event.id())
                        .addValue("subject", event.subject())
                        .addValue("purpose", event.purpose())
                        .addValue("action", event.action().wire())
                        .addValue("resourceId", event.resourceId())
                        .addValue("unitId", event.unitId())
                        .addValue("correlationId", event.correlationId())
                        .addValue("outcome", event.outcome().wire())
                        .addValue("occurredAt", dateTime(event.occurredAt()))));
    }

    @Override
    public List<AuditEvent> auditEvents(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, subject, purpose, action, resource_id,
                       unit_id, correlation_id, outcome, occurred_at
                  FROM public.messaging_audit_event
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY seq
                """, Map.of("organizationId", organizationId), AUDIT_MAPPER));
    }

    @Override
    public List<AuditEvent> auditEvents(String organizationId, String resourceId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, subject, purpose, action, resource_id,
                       unit_id, correlation_id, outcome, occurred_at
                  FROM public.messaging_audit_event
                 WHERE organization_id = CAST(:organizationId AS uuid) AND resource_id = :resourceId
                 ORDER BY seq
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("resourceId", resourceId), AUDIT_MAPPER));
    }

    @Override
    public void putRetentionPolicy(String organizationId, RetentionPolicy policy) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_retention_policy
                    (organization_id, resource_type, retention_days)
                VALUES (CAST(:organizationId AS uuid), :resourceType, :retentionDays)
                ON CONFLICT (organization_id, resource_type)
                    DO UPDATE SET retention_days = EXCLUDED.retention_days
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("resourceType", policy.resourceType())
                        .addValue("retentionDays", policy.retentionDays())));
    }

    @Override
    public Optional<RetentionPolicy> retentionPolicy(String organizationId, String resourceType) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, resource_type, retention_days
                  FROM public.messaging_retention_policy
                 WHERE organization_id = CAST(:organizationId AS uuid) AND resource_type = :resourceType
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("resourceType", resourceType), POLICY_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<MessagingResource> resources(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, resource_type, created_at
                  FROM public.messaging_resource
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY seq
                """, Map.of("organizationId", organizationId), RESOURCE_MAPPER));
    }

    @Override
    public void putRetentionDuty(String organizationId, RetentionDuty duty) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_retention_duty
                    (organization_id, resource_id, sender_retention_days, recipient_retention_days)
                VALUES (CAST(:organizationId AS uuid), :resourceId, :senderDays, :recipientDays)
                ON CONFLICT (organization_id, resource_id) DO UPDATE
                    SET sender_retention_days = EXCLUDED.sender_retention_days,
                        recipient_retention_days = EXCLUDED.recipient_retention_days
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("resourceId", duty.resourceId())
                        .addValue("senderDays", duty.senderRetentionDays())
                        .addValue("recipientDays", duty.recipientRetentionDays())));
    }

    @Override
    public Optional<RetentionDuty> retentionDuty(String organizationId, String resourceId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, resource_id, sender_retention_days, recipient_retention_days
                  FROM public.messaging_retention_duty
                 WHERE organization_id = CAST(:organizationId AS uuid) AND resource_id = :resourceId
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("resourceId", resourceId), DUTY_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putLegalHold(String organizationId, LegalHold hold) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_legal_hold
                    (organization_id, id, resource_id, reason, placed_by, placed_at,
                     released_by, released_at, released_reason)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :resourceId, :reason,
                        :placedBy, CAST(:placedAt AS timestamptz), :releasedBy,
                        CAST(:releasedAt AS timestamptz), :releasedReason)
                ON CONFLICT (organization_id, id) DO UPDATE
                    SET resource_id = EXCLUDED.resource_id, reason = EXCLUDED.reason,
                        placed_by = EXCLUDED.placed_by, placed_at = EXCLUDED.placed_at,
                        released_by = EXCLUDED.released_by, released_at = EXCLUDED.released_at,
                        released_reason = EXCLUDED.released_reason
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", hold.id())
                        .addValue("resourceId", hold.resourceId())
                        .addValue("reason", hold.reason())
                        .addValue("placedBy", hold.placedBy())
                        .addValue("placedAt", dateTime(hold.placedAt()))
                        .addValue("releasedBy", hold.releasedBy())
                        .addValue("releasedAt", hold.releasedAt() == null ? null : dateTime(hold.releasedAt()))
                        .addValue("releasedReason", hold.releasedReason())));
    }

    @Override
    public Optional<LegalHold> legalHold(String organizationId, String holdId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, resource_id, reason, placed_by, placed_at,
                       released_by, released_at, released_reason
                  FROM public.messaging_legal_hold
                 WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", holdId), HOLD_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<LegalHold> legalHolds(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, resource_id, reason, placed_by, placed_at,
                       released_by, released_at, released_reason
                  FROM public.messaging_legal_hold
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY seq
                """, Map.of("organizationId", organizationId), HOLD_MAPPER));
    }

    @Override
    public void putRecordAccessPolicy(String organizationId, RecordAccessPolicy policy) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_record_access_policy
                    (organization_id, prior_conversations_readable, policy_version)
                VALUES (CAST(:organizationId AS uuid), :readable, :policyVersion)
                ON CONFLICT (organization_id) DO UPDATE
                    SET prior_conversations_readable = EXCLUDED.prior_conversations_readable,
                        policy_version = EXCLUDED.policy_version
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("readable", policy.priorConversationsReadable())
                        .addValue("policyVersion", policy.policyVersion())));
    }

    @Override
    public RecordAccessPolicy recordAccessPolicy(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, prior_conversations_readable, policy_version
                  FROM public.messaging_record_access_policy
                 WHERE organization_id = CAST(:organizationId AS uuid)
                """, Map.of("organizationId", organizationId), ACCESS_MAPPER)
                .stream().findFirst()
                .orElseGet(() -> new RecordAccessPolicy(organizationId, false, "UNKNOWN")));
    }

    @Override
    public void addConnection(String organizationId, ConnectionReference connection) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_connection
                    (organization_id, id, unit_id, patient_id, revoked_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :unitId, :patientId,
                        CAST(:revokedAt AS timestamptz))
                ON CONFLICT (organization_id, id) DO UPDATE
                    SET unit_id = EXCLUDED.unit_id, patient_id = EXCLUDED.patient_id,
                        revoked_at = EXCLUDED.revoked_at
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", connection.id())
                        .addValue("unitId", connection.unitId())
                        .addValue("patientId", connection.patientId())
                        .addValue("revokedAt",
                                connection.revokedAt() == null ? null : dateTime(connection.revokedAt()))));
    }

    @Override
    public List<ConnectionReference> allConnections(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, unit_id, patient_id, revoked_at
                  FROM public.messaging_connection
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY seq
                """, Map.of("organizationId", organizationId), CONNECTION_MAPPER));
    }

    @Override
    public Optional<ConnectionReference> connection(String organizationId, String connectionId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, unit_id, patient_id, revoked_at
                  FROM public.messaging_connection
                 WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", connectionId), CONNECTION_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putProvenanceLink(String organizationId, ProvenanceLink link) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_provenance_link
                    (organization_id, id, conversation_id, message_id, patient_id, chart_id,
                     source_type, source_reference, revision, linked_by, linked_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :conversationId, :messageId,
                        :patientId, :chartId, :sourceType, :sourceReference, :revision, :linkedBy,
                        CAST(:linkedAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", link.id())
                        .addValue("conversationId", link.conversationId())
                        .addValue("messageId", link.messageId())
                        .addValue("patientId", link.patientId())
                        .addValue("chartId", link.chartId())
                        .addValue("sourceType", link.sourceType())
                        .addValue("sourceReference", link.sourceReference())
                        .addValue("revision", link.revision())
                        .addValue("linkedBy", link.linkedBy())
                        .addValue("linkedAt", dateTime(link.linkedAt()))));
    }

    @Override
    public List<ProvenanceLink> provenanceLinks(String organizationId, String messageId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, conversation_id, message_id, patient_id,
                       chart_id, source_type, source_reference, revision, linked_by, linked_at
                  FROM public.messaging_provenance_link
                 WHERE organization_id = CAST(:organizationId AS uuid) AND message_id = :messageId
                 ORDER BY seq
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("messageId", messageId), PROVENANCE_MAPPER));
    }

    @Override
    public void putExport(String organizationId, ExportRecord export) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_export
                    (organization_id, id, scope, created_by, created_at, expires_at,
                     source_reference, source_revision, export_purpose)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :scope, :createdBy,
                        CAST(:createdAt AS timestamptz), CAST(:expiresAt AS timestamptz),
                        :sourceReference, :sourceRevision, :exportPurpose)
                ON CONFLICT (organization_id, id) DO NOTHING
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", export.id())
                        .addValue("scope", export.scope())
                        .addValue("createdBy", export.createdBy())
                        .addValue("createdAt", dateTime(export.createdAt()))
                        .addValue("expiresAt", dateTime(export.expiresAt()))
                        .addValue("sourceReference", export.provenance().sourceReference())
                        .addValue("sourceRevision", export.provenance().sourceRevision())
                        .addValue("exportPurpose", export.provenance().purpose())));
    }

    @Override
    public Optional<ExportRecord> export(String organizationId, String exportId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, scope, created_by, created_at, expires_at,
                       source_reference, source_revision, export_purpose
                  FROM public.messaging_export
                 WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", exportId), EXPORT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putCopyRequest(String organizationId, CopyRequest request) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_copy_request
                    (organization_id, id, resource_id, requested_by, purpose, status, created_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :resourceId, :requestedBy,
                        :purpose, :status, CAST(:createdAt AS timestamptz))
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", request.id())
                        .addValue("resourceId", request.resourceId())
                        .addValue("requestedBy", request.requestedBy())
                        .addValue("purpose", request.purpose())
                        .addValue("status", request.status())
                        .addValue("createdAt", dateTime(request.createdAt()))));
    }

    @Override
    public List<CopyRequest> copyRequests(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, resource_id, requested_by, purpose, status, created_at
                  FROM public.messaging_copy_request
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY seq
                """, Map.of("organizationId", organizationId), COPY_MAPPER));
    }

    @Override
    public void putRevocationEffect(String organizationId, RevocationEffect effect) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_revocation_effect
                    (organization_id, id, connection_id, messaging_blocked, attachment_access_blocked,
                     prior_conversations_readable, record_access_basis, recorded_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :connectionId, :messagingBlocked,
                        :attachmentBlocked, :priorReadable, :recordAccessBasis,
                        CAST(:recordedAt AS timestamptz))
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", effect.id())
                        .addValue("connectionId", effect.connectionId())
                        .addValue("messagingBlocked", effect.messagingBlocked())
                        .addValue("attachmentBlocked", effect.attachmentAccessBlocked())
                        .addValue("priorReadable", effect.priorConversationsReadable())
                        .addValue("recordAccessBasis", effect.recordAccessBasis())
                        .addValue("recordedAt", dateTime(effect.recordedAt()))));
    }

    @Override
    public List<RevocationEffect> revocationEffects(String organizationId, String connectionId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, connection_id, messaging_blocked,
                       attachment_access_blocked, prior_conversations_readable,
                       record_access_basis, recorded_at
                  FROM public.messaging_revocation_effect
                 WHERE organization_id = CAST(:organizationId AS uuid) AND connection_id = :connectionId
                 ORDER BY seq
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("connectionId", connectionId), REVOCATION_MAPPER));
    }

    private static AuditEvent mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditEvent(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("subject"), resultSet.getString("purpose"),
                AuditAction.from(resultSet.getString("action")),
                resultSet.getString("resource_id"), resultSet.getString("unit_id"),
                resultSet.getString("correlation_id"), Outcome.from(resultSet.getString("outcome")),
                instant(resultSet, "occurred_at"));
    }

    private static RetentionPolicy mapPolicy(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RetentionPolicy(resultSet.getString("organization_id"),
                resultSet.getString("resource_type"), resultSet.getLong("retention_days"));
    }

    private static MessagingResource mapResource(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MessagingResource(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("resource_type"), instant(resultSet, "created_at"));
    }

    private static RetentionDuty mapDuty(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RetentionDuty(resultSet.getString("organization_id"), resultSet.getString("resource_id"),
                resultSet.getLong("sender_retention_days"), resultSet.getLong("recipient_retention_days"));
    }

    private static LegalHold mapHold(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LegalHold(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("resource_id"), resultSet.getString("reason"),
                resultSet.getString("placed_by"), instant(resultSet, "placed_at"),
                resultSet.getString("released_by"), nullableInstant(resultSet, "released_at"),
                resultSet.getString("released_reason"));
    }

    private static RecordAccessPolicy mapAccess(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RecordAccessPolicy(resultSet.getString("organization_id"),
                resultSet.getBoolean("prior_conversations_readable"), resultSet.getString("policy_version"));
    }

    private static ConnectionReference mapConnection(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ConnectionReference(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("unit_id"), resultSet.getString("patient_id"),
                nullableInstant(resultSet, "revoked_at"));
    }

    private static ProvenanceLink mapProvenance(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProvenanceLink(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("conversation_id"), resultSet.getString("message_id"),
                resultSet.getString("patient_id"), resultSet.getString("chart_id"),
                resultSet.getString("source_type"), resultSet.getString("source_reference"),
                resultSet.getString("revision"), resultSet.getString("linked_by"),
                instant(resultSet, "linked_at"));
    }

    private static ExportRecord mapExport(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExportRecord(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("scope"), resultSet.getString("created_by"),
                instant(resultSet, "created_at"), instant(resultSet, "expires_at"),
                new ExportProvenance(resultSet.getString("source_reference"),
                        resultSet.getString("source_revision"), resultSet.getString("export_purpose")));
    }

    private static CopyRequest mapCopy(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CopyRequest(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("resource_id"), resultSet.getString("requested_by"),
                resultSet.getString("purpose"), resultSet.getString("status"),
                instant(resultSet, "created_at"));
    }

    private static RevocationEffect mapRevocation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RevocationEffect(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("connection_id"), resultSet.getBoolean("messaging_blocked"),
                resultSet.getBoolean("attachment_access_blocked"),
                resultSet.getBoolean("prior_conversations_readable"),
                resultSet.getString("record_access_basis"), instant(resultSet, "recorded_at"));
    }

    /**
     * Convert a server instant to the driver's timestamptz representation.
     *
     * <p>pgjdbc cannot infer a SQL type for {@link java.time.Instant}; the UTC
     * offset keeps the wire value unambiguous.</p>
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

    /**
     * Read a nullable timestamptz column, preserving "not yet recorded" as null.
     *
     * @param resultSet result row
     * @param column    column label
     * @return the instant, or {@code null} when the column is null
     * @throws SQLException when the column cannot be read
     */
    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
