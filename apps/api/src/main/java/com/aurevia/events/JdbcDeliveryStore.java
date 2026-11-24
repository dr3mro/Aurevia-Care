package com.aurevia.events;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.events.DurableEventContract.AuditAction;
import com.aurevia.events.DurableEventContract.ConsumerReceipt;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.ExternalLink;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.ProcessingOutcome;
import com.aurevia.events.DurableEventContract.ReplayRecord;
import com.aurevia.events.DurableEventContract.Resolution;

/**
 * PostgreSQL-backed persistence boundary for ARC-05 delivery facts.
 *
 * <p>V55 owns the six tenant tables and their forced row-level security. Reads
 * add no manual organization predicate: the tenant-guarded transaction binds
 * {@code app.current_org()} and {@code app.actor}, so forced RLS confines every
 * statement to the active organization and the insert triggers derive
 * authorship from the same transaction. The {@code organizationId} argument on
 * each method identifies that same active tenant; membership and role folds
 * take their tenant scope from the bound GUC, so the argument is not rebound in
 * those two queries. Each operation runs in one guarded transaction, and the
 * idempotent outbox insert commits its audit fact in the same transaction in
 * {@code publishAtomically}.</p>
 *
 * <p>Every record carries only non-sensitive delivery metadata: opaque ids,
 * server instants, stable wire keys and a payload digest. No clinical payload
 * is written by this adapter.</p>
 */
@Repository
public class JdbcDeliveryStore implements DeliveryStore {

    private static final String OUTBOX_COLUMNS = """
            organization_id::text, id::text, aggregate_id::text, aggregate_version, sender,
            event_type, namespace, payload_digest, idempotency_key, state, attempt,
            next_attempt_at, created_at, detail
            """;
    private static final String RECEIPT_COLUMNS = """
            organization_id::text, id::text, external_event_id, sender, event_type, namespace,
            aggregate_id::text, aggregate_version, order_id::text, patient_id::text,
            outcome, detail, processed_at, actor_subject
            """;
    private static final String DEAD_LETTER_COLUMNS = """
            event_id::text, organization_id::text, reason, attempt, failed_at,
            reviewer_subject, reviewed_at, resolution, review_reason
            """;
    private static final String AUDIT_COLUMNS = """
            id::text, organization_id::text, actor_subject, purpose, action, resource_id,
            correlation_id, outcome, occurred_at
            """;

