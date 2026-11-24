package com.aurevia.exchange.critical;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.exchange.critical.CriticalContract.Event;
import com.aurevia.exchange.critical.CriticalContract.EventType;
import com.aurevia.exchange.critical.CriticalContract.Result;
import com.aurevia.exchange.critical.CriticalContract.State;

/**
 * The single SQL boundary for XCH-05 critical results.
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced
 * row-level security confines it to the active organization. The event ledger is
 * append-only for every role and the actor is always derived from the
 * transaction's {@code app.actor} binding, never from caller input.</p>
 */
@Repository
public class CriticalResultRepository {

    private static final String SELECT_RESULT = """
            SELECT id::text AS id, version, patient_id::text AS patient_id, source_system,
                   source_id, order_reference, accession, result_summary, observed_value, unit,
                   read_back_required, state, verified_by_subject, verified_at, owner_subject,
                   due_at, primary_channel, backup_channel, backup_recipient, created_at
              FROM public.critical_result
            """;

    private static final RowMapper<Result> RESULT_MAPPER = CriticalResultRepository::mapResult;
    private static final RowMapper<Event> EVENT_MAPPER = CriticalResultRepository::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public CriticalResultRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership with the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List the roles currently in force for a subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Read one approved site configuration value through the version fold.
     *
     * @param key tenant-local policy key
     * @return the effective value, or empty when no approved policy exists
     */
    public Optional<String> approvedSetting(String key) {
        return jdbc.query("SELECT value FROM public.effective_config(:key)", Map.of("key", key),
                (resultSet, rowNumber) -> resultSet.getString("value")).stream().findFirst();
    }

    /**
     * Check that a patient is visible in the active organization.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient exists in this tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                """, Map.of("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Insert a new unverified critical result.
     *
     * @param id               new opaque identifier
     * @param patientId        owning patient
     * @param sourceSystem     source system name
     * @param sourceId         source identifier
     * @param orderReference   originating order reference, or {@code null}
     * @param accession        source accession, or {@code null}
     * @param resultSummary    synthetic narrative
     * @param observedValue    observed critical value
     * @param unit             observation unit
     * @param readBackRequired whether a verbatim read-back is required
     * @return number of inserted rows
     */
    public int insert(String id, String patientId, String sourceSystem, String sourceId,
                      String orderReference, String accession, String resultSummary,
                      String observedValue, String unit, boolean readBackRequired) {
        return jdbc.update("""
                INSERT INTO public.critical_result
                    (organization_id, id, version, patient_id, source_system, source_id,
                     order_reference, accession, result_summary, observed_value, unit,
                     read_back_required, state)
                VALUES (public.app_org(), CAST(:id AS uuid), 1, CAST(:patientId AS uuid),
                        :sourceSystem, :sourceId, :orderReference, :accession, :resultSummary,
                        :observedValue, :unit, :readBackRequired, 'unverified')
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("patientId", patientId).addValue("sourceSystem", sourceSystem)
                .addValue("sourceId", sourceId).addValue("orderReference", orderReference)
                .addValue("accession", accession).addValue("resultSummary", resultSummary)
                .addValue("observedValue", observedValue).addValue("unit", unit)
                .addValue("readBackRequired", readBackRequired));
    }

    /**
     * Read one critical result.
     *
     * @param id result identifier
     * @return the result when visible
     */
    public Optional<Result> find(String id) {
        return jdbc.query(SELECT_RESULT + " WHERE id = CAST(:id AS uuid)",
                Map.of("id", id), RESULT_MAPPER).stream().findFirst();
    }

    /**
     * Read one critical result, locking its row for the current transaction.
     *
     * @param id result identifier
     * @return the locked result when visible
     */
    public Optional<Result> loadForUpdate(String id) {
        return jdbc.query(SELECT_RESULT + " WHERE id = CAST(:id AS uuid) FOR UPDATE",
                Map.of("id", id), RESULT_MAPPER).stream().findFirst();
    }

    /**
     * Assign the responsible owner, deadline and approved escalation policy.
     *
     * @param id              result identifier
     * @param expectedVersion version read by the caller
     * @param ownerSubject    responsible owner
     * @param dueAt           acknowledgment deadline
     * @param primaryChannel  approved channel before the deadline
     * @param backupChannel   approved channel when overdue or down
     * @param backupRecipient approved backup recipient
     * @return number of updated rows; zero means the version moved
     */
    public int assign(String id, int expectedVersion, String ownerSubject, Instant dueAt,
                      String primaryChannel, String backupChannel, String backupRecipient) {
        return jdbc.update("""
                UPDATE public.critical_result
                   SET owner_subject = :ownerSubject, due_at = CAST(:dueAt AS timestamptz),
                       primary_channel = :primaryChannel, backup_channel = :backupChannel,
                       backup_recipient = :backupRecipient, version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("ownerSubject", ownerSubject)
                .addValue("dueAt", dueAt.toString()).addValue("primaryChannel", primaryChannel)
                .addValue("backupChannel", backupChannel).addValue("backupRecipient", backupRecipient));
    }

