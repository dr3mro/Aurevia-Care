package com.aurevia.scheduling.followup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tenant-owned JDBC store for follow-up suggestions and care-team tasks (CLN-06).
 *
 * <p>Every statement is parameterised and carries the active organization, which
 * V74 also enforces with forced row-level security. A write upserts the current
 * version of an immutable identifier; the prior rule explanation, override
 * provenance and OPS-02 audit events preserve the history of a change.</p>
 */
@Repository
public class JdbcFollowUpStore implements FollowUpStore {

    private static final RowMapper<FollowUpSuggestion> SUGGESTION_MAPPER = JdbcFollowUpStore::mapSuggestion;
    private static final RowMapper<CareTeamTask> TASK_MAPPER = JdbcFollowUpStore::mapTask;

    private static final String UPSERT_SUGGESTION = """
            INSERT INTO public.follow_up_suggestion
                (organization_id, id, patient_id, facility_code, visit_type, encounter_at,
                 time_zone, suggested_date, interval_days, basis, rule_explanation,
                 override_actor, override_reason, override_recorded_at,
                 override_original_date, override_replacement_date, confirmed, booking_id, version)
            VALUES
                (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:patientId AS uuid),
                 :facilityCode, :visitType, CAST(:encounterAt AS timestamptz), :timeZone,
                 CAST(:suggestedDate AS date), :intervalDays, :basis, :ruleExplanation,
                 :overrideActor, :overrideReason, CAST(:overrideRecordedAt AS timestamptz),
                 CAST(:overrideOriginalDate AS date), CAST(:overrideReplacementDate AS date),
                 :confirmed, :bookingId, :version)
            ON CONFLICT (organization_id, id) DO UPDATE SET
                 suggested_date = EXCLUDED.suggested_date,
                 interval_days = EXCLUDED.interval_days,
                 basis = EXCLUDED.basis,
                 rule_explanation = EXCLUDED.rule_explanation,
                 override_actor = EXCLUDED.override_actor,
                 override_reason = EXCLUDED.override_reason,
                 override_recorded_at = EXCLUDED.override_recorded_at,
                 override_original_date = EXCLUDED.override_original_date,
                 override_replacement_date = EXCLUDED.override_replacement_date,
                 confirmed = EXCLUDED.confirmed,
                 booking_id = EXCLUDED.booking_id,
                 version = EXCLUDED.version
            """;