    private static final RowMapper<OutboxEvent> OUTBOX_MAPPER = JdbcDeliveryStore::mapOutbox;
    private static final RowMapper<ConsumerReceipt> RECEIPT_MAPPER = JdbcDeliveryStore::mapReceipt;
    private static final RowMapper<DeadLetterEntry> DEAD_LETTER_MAPPER = JdbcDeliveryStore::mapDeadLetter;
    private static final RowMapper<DeliveryAuditEvent> AUDIT_MAPPER = JdbcDeliveryStore::mapAudit;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL delivery store.
     *
     * @param jdbc          shared named-parameter JDBC template
     * @param transactions  tenant-guarded transaction template
     */
    public JdbcDeliveryStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return transactions.execute(status -> {
            Boolean active = jdbc.queryForObject(
                    "SELECT public.has_active_membership(:subject)",
                    new MapSqlParameterSource().addValue("subject", subject), Boolean.class);
            return Boolean.TRUE.equals(active);
        });
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        return transactions.execute(status -> jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)",
                new MapSqlParameterSource().addValue("subject", subject), String.class));
    }

    @Override
    public Optional<OutboxEvent> outboxEvent(String organizationId, String eventId) {
        return transactions.execute(status -> jdbc.query("SELECT " + OUTBOX_COLUMNS
                + "FROM public.delivery_outbox WHERE id = CAST(:eventId AS uuid)",
                new MapSqlParameterSource().addValue("eventId", eventId), OUTBOX_MAPPER)
                .stream().findFirst());
    }

    @Override
    public Optional<OutboxEvent> byIdempotencyKey(String organizationId, String sender,
                                                   String eventType, String key) {
        return transactions.execute(status -> jdbc.query("SELECT " + OUTBOX_COLUMNS
                + "FROM public.delivery_outbox "
                + "WHERE sender = :sender AND event_type = :eventType AND idempotency_key = :key",
                new MapSqlParameterSource().addValue("sender", sender)
                        .addValue("eventType", eventType).addValue("key", key), OUTBOX_MAPPER)
                .stream().findFirst());
    }

    @Override
    public OutboxEvent publishAtomically(String organizationId, OutboxEvent event,
                                         DeliveryAuditEvent audit) {
        return transactions.execute(status -> {
            List<OutboxEvent> inserted = jdbc.query("""
                    INSERT INTO public.delivery_outbox
                        (organization_id, id, aggregate_id, aggregate_version, sender, event_type,
                         namespace, payload_digest, idempotency_key, state, attempt,
                         next_attempt_at, created_at, detail)
                    VALUES (CAST(:organizationId AS uuid), CAST(:eventId AS uuid),
                            CAST(:aggregateId AS uuid), :aggregateVersion, :sender, :eventType,
                            :namespace, :payloadDigest, :idempotencyKey, :state, :attempt,
                            CAST(:nextAttemptAt AS timestamptz), CAST(:createdAt AS timestamptz), :detail)
                    ON CONFLICT (organization_id, sender, event_type, idempotency_key) DO NOTHING
                    """ + "RETURNING " + OUTBOX_COLUMNS, outboxParameters(event), OUTBOX_MAPPER);
            if (inserted.isEmpty()) {
                return byIdempotencyKey(organizationId, event.sender(), event.eventType(),
                        event.idempotencyKey()).orElseThrow();
            }
            appendAudit(organizationId, audit);
            return event;
        });
    }

    @Override
    public void putOutboxEvent(String organizationId, OutboxEvent event) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE public.delivery_outbox
                   SET aggregate_version = :aggregateVersion, state = :state, attempt = :attempt,
                       next_attempt_at = CAST(:nextAttemptAt AS timestamptz), detail = :detail
                 WHERE id = CAST(:eventId AS uuid)
                """, outboxParameters(event)));
    }

    @Override
    public void appendAudit(String organizationId, DeliveryAuditEvent audit) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.delivery_audit
                    (organization_id, id, purpose, action, resource_id, correlation_id, outcome, occurred_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :purpose, :action,
                        :resourceId, :correlationId, :outcome, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", audit.id())
                        .addValue("purpose", audit.purpose())
                        .addValue("action", audit.action().wire())
                        .addValue("resourceId", audit.resourceId())
                        .addValue("correlationId", audit.correlationId())
                        .addValue("outcome", audit.outcome().wire())
                        .addValue("occurredAt", dateTime(audit.occurredAt()))));
    }

    @Override
    public List<DeliveryAuditEvent> auditEvents(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + AUDIT_COLUMNS
                + "FROM public.delivery_audit ORDER BY occurred_at, id", AUDIT_MAPPER));
    }

    @Override
    public Optional<ConsumerReceipt> receipt(String organizationId, String namespace, String sender,
                                             String eventType, String externalEventId) {
        return transactions.execute(status -> jdbc.query("SELECT " + RECEIPT_COLUMNS
                + "FROM public.delivery_receipt "
                + "WHERE namespace = :namespace AND sender = :sender AND event_type = :eventType "
                + "AND external_event_id = :externalEventId",
                new MapSqlParameterSource().addValue("namespace", namespace)
                        .addValue("sender", sender).addValue("eventType", eventType)
                        .addValue("externalEventId", externalEventId), RECEIPT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public Optional<ConsumerReceipt> receiptById(String organizationId, String externalEventId) {
        return transactions.execute(status -> jdbc.query("SELECT " + RECEIPT_COLUMNS
                + "FROM public.delivery_receipt WHERE external_event_id = :externalEventId",
                new MapSqlParameterSource().addValue("externalEventId", externalEventId), RECEIPT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public OptionalInt maxVersion(String organizationId, String namespace, String sender,
                                  String eventType, String aggregateId) {
        return transactions.execute(status -> {
            Integer highest = jdbc.queryForObject("""
                    SELECT max(aggregate_version) FROM public.delivery_receipt
                     WHERE namespace = :namespace AND sender = :sender AND event_type = :eventType
                       AND aggregate_id = CAST(:aggregateId AS uuid)
                    """, new MapSqlParameterSource().addValue("namespace", namespace)
                            .addValue("sender", sender).addValue("eventType", eventType)
                            .addValue("aggregateId", aggregateId), Integer.class);
            return highest == null ? OptionalInt.empty() : OptionalInt.of(highest);
        });
    }

    @Override
    public void putReceipt(String organizationId, ConsumerReceipt receipt) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.delivery_receipt
                    (organization_id, id, external_event_id, sender, event_type, namespace,
                     aggregate_id, aggregate_version, order_id, patient_id, outcome, detail, processed_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :externalEventId,
                        :sender, :eventType, :namespace, CAST(:aggregateId AS uuid),
                        :aggregateVersion, CAST(:orderId AS uuid), CAST(:patientId AS uuid),
                        :outcome, :detail, CAST(:processedAt AS timestamptz))
                ON CONFLICT (organization_id, namespace, sender, event_type, external_event_id) DO NOTHING
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", receipt.receiptId())
                        .addValue("externalEventId", receipt.externalEventId())
                        .addValue("sender", receipt.sender())
                        .addValue("eventType", receipt.eventType())
                        .addValue("namespace", receipt.namespace())
                        .addValue("aggregateId", receipt.aggregateId())
                        .addValue("aggregateVersion", receipt.aggregateVersion())
                        .addValue("orderId", receipt.orderId())
                        .addValue("patientId", receipt.patientId())
                        .addValue("outcome", receipt.outcome().wire())
                        .addValue("detail", receipt.detail())
                        .addValue("processedAt", dateTime(receipt.processedAt()))));
    }

    @Override
    public void putDeadLetter(String organizationId, DeadLetterEntry entry) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.delivery_dead_letter
                    (organization_id, event_id, reason, attempt, failed_at,
                     reviewer_subject, reviewed_at, resolution, review_reason)
                VALUES (CAST(:organizationId AS uuid), CAST(:eventId AS uuid), :reason, :attempt,
                        CAST(:failedAt AS timestamptz), :reviewer, CAST(:reviewedAt AS timestamptz),
                        :resolution, :reviewReason)
                ON CONFLICT (organization_id, event_id) DO UPDATE SET
                    reviewer_subject = EXCLUDED.reviewer_subject,
                    reviewed_at = EXCLUDED.reviewed_at,
                    resolution = EXCLUDED.resolution,
                    review_reason = EXCLUDED.review_reason
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("eventId", entry.eventId())
                        .addValue("reason", entry.reason())
                        .addValue("attempt", entry.attempt())
                        .addValue("failedAt", dateTime(entry.failedAt()))
                        .addValue("reviewer", entry.reviewer())
                        .addValue("reviewedAt", dateTime(entry.reviewedAt()))
                        .addValue("resolution", Optional.ofNullable(entry.resolution())
                                .map(Resolution::wire).orElse(null))
                        .addValue("reviewReason", entry.reviewReason())));
    }

    @Override
    public Optional<DeadLetterEntry> deadLetter(String organizationId, String eventId) {
        return transactions.execute(status -> jdbc.query("SELECT " + DEAD_LETTER_COLUMNS
                + "FROM public.delivery_dead_letter WHERE event_id = CAST(:eventId AS uuid)",
                new MapSqlParameterSource().addValue("eventId", eventId), DEAD_LETTER_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<DeadLetterEntry> deadLetters(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + DEAD_LETTER_COLUMNS
                + "FROM public.delivery_dead_letter ORDER BY failed_at, event_id", DEAD_LETTER_MAPPER));
    }

    @Override
    public void putReplay(String organizationId, ReplayRecord replay) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.delivery_replay
                    (organization_id, id, original_event_id, reason, authorized_by_subject, replayed_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid),
                        CAST(:originalEventId AS uuid), :reason, :authorizedBy,
                        CAST(:replayedAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", replay.replayId())
                        .addValue("originalEventId", replay.originalEventId())
                        .addValue("reason", replay.reason())
                        .addValue("authorizedBy", replay.authorizedBy())
                        .addValue("replayedAt", dateTime(replay.replayedAt()))));
    }

    @Override
    public void putLink(String organizationId, ExternalLink link) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.delivery_link
                    (organization_id, id, external_event_id, order_id, patient_id,
                     target_aggregate_id, linked_by_subject, linked_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :externalEventId,
                        CAST(:orderId AS uuid), CAST(:patientId AS uuid),
                        CAST(:targetAggregateId AS uuid), :linkedBy, CAST(:linkedAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", link.linkId())
                        .addValue("externalEventId", link.externalEventId())
                        .addValue("orderId", link.orderId())
                        .addValue("patientId", link.patientId())
                        .addValue("targetAggregateId", link.targetAggregateId())
                        .addValue("linkedBy", link.linkedBy())
                        .addValue("linkedAt", dateTime(link.linkedAt()))));
    }

    @Override
    public boolean orderExists(String organizationId, String orderId) {
        return transactions.execute(status -> {
            Boolean exists = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM public.exchange_order WHERE id = CAST(:orderId AS uuid))
                    """, new MapSqlParameterSource().addValue("orderId", orderId), Boolean.class);
            return Boolean.TRUE.equals(exists);
        });
    }

    @Override
    public boolean patientExists(String organizationId, String patientId) {
        return transactions.execute(status -> {
            Boolean exists = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                    """, new MapSqlParameterSource().addValue("patientId", patientId), Boolean.class);
            return Boolean.TRUE.equals(exists);
        });
    }

    private static MapSqlParameterSource outboxParameters(OutboxEvent event) {
        return new MapSqlParameterSource()
                .addValue("organizationId", event.organizationId())
                .addValue("eventId", event.eventId())
                .addValue("aggregateId", event.aggregateId())
                .addValue("aggregateVersion", event.aggregateVersion())
                .addValue("sender", event.sender())
                .addValue("eventType", event.eventType())
                .addValue("namespace", event.namespace())
                .addValue("payloadDigest", event.payloadDigest())
                .addValue("idempotencyKey", event.idempotencyKey())
                .addValue("state", event.state().wire())
                .addValue("attempt", event.attempt())
                .addValue("nextAttemptAt", dateTime(event.nextAttemptAt()))
                .addValue("createdAt", dateTime(event.createdAt()))
                .addValue("detail", event.detail());
    }

    private static OutboxEvent mapOutbox(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OutboxEvent(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("aggregate_id"), resultSet.getInt("aggregate_version"),
                resultSet.getString("sender"), resultSet.getString("event_type"),
                resultSet.getString("namespace"), resultSet.getString("payload_digest"),
                resultSet.getString("idempotency_key"), DeliveryState.from(resultSet.getString("state")),
                resultSet.getInt("attempt"), instant(resultSet, "next_attempt_at"),
                instant(resultSet, "created_at"), resultSet.getString("detail"));
    }

    private static ConsumerReceipt mapReceipt(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ConsumerReceipt(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("external_event_id"), resultSet.getString("sender"),
                resultSet.getString("event_type"), resultSet.getString("namespace"),
                resultSet.getString("aggregate_id"), resultSet.getInt("aggregate_version"),
                resultSet.getString("order_id"), resultSet.getString("patient_id"),
                ProcessingOutcome.from(resultSet.getString("outcome")), resultSet.getString("detail"),
                instant(resultSet, "processed_at"), resultSet.getString("actor_subject"));
    }

    private static DeadLetterEntry mapDeadLetter(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DeadLetterEntry(resultSet.getString("event_id"), resultSet.getString("organization_id"),
                resultSet.getString("reason"), resultSet.getInt("attempt"),
                instant(resultSet, "failed_at"), resultSet.getString("reviewer_subject"),
                instant(resultSet, "reviewed_at"),
                Optional.ofNullable(resultSet.getString("resolution")).map(Resolution::from).orElse(null),
                resultSet.getString("review_reason"));
    }

    private static DeliveryAuditEvent mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DeliveryAuditEvent(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("actor_subject"), resultSet.getString("purpose"),
                AuditAction.from(resultSet.getString("action")), resultSet.getString("resource_id"),
                resultSet.getString("correlation_id"), Outcome.from(resultSet.getString("outcome")),
                instant(resultSet, "occurred_at"));
    }

    /**
     * Convert a server instant to the driver's timestamptz representation.
     *
     * <p>pgjdbc cannot infer a SQL type for {@link java.time.Instant}; the UTC
     * offset keeps the wire value unambiguous. A missing instant stays null so
     * an unscheduled retry writes SQL NULL.</p>
     *
     * @param instant server instant, or {@code null}
     * @return an offset date time, or {@code null}
     */
    private static OffsetDateTime dateTime(Instant instant) {
        return Optional.ofNullable(instant)
                .map(value -> OffsetDateTime.ofInstant(value, ZoneOffset.UTC)).orElse(null);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Optional.ofNullable(resultSet.getObject(column, OffsetDateTime.class))
                .map(OffsetDateTime::toInstant).orElse(null);
    }
}