    /**
     * Record verification and activate the alert.
     *
     * @param id               result identifier
     * @param expectedVersion  version read by the caller
     * @param verifiedBySubject authenticated verifier
     * @param verifiedAt       verification instant
     * @return number of updated rows; zero means the version moved
     */
    public int verify(String id, int expectedVersion, String verifiedBySubject, Instant verifiedAt) {
        return jdbc.update("""
                UPDATE public.critical_result
                   SET verified_by_subject = :verifiedBySubject,
                       verified_at = CAST(:verifiedAt AS timestamptz),
                       state = 'awaiting_ack', version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("verifiedBySubject", verifiedBySubject)
                .addValue("verifiedAt", verifiedAt.toString()));
    }

    /**
     * Record the clinician acknowledgment.
     *
     * @param id             result identifier
     * @param expectedVersion version read by the caller
     * @return number of updated rows; zero means the version moved
     */
    public int acknowledge(String id, int expectedVersion) {
        return jdbc.update("""
                UPDATE public.critical_result SET state = 'acknowledged', version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion));
    }

    /**
     * Close the alert after follow-up.
     *
     * @param id             result identifier
     * @param expectedVersion version read by the caller
     * @return number of updated rows; zero means the version moved
     */
    public int close(String id, int expectedVersion) {
        return jdbc.update("""
                UPDATE public.critical_result SET state = 'closed', version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion));
    }

    /**
     * Append one immutable fact to the event ledger.
     *
     * @param criticalResultId owning result
     * @param version          revision the fact is bound to
     * @param eventType        fact type
     * @param recipient        notified recipient, or {@code null}
     * @param channel          channel used, or {@code null}
     * @param outcome          recorded outcome, or {@code null}
     * @param detail           non-sensitive detail, or {@code null}
     */
    public void appendEvent(String criticalResultId, int version, EventType eventType,
                            String recipient, String channel, String outcome, String detail) {
        jdbc.update("""
                INSERT INTO public.critical_result_event
                    (organization_id, critical_result_id, critical_result_version, event_type,
                     recipient, channel, outcome, detail)
                VALUES (public.app_org(), CAST(:criticalResultId AS uuid), :version, :eventType,
                        :recipient, :channel, :outcome, :detail)
                """, new MapSqlParameterSource().addValue("criticalResultId", criticalResultId)
                .addValue("version", version).addValue("eventType", eventType.wire())
                .addValue("recipient", recipient).addValue("channel", channel)
                .addValue("outcome", outcome).addValue("detail", detail));
    }

    /**
     * Read the full event trail of one result in chronological order.
     *
     * @param criticalResultId result identifier
     * @return immutable, ordered events
     */
    public List<Event> events(String criticalResultId) {
        return jdbc.query("""
                SELECT id::text AS id, critical_result_id::text AS critical_result_id,
                       critical_result_version, event_type, actor_id::text AS actor_id,
                       recipient, channel, outcome, detail, occurred_at
                  FROM public.critical_result_event
                 WHERE critical_result_id = CAST(:criticalResultId AS uuid)
                 ORDER BY occurred_at, id
                """, Map.of("criticalResultId", criticalResultId), EVENT_MAPPER);
    }

    /**
     * Check whether a delivered escalation attempt exists for a revision.
     *
     * @param criticalResultId result identifier
     * @param version          bound revision
     * @return {@code true} when a delivered attempt exists
     */
    public boolean hasDeliveredAttempt(String criticalResultId, int version) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.critical_result_event
                     WHERE critical_result_id = CAST(:criticalResultId AS uuid)
                       AND critical_result_version = :version
                       AND event_type = 'escalation_attempt' AND outcome = 'delivered')
                """, new MapSqlParameterSource().addValue("criticalResultId", criticalResultId)
                .addValue("version", version), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Check whether a fact of a given type exists for a result.
     *
     * @param criticalResultId result identifier
     * @param eventType        fact type
     * @return {@code true} when at least one such fact exists
     */
    public boolean hasEvent(String criticalResultId, EventType eventType) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.critical_result_event
                     WHERE critical_result_id = CAST(:criticalResultId AS uuid)
                       AND event_type = :eventType)
                """, new MapSqlParameterSource().addValue("criticalResultId", criticalResultId)
                .addValue("eventType", eventType.wire()), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * List verified, owned alerts that are past their deadline.
     *
     * @param now current application instant
     * @return overdue results
     */
    public List<Result> overdue(Instant now) {
        return jdbc.query(SELECT_RESULT
                        + " WHERE state = 'awaiting_ack' AND due_at < CAST(:now AS timestamptz)"
                        + " ORDER BY due_at, id",
                Map.of("now", now.toString()), RESULT_MAPPER);
    }

    /**
     * List all recorded roster-gap facts.
     *
     * @return roster-gap events
     */
    public List<Event> rosterGaps() {
        return eventsOfType(EventType.ROSTER_GAP);
    }

    /**
     * List all failed escalation attempts.
     *
     * @return failed notification events
     */
    public List<Event> failedNotifications() {
        return jdbc.query(EVENT_SELECT + " WHERE event_type = 'escalation_attempt' AND outcome = 'failed'"
                + " ORDER BY occurred_at, id", EVENT_MAPPER);
    }

    private static final String EVENT_SELECT = """
            SELECT id::text AS id, critical_result_id::text AS critical_result_id,
                   critical_result_version, event_type, actor_id::text AS actor_id,
                   recipient, channel, outcome, detail, occurred_at
              FROM public.critical_result_event
            """;

    private List<Event> eventsOfType(EventType eventType) {
        return jdbc.query(EVENT_SELECT + " WHERE event_type = :eventType ORDER BY occurred_at, id",
                Map.of("eventType", eventType.wire()), EVENT_MAPPER);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Result mapResult(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Result(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("source_system"),
                resultSet.getString("source_id"), resultSet.getString("order_reference"),
                resultSet.getString("accession"), resultSet.getString("result_summary"),
                resultSet.getString("observed_value"), resultSet.getString("unit"),
                resultSet.getBoolean("read_back_required"),
                State.from(resultSet.getString("state")), resultSet.getString("verified_by_subject"),
                instant(resultSet, "verified_at"), resultSet.getString("owner_subject"),
                instant(resultSet, "due_at"), resultSet.getString("primary_channel"),
                resultSet.getString("backup_channel"), resultSet.getString("backup_recipient"),
                instant(resultSet, "created_at"));
    }

    private static Event mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Event(resultSet.getString("id"), resultSet.getString("critical_result_id"),
                resultSet.getInt("critical_result_version"),
                EventType.from(resultSet.getString("event_type")), resultSet.getString("actor_id"),
                resultSet.getString("recipient"), resultSet.getString("channel"),
                resultSet.getString("outcome"), resultSet.getString("detail"),
                instant(resultSet, "occurred_at"));
    }
}