    private static final String UPSERT_TASK = """
            INSERT INTO public.care_team_task
                (organization_id, id, follow_up_id, owner, due_date, instruction, status,
                 outcome, created_by)
            VALUES
                (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:followUpId AS uuid),
                 :owner, CAST(:dueDate AS date), :instruction, :status, :outcome, :createdBy)
            ON CONFLICT (organization_id, id) DO UPDATE SET
                 status = EXCLUDED.status, outcome = EXCLUDED.outcome
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the store.
     *
     * @param jdbc shared named-parameter JDBC template bound to the tenant-guarded transaction
     */
    public JdbcFollowUpStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveSuggestion(FollowUpSuggestion suggestion) {
        FollowUpOverride override = suggestion.override();
        jdbc.update(UPSERT_SUGGESTION, new MapSqlParameterSource()
                .addValue("organizationId", suggestion.organizationId())
                .addValue("id", suggestion.id())
                .addValue("patientId", suggestion.patientId())
                .addValue("facilityCode", suggestion.facilityCode())
                .addValue("visitType", suggestion.visitType())
                .addValue("encounterAt", suggestion.encounterAt().toString())
                .addValue("timeZone", suggestion.timeZone())
                .addValue("suggestedDate", suggestion.suggestedDate().toString())
                .addValue("intervalDays", suggestion.intervalDays())
                .addValue("basis", suggestion.basis())
                .addValue("ruleExplanation", suggestion.ruleExplanation())
                .addValue("overrideActor", override == null ? null : override.actor())
                .addValue("overrideReason", override == null ? null : override.reason())
                .addValue("overrideRecordedAt", override == null ? null : override.recordedAt().toString())
                .addValue("overrideOriginalDate", override == null ? null : override.originalDate().toString())
                .addValue("overrideReplacementDate", override == null ? null : override.replacementDate().toString())
                .addValue("confirmed", suggestion.confirmed())
                .addValue("bookingId", suggestion.bookingId())
                .addValue("version", suggestion.version()));
    }

    @Override
    public Optional<FollowUpSuggestion> findSuggestion(String organizationId, String id) {
        return jdbc.query("""
                SELECT id::text, organization_id::text, patient_id::text, facility_code, visit_type,
                       encounter_at, time_zone, suggested_date, interval_days, basis,
                       rule_explanation, override_actor, override_reason, override_recorded_at,
                       override_original_date, override_replacement_date, confirmed, booking_id, version
                  FROM public.follow_up_suggestion
                 WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("id", id), SUGGESTION_MAPPER).stream().findFirst();
    }

    @Override
    public void saveTask(CareTeamTask task) {
        jdbc.update(UPSERT_TASK, new MapSqlParameterSource()
                .addValue("organizationId", task.organizationId())
                .addValue("id", task.id())
                .addValue("followUpId", task.followUpId())
                .addValue("owner", task.owner())
                .addValue("dueDate", task.dueDate().toString())
                .addValue("instruction", task.instruction())
                .addValue("status", task.status())
                .addValue("outcome", task.outcome())
                .addValue("createdBy", task.createdBy()));
    }

    @Override
    public Optional<CareTeamTask> findTask(String organizationId, String id) {
        return jdbc.query("""
                SELECT id::text, organization_id::text, follow_up_id::text, owner, due_date,
                       instruction, status, outcome, created_by, created_at
                  FROM public.care_team_task
                 WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("id", id), TASK_MAPPER).stream().findFirst();
    }

    @Override
    public List<CareTeamTask> tasksFor(String organizationId, String followUpId) {
        return jdbc.query("""
                SELECT id::text, organization_id::text, follow_up_id::text, owner, due_date,
                       instruction, status, outcome, created_by, created_at
                  FROM public.care_team_task
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND follow_up_id = CAST(:followUpId AS uuid)
                 ORDER BY due_date, id
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("followUpId", followUpId), TASK_MAPPER);
    }

    static FollowUpSuggestion mapSuggestion(ResultSet resultSet, int rowNumber) throws SQLException {
        String overrideActor = resultSet.getString("override_actor");
        FollowUpOverride override = overrideActor == null ? null : new FollowUpOverride(
                overrideActor,
                resultSet.getString("override_reason"),
                instant(resultSet, "override_recorded_at"),
                resultSet.getObject("override_original_date", LocalDate.class),
                resultSet.getObject("override_replacement_date", LocalDate.class));
        Integer intervalDays = resultSet.getObject("interval_days", Integer.class);
        return new FollowUpSuggestion(
                resultSet.getString("id"),
                resultSet.getString("organization_id"),
                resultSet.getString("patient_id"),
                resultSet.getString("facility_code"),
                resultSet.getString("visit_type"),
                instant(resultSet, "encounter_at"),
                resultSet.getString("time_zone"),
                resultSet.getObject("suggested_date", LocalDate.class),
                intervalDays,
                resultSet.getString("basis"),
                resultSet.getString("rule_explanation"),
                override,
                resultSet.getBoolean("confirmed"),
                resultSet.getString("booking_id"),
                resultSet.getInt("version"));
    }

    static CareTeamTask mapTask(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CareTeamTask(
                resultSet.getString("id"),
                resultSet.getString("organization_id"),
                resultSet.getString("follow_up_id"),
                resultSet.getString("owner"),
                resultSet.getObject("due_date", LocalDate.class),
                resultSet.getString("instruction"),
                resultSet.getString("status"),
                resultSet.getString("outcome"),
                resultSet.getString("created_by"),
                instant(resultSet, "created_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        // The timestamp columns are NOT NULL (and an override always carries a
        // recorded instant), so a null here is a corrupt row, not a valid state.
        return java.util.Objects.requireNonNull(resultSet.getObject(column, OffsetDateTime.class),
                column + " must be present").toInstant();
    }
}
