package com.aurevia.scheduling;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The SQL boundary for appointments and their append-only history.
 *
 * <p>Reads fold a lapsed hold to {@code expired} so a stale reservation never
 * reads as live. Writes never delete: a state change and a slot change bump the
 * version by exactly one and are guarded by the {@code appointment_update_guard}
 * trigger, which also pins the ownership columns.</p>
 */
@Repository
public class AppointmentRepository {

    private static final String SELECT_APPOINTMENT = """
            SELECT a.id::text AS id, a.version, a.patient_id::text AS patient_id,
                   f.code AS facility_code, c.code AS clinician_code, r.code AS room_code,
                   a.visit_type, a.starts_at, a.ends_at,
                   CASE WHEN a.state = 'held' AND a.expires_at <= now()
                        THEN 'expired' ELSE a.state END AS state,
                   a.series_id::text AS series_id, a.expires_at, a.override_reason
              FROM public.appointment a
              JOIN public.facility f
                ON f.organization_id = a.organization_id AND f.id = a.facility_id
              JOIN public.scheduling_resource c
                ON c.organization_id = a.organization_id AND c.id = a.clinician_resource_id
              LEFT JOIN public.scheduling_resource r
                ON r.organization_id = a.organization_id AND r.id = a.room_resource_id
            """;

