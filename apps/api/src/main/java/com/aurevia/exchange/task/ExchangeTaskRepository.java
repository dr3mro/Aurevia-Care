package com.aurevia.exchange.task;

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

/**
 * SQL boundary for XCH-02 state and its append-only event ledger (ARC-02).
 *
 * <p>Every read and write is executed inside the tenant-guarded transaction, so
 * forced row-level security and the transaction-local {@code app.current_org}
 * binding conceal another tenant's rows. The review actor is never written by
 * SQL: the V20 trigger derives it from {@code app.actor}.</p>
 */
@Repository
public class ExchangeTaskRepository {

    private static final String SELECT_TASK = """
            SELECT id::text AS id, version, order_ref, workflow_state, transmission_state,
                   clinical_review_state, reason, clinical_review_actor_id::text AS reviewed_by,
                   clinical_reviewed_at, clinical_revision, created_at
              FROM public.exchange_task
             """;
    private static final RowMapper<ExchangeTask> TASK_MAPPER = ExchangeTaskRepository::mapTask;
    private static final RowMapper<ExchangeTaskEvent> EVENT_MAPPER = ExchangeTaskRepository::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public ExchangeTaskRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check that the subject still has active membership in the active tenant.
     *
     * @param subject authenticated subject
     * @return {@code true} when membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List role keys currently in force for the subject in the active tenant.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Insert a new work item in the three initial states.
     *
     * @param organizationId active organization identifier
     * @param id             opaque task identifier
     * @param orderRef       tenant-local order reference
     * @return number of inserted rows
     */
    public int insert(String organizationId, String id, String orderRef) {
        return jdbc.update("""
                INSERT INTO public.exchange_task (organization_id, id, order_ref)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :orderRef)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("id", id).addValue("orderRef", orderRef));
    }

    /**
     * Read one task, locking the row for the current transaction.
     *
     * @param id task identifier
     * @return the locked task when visible in the active tenant
     */
    public Optional<ExchangeTask> loadForUpdate(String id) {
        return jdbc.query(SELECT_TASK + " WHERE id = CAST(:id AS uuid) FOR UPDATE OF exchange_task",
                Map.of("id", id), TASK_MAPPER).stream().findFirst();
    }

    /**
     * Read one task without locking it.
     *
     * @param id task identifier
     * @return the task when visible in the active tenant
     */
    public Optional<ExchangeTask> find(String id) {
        return jdbc.query(SELECT_TASK + " WHERE id = CAST(:id AS uuid)",
                Map.of("id", id), TASK_MAPPER).stream().findFirst();
    }

    /**
     * Compare-and-set the three channels to the next snapshot.
     *
     * <p>The review actor column is deliberately omitted; V20 fills it from the
     * authenticated transaction when the review channel changes. The guard
     * trigger rejects any update that changes more than one channel or skips a
     * version.</p>
     *
     * @param id              task identifier
     * @param expectedVersion version the caller read
     * @param next            the complete next snapshot
     * @return number of updated rows; {@code 0} means the version moved
     */
    public int updateState(String id, int expectedVersion, ExchangeTask next) {
        return jdbc.update("""
                UPDATE public.exchange_task
                   SET workflow_state = :workflow, transmission_state = :transmission,
                       clinical_review_state = :review, reason = :reason,
                       clinical_reviewed_at = CAST(:reviewedAt AS timestamptz),
                       clinical_revision = :reviewedRevision,
                       version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("workflow", next.workflowState().wire())
                .addValue("transmission", next.transmissionState().wire())
                .addValue("review", next.clinicalReviewState().wire())
                .addValue("reason", next.reason())
                .addValue("reviewedAt", instantText(next.reviewedAt()))
                .addValue("reviewedRevision", next.reviewedRevision()));
    }

    /**
     * Append one immutable transition event in the caller's transaction.
     *
     * @param taskId           owning task identifier
     * @param version          task revision the event describes
     * @param dimension        state channel that changed
     * @param action           command audit action
     * @param fromState        previous wire state, or {@code null} on creation
     * @param toState          resulting wire state
     * @param reason           recorded reason, or {@code null}
     * @param reviewedRevision reviewed revision for clinical events, or {@code null}
     */
    public void appendEvent(String taskId, int version, ExchangeDimension dimension, String action,
                            String fromState, String toState, String reason, Integer reviewedRevision) {
        jdbc.update("""
                INSERT INTO public.exchange_task_transition
                    (organization_id, task_id, to_version, channel, action, from_state,
                     to_state, reason, reviewed_revision)
                VALUES (public.app_org(), CAST(:taskId AS uuid), :version, :channel, :action,
                        :fromState, :toState, :reason, :reviewedRevision)
                """, new MapSqlParameterSource().addValue("taskId", taskId)
                .addValue("version", version).addValue("channel", dimension.wire())
                .addValue("action", action).addValue("fromState", fromState)
                .addValue("toState", toState).addValue("reason", reason)
                .addValue("reviewedRevision", reviewedRevision));
    }

    /**
     * Read the append-only history of one task, oldest first.
     *
     * @param taskId task identifier
     * @return history entries
     */
    public List<ExchangeTaskEvent> history(String taskId) {
        return jdbc.query("""
                SELECT id::text, task_id::text, to_version, channel, action, from_state,
                       to_state, reason, reviewed_revision, actor_id::text, occurred_at
                  FROM public.exchange_task_transition
                 WHERE task_id = CAST(:taskId AS uuid)
                 ORDER BY occurred_at, id
                """, Map.of("taskId", taskId), EVENT_MAPPER);
    }

    private static String instantText(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static ExchangeTask mapTask(ResultSet resultSet, int rowNumber) throws SQLException {
        int revision = resultSet.getInt("clinical_revision");
        boolean revisionNull = resultSet.wasNull();
        return new ExchangeTask(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("order_ref"), WorkflowState.from(resultSet.getString("workflow_state")),
                TransmissionState.from(resultSet.getString("transmission_state")),
                ClinicalReviewState.from(resultSet.getString("clinical_review_state")),
                resultSet.getString("reason"), resultSet.getString("reviewed_by"),
                instant(resultSet, "clinical_reviewed_at"),
                revisionNull ? null : revision, instant(resultSet, "created_at"));
    }

    private static ExchangeTaskEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        int revision = resultSet.getInt("reviewed_revision");
        boolean revisionNull = resultSet.wasNull();
        return new ExchangeTaskEvent(resultSet.getString("id"), resultSet.getString("task_id"),
                resultSet.getInt("to_version"),
                ExchangeDimension.from(resultSet.getString("channel")), resultSet.getString("action"),
                resultSet.getString("from_state"), resultSet.getString("to_state"),
                resultSet.getString("reason"), revisionNull ? null : revision,
                resultSet.getString("actor_id"), instant(resultSet, "occurred_at"));
    }
}