    private static final RowMapper<Appointment> APPOINTMENT_MAPPER = AppointmentRepository::mapAppointment;
    private static final RowMapper<AppointmentEvent> EVENT_MAPPER = AppointmentRepository::mapEvent;
    private static final RowMapper<AppointmentRow> APPOINTMENT_ROW_MAPPER = AppointmentRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public AppointmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check that the patient is visible in the active organization.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient record exists in this tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                """, Map.of("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Take a deterministic transaction-scoped lock on every protected resource
     * involved in a slot change.
     *
     * <p>Keys are locked in sorted order so two commands that touch the same pair
     * cannot form a lock cycle. The exclusion constraints remain the authority for
     * exclusivity; the advisory lock only removes the deadlock and retry noise.</p>
     *
     * @param resourceIds protected resources of the requested slot
     */
    public void lockResources(List<String> resourceIds) {
        List<String> ordered = new ArrayList<>(resourceIds);
        Collections.sort(ordered);
        for (String resourceId : ordered) {
            jdbc.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT pg_advisory_xact_lock(
                            hashtextextended(public.app_org()::text || ':' || :resourceId, 0))
                    ) locked
                    """, Map.of("resourceId", resourceId), Long.class);
        }
    }

    /**
     * Release lapsed holds that overlap the requested slot and record one history
     * event per released hold, so the next booking of the slot can proceed.
     *
     * <p>Scoped to the two protected resources of the requested slot: it is
     * bounded work, not a tenant-wide sweep.</p>
     *
     * @param now                 current instant
     * @param clinicianResourceId clinician resource of the requested slot
     * @param roomResourceId      room resource of the requested slot, or {@code null}
     * @param startsAt            requested slot start
     * @param endsAt              requested slot end
     */
    public void expireStaleHolds(Instant now, String clinicianResourceId, String roomResourceId,
                                 Instant startsAt, Instant endsAt) {
        List<ExpiredHold> released = jdbc.query("""
                UPDATE public.appointment
                   SET state = 'expired', version = version + 1
                 WHERE state = 'held' AND expires_at <= CAST(:now AS timestamptz)
                   AND tstzrange(starts_at, ends_at, '[)')
                       && tstzrange(CAST(:startsAt AS timestamptz), CAST(:endsAt AS timestamptz), '[)')
                   AND (clinician_resource_id = CAST(:clinicianId AS uuid)
                        OR (CAST(:roomId AS uuid) IS NOT NULL
                            AND room_resource_id = CAST(:roomId AS uuid)))
                RETURNING id::text, version
                """, new MapSqlParameterSource().addValue("now", now.toString())
                .addValue("startsAt", startsAt.toString()).addValue("endsAt", endsAt.toString())
                .addValue("clinicianId", clinicianResourceId)
                .addValue("roomId", roomResourceId), (resultSet, rowNumber) ->
                new ExpiredHold(resultSet.getString("id"), resultSet.getInt("version")));
        for (ExpiredHold hold : released) {
            appendEvent(hold.id(), hold.version(), "expired", AppointmentState.HELD,
                    AppointmentState.EXPIRED, "hold_ttl_elapsed", null, null);
        }
    }

    /**
     * Insert one appointment; the author is derived from the transaction actor.
     *
     * @param organizationId active organization identifier
     * @param id             new opaque appointment identifier
     * @param patientId      patient identifier
     * @param facilityId     facility identifier
     * @param clinicianId    clinician resource identifier
     * @param roomId         room resource identifier, or {@code null}
     * @param visitType      visit type code
     * @param startsAt       slot start
     * @param endsAt         slot end
     * @param state          initial state
     * @param seriesId       shared series identifier, or {@code null}
     * @param expiresAt      hold expiry, or {@code null}
     * @param overrideReason recorded availability override, or {@code null}
     * @return number of inserted rows
     */
    public int insert(String organizationId, String id, String patientId, String facilityId,
                      String clinicianId, String roomId, String visitType, Instant startsAt,
                      Instant endsAt, AppointmentState state, String seriesId, Instant expiresAt,
                      String overrideReason) {
        return jdbc.update("""
                INSERT INTO public.appointment
                    (organization_id, id, version, patient_id, facility_id, clinician_resource_id,
                     room_resource_id, visit_type, starts_at, ends_at, state, series_id,
                     expires_at, override_reason)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), 1,
                        CAST(:patientId AS uuid), CAST(:facilityId AS uuid),
                        CAST(:clinicianId AS uuid), CAST(:roomId AS uuid), :visitType,
                        CAST(:startsAt AS timestamptz), CAST(:endsAt AS timestamptz), :state,
                        CAST(:seriesId AS uuid), CAST(:expiresAt AS timestamptz), :overrideReason)
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId).addValue("id", id)
                .addValue("patientId", patientId).addValue("facilityId", facilityId)
                .addValue("clinicianId", clinicianId).addValue("roomId", roomId)
                .addValue("visitType", visitType).addValue("startsAt", startsAt.toString())
                .addValue("endsAt", endsAt.toString()).addValue("state", state.wire())
                .addValue("seriesId", seriesId).addValue("expiresAt", instantText(expiresAt))
                .addValue("overrideReason", overrideReason));
    }

    /**
     * Read one appointment, locking the row for the current transaction.
     *
     * @param id appointment identifier
     * @return the locked appointment when visible
     */
    public Optional<Appointment> loadForUpdate(String id) {
        return jdbc.query(SELECT_APPOINTMENT + " WHERE a.id = CAST(:id AS uuid) FOR UPDATE OF a",
                Map.of("id", id), APPOINTMENT_MAPPER).stream().findFirst();
    }

    /**
     * Read one appointment without locking it.
     *
     * @param id appointment identifier
     * @return the appointment when visible
     */
    public Optional<Appointment> find(String id) {
        return jdbc.query(SELECT_APPOINTMENT + " WHERE a.id = CAST(:id AS uuid)",
                Map.of("id", id), APPOINTMENT_MAPPER).stream().findFirst();
    }

    /**
     * Read one cursor-paged slice of the active tenant's appointments.
     *
     * <p>Forced RLS confines the query to the active organization, so no manual
     * organization predicate is needed. Ordering by scheduled start with the
     * immutable appointment id as tie-break makes the cursor stable even when two
     * appointments share a start instant. The state filter uses the same lapsed-hold
     * fold as the projection so a stale hold is filtered as {@code expired}.</p>
     *
     * @param from        inclusive lower bound of the scheduled-start range
     * @param to          inclusive upper bound of the scheduled-start range
     * @param resource    optional tenant-local clinician or room code, or {@code null}
     * @param state       optional effective appointment state, or {@code null}
     * @param cursorStart exclusive lower start bound from the previous page, or {@code null}
     * @param cursorId    immutable id that breaks a tie at the cursor start
     * @param limit       maximum rows to fetch (one more than the requested page size)
     * @return appointments with their immutable row id, in scheduled-start order
     */
    public List<AppointmentRow> query(Instant from, Instant to, String resource, AppointmentState state,
                                      Instant cursorStart, String cursorId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from.toString())
                .addValue("to", to.toString())
                .addValue("limit", limit);
        StringBuilder sql = new StringBuilder(SELECT_APPOINTMENT)
                .append(" WHERE a.starts_at >= CAST(:from AS timestamptz)")
                .append(" AND a.starts_at <= CAST(:to AS timestamptz)");
        if (resource != null) {
            sql.append(" AND (c.code = :resource OR r.code = :resource)");
            params.addValue("resource", resource);
        }
        if (state != null) {
            sql.append(" AND (CASE WHEN a.state = 'held' AND a.expires_at <= now() "
                    + "THEN 'expired' ELSE a.state END) = :state");
            params.addValue("state", state.wire());
        }
        if (cursorStart != null) {
            sql.append("""
                     AND (a.starts_at > CAST(:cursorStart AS timestamptz)
                          OR (a.starts_at = CAST(:cursorStart AS timestamptz)
                              AND a.id > CAST(:cursorId AS uuid)))
                     """);
            params.addValue("cursorStart", cursorStart.toString()).addValue("cursorId", cursorId);
        }
        sql.append(" ORDER BY a.starts_at, a.id LIMIT :limit");
        return jdbc.query(sql.toString(), params, APPOINTMENT_ROW_MAPPER);
    }

    /**
     * Move an appointment to a new state, enforcing the expected version.
     *
     * @param id              appointment identifier
     * @param expectedVersion version the caller read
     * @param state           next state
     * @return number of updated rows; {@code 0} means the version moved underneath
     */
    public int updateState(String id, int expectedVersion, AppointmentState state) {
        return jdbc.update("""
                UPDATE public.appointment SET state = :state, version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("state", state.wire()));
    }

    /**
     * Move an appointment to a new slot, enforcing the expected version.
     *
     * @param id              appointment identifier
     * @param expectedVersion version the caller read
     * @param clinicianId     clinician resource identifier
     * @param roomId          room resource identifier, or {@code null}
     * @param startsAt        new slot start
     * @param endsAt          new slot end
     * @param overrideReason  recorded override, or {@code null}
     * @return number of updated rows; {@code 0} means the version moved underneath
     */
    public int updateSlot(String id, int expectedVersion, String clinicianId, String roomId,
                          Instant startsAt, Instant endsAt, String overrideReason) {
        return jdbc.update("""
                UPDATE public.appointment
                   SET clinician_resource_id = CAST(:clinicianId AS uuid),
                       room_resource_id = CAST(:roomId AS uuid),
                       starts_at = CAST(:startsAt AS timestamptz),
                       ends_at = CAST(:endsAt AS timestamptz),
                       override_reason = :overrideReason,
                       version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("clinicianId", clinicianId)
                .addValue("roomId", roomId).addValue("startsAt", startsAt.toString())
                .addValue("endsAt", endsAt.toString()).addValue("overrideReason", overrideReason));
    }

    /**
     * Append one immutable history event.
     *
     * @param appointmentId appointment identifier
     * @param version       appointment revision the event describes
     * @param action        history action key
     * @param fromState     previous state, or {@code null}
     * @param toState       resulting state
     * @param reason        recorded reason, or {@code null}
     * @param previousStart slot start before a reschedule, or {@code null}
     * @param previousEnd   slot end before a reschedule, or {@code null}
     */
    public void appendEvent(String appointmentId, int version, String action,
                            AppointmentState fromState, AppointmentState toState, String reason,
                            Instant previousStart, Instant previousEnd) {
        jdbc.update("""
                INSERT INTO public.appointment_event
                    (organization_id, appointment_id, appointment_version, action, from_state,
                     to_state, reason, previous_starts_at, previous_ends_at)
                VALUES (public.app_org(), CAST(:appointmentId AS uuid), :version, :action, :fromState,
                        :toState, :reason, CAST(:previousStart AS timestamptz),
                        CAST(:previousEnd AS timestamptz))
                """, new MapSqlParameterSource().addValue("appointmentId", appointmentId)
                .addValue("version", version).addValue("action", action)
                .addValue("fromState", fromState == null ? null : fromState.wire())
                .addValue("toState", toState.wire()).addValue("reason", reason)
                .addValue("previousStart", instantText(previousStart))
                .addValue("previousEnd", instantText(previousEnd)));
    }

    /**
     * Read the append-only history of one appointment, oldest first.
     *
     * @param appointmentId appointment identifier
     * @return the history entries
     */
    public List<AppointmentEvent> history(String appointmentId) {
        return jdbc.query("""
                SELECT id::text, appointment_version, action, from_state, to_state, reason,
                       previous_starts_at, previous_ends_at, actor_id::text, occurred_at
                  FROM public.appointment_event
                 WHERE appointment_id = CAST(:appointmentId AS uuid)
                 ORDER BY occurred_at, id
                """, Map.of("appointmentId", appointmentId), EVENT_MAPPER);
    }

    /**
     * Find appointments already occupying either protected resource of a slot.
     *
     * <p>Called for every occurrence before any row of a series is inserted, so
     * one command can report all clashes and reject the whole series. The
     * partial exclusion constraints remain the authority for concurrent writers.</p>
     *
     * @param clinicianId clinician resource of the requested slot
     * @param roomId      room resource of the requested slot, or {@code null}
     * @param startsAt    requested slot start
     * @param endsAt      requested slot end
     * @return immutable identifiers of the conflicting appointments
     */
    public List<String> findConflicts(String clinicianId, String roomId, Instant startsAt,
                                      Instant endsAt) {
        return jdbc.query("""
                SELECT a.id::text AS id
                  FROM public.appointment a
                 WHERE a.state IN ('held', 'booked', 'checked_in', 'in_progress')
                   -- A lapsed hold is released by the booking path, not a real clash.
                   AND (a.state <> 'held' OR a.expires_at > now())
                   AND tstzrange(a.starts_at, a.ends_at, '[)')
                       && tstzrange(CAST(:startsAt AS timestamptz),
                                   CAST(:endsAt AS timestamptz), '[)')
                   AND (a.clinician_resource_id = CAST(:clinicianId AS uuid)
                        OR (CAST(:roomId AS uuid) IS NOT NULL
                            AND a.room_resource_id = CAST(:roomId AS uuid)))
                 ORDER BY a.starts_at, a.id
                """, new MapSqlParameterSource().addValue("startsAt", startsAt.toString())
                .addValue("endsAt", endsAt.toString()).addValue("clinicianId", clinicianId)
                .addValue("roomId", roomId), (resultSet, rowNumber) -> resultSet.getString("id"));
    }

    /**
     * Persist the recurrence pattern that produced a series.
     *
     * @param id              new opaque template identifier
     * @param intervalWeeks   weeks between occurrences
     * @param occurrenceCount fixed count, or {@code null} for an end date
     * @param endDate         ISO-8601 local end date, or {@code null}
     * @param localTime       local clock time of every occurrence
     * @param timeZone        facility IANA zone identifier
     * @return number of inserted rows
     */
    public int insertRecurrenceTemplate(String id, int intervalWeeks, Integer occurrenceCount,
                                        String endDate, String localTime, String timeZone) {
        return jdbc.update("""
                INSERT INTO public.recurrence_template
                    (organization_id, id, interval_weeks, occurrence_count, end_local_date,
                     local_start_time, time_zone)
                VALUES (public.app_org(), CAST(:id AS uuid), :intervalWeeks,
                        CAST(:occurrenceCount AS integer), CAST(:endDate AS date),
                        CAST(:localTime AS time), :timeZone)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("intervalWeeks", intervalWeeks).addValue("occurrenceCount", occurrenceCount)
                .addValue("endDate", endDate).addValue("localTime", localTime)
                .addValue("timeZone", timeZone));
    }

    /**
     * Persist the shared identity of a booked series.
     *
     * @param id             new opaque series identifier
     * @param templateId     recurrence template that produced the series
     * @param patientId      patient identifier
     * @param facilityId     facility identifier
     * @param clinicianId    clinician resource identifier
     * @param roomId         room resource identifier, or {@code null}
     * @param visitType      visit type code
     * @param firstStartsAt  UTC start of the first occurrence
     * @return number of inserted rows
     */
    public int insertAppointmentSeries(String id, String templateId, String patientId,
                                       String facilityId, String clinicianId, String roomId,
                                       String visitType, Instant firstStartsAt) {
        return jdbc.update("""
                INSERT INTO public.appointment_series
                    (organization_id, id, template_id, patient_id, facility_id,
                     clinician_resource_id, room_resource_id, visit_type, first_starts_at)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:templateId AS uuid),
                        CAST(:patientId AS uuid), CAST(:facilityId AS uuid),
                        CAST(:clinicianId AS uuid), CAST(:roomId AS uuid), :visitType,
                        CAST(:firstStartsAt AS timestamptz))
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("templateId", templateId).addValue("patientId", patientId)
                .addValue("facilityId", facilityId).addValue("clinicianId", clinicianId)
                .addValue("roomId", roomId).addValue("visitType", visitType)
                .addValue("firstStartsAt", firstStartsAt.toString()));
    }

    /**
     * Persist one immutable occurrence of a series and the appointment it booked.
     *
     * @param id             new opaque occurrence identifier
     * @param seriesId       owning series identifier
     * @param appointmentId  appointment booked for this occurrence
     * @param occurrenceIndex one-based position in the series
     * @param plannedStart    originally planned UTC start, kept after any edit
     * @return number of inserted rows
     */
    public int insertSeriesOccurrence(String id, String seriesId, String appointmentId,
                                      int occurrenceIndex, Instant plannedStart) {
        return jdbc.update("""
                INSERT INTO public.series_occurrence
                    (organization_id, id, series_id, appointment_id, occurrence_index, planned_starts_at)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:seriesId AS uuid),
                        CAST(:appointmentId AS uuid), :occurrenceIndex,
                        CAST(:plannedStart AS timestamptz))
                """, new MapSqlParameterSource().addValue("id", id).addValue("seriesId", seriesId)
                .addValue("appointmentId", appointmentId).addValue("occurrenceIndex", occurrenceIndex)
                .addValue("plannedStart", plannedStart.toString()));
    }

    private static String instantText(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Appointment mapAppointment(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Appointment(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("facility_code"),
                resultSet.getString("clinician_code"), resultSet.getString("room_code"),
                resultSet.getString("visit_type"), instant(resultSet, "starts_at"),
                instant(resultSet, "ends_at"), AppointmentState.from(resultSet.getString("state")),
                resultSet.getString("series_id"), instant(resultSet, "expires_at"),
                resultSet.getString("override_reason"));
    }

    private static AppointmentRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AppointmentRow(mapAppointment(resultSet, rowNumber), resultSet.getString("id"));
    }

    private static AppointmentEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AppointmentEvent(resultSet.getString("id"), resultSet.getInt("appointment_version"),
                resultSet.getString("action"), resultSet.getString("from_state"),
                resultSet.getString("to_state"), resultSet.getString("reason"),
                instant(resultSet, "previous_starts_at"), instant(resultSet, "previous_ends_at"),
                resultSet.getString("actor_id"), instant(resultSet, "occurred_at"));
    }

    /**
     * One paged row: the appointment projection and its immutable row id.
     *
     * @param appointment appointment projection
     * @param id          immutable appointment id used as the cursor tie-break
     * @return this record
     */
    public record AppointmentRow(Appointment appointment, String id) {
    }

    /**
     * One released hold, used to append its history event.
     *
     * @param id      appointment identifier
     * @param version version after the state change
     */
    private record ExpiredHold(String id, int version) {
    }
}
