package com.aurevia.scheduling;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The SQL boundary for scheduling reference data: facilities, visit types,
 * clinician and room resources, weekly availability windows and the approved
 * booking rules.
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced RLS
 * confines it to the active organization. Reference rows are insert-only for the
 * runtime role; corrections are a later configuration story.</p>
 */
@Repository
public class SchedulingRepository {

    private static final RowMapper<ResourceView> RESOURCE_MAPPER = SchedulingRepository::mapResource;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public SchedulingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership using the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest effective membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List role keys currently in force for the subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Read a facility by tenant-local code.
     *
     * @param code tenant-local facility code
     * @return facility identity, code and time zone when visible
     */
    public Optional<FacilityInfo> facility(String code) {
        return jdbc.query("""
                SELECT id::text, code, time_zone FROM public.facility WHERE code = :code
                """, Map.of("code", code), (resultSet, rowNumber) ->
                new FacilityInfo(resultSet.getString("id"), resultSet.getString("code"),
                        resultSet.getString("time_zone"))).stream().findFirst();
    }

    /**
     * Read a visit type by tenant-local code.
     *
     * @param code tenant-local visit type code
     * @return the visit type when visible
     */
    public Optional<VisitTypeView> visitType(String code) {
        return jdbc.query("""
                SELECT code, duration_minutes FROM public.visit_type WHERE code = :code
                """, Map.of("code", code), (resultSet, rowNumber) ->
                new VisitTypeView(resultSet.getString("code"), resultSet.getInt("duration_minutes")))
                .stream().findFirst();
    }

    /**
     * Read one resource of a facility and kind by tenant-local code.
     *
     * @param facilityId facility identifier
     * @param kind       {@code clinician} or {@code room}
     * @param code       tenant-local resource code
     * @return the resource when visible
     */
    public Optional<ResourceView> resource(String facilityId, String kind, String code) {
        return jdbc.query("""
                SELECT r.id::text, f.code AS facility_code, r.kind, r.code, r.display_name
                  FROM public.scheduling_resource r
                  JOIN public.facility f
                    ON f.organization_id = r.organization_id AND f.id = r.facility_id
                 WHERE r.facility_id = CAST(:facilityId AS uuid)
                   AND r.kind = :kind AND r.code = :code
                """, new MapSqlParameterSource().addValue("facilityId", facilityId)
                .addValue("kind", kind).addValue("code", code), RESOURCE_MAPPER).stream().findFirst();
    }

    /**
     * Read one resource of the active tenant by its tenant-local code.
     *
     * @param code tenant-local resource code
     * @return the resource when visible
     */
    public Optional<ResourceView> resourceByCode(String code) {
        return jdbc.query("""
                SELECT r.id::text, f.code AS facility_code, r.kind, r.code, r.display_name
                  FROM public.scheduling_resource r
                  JOIN public.facility f
                    ON f.organization_id = r.organization_id AND f.id = r.facility_id
                 WHERE r.code = :code
                """, Map.of("code", code), RESOURCE_MAPPER).stream().findFirst();
    }

    /**
     * List the resources of one facility ordered by kind and code.
     *
     * @param facilityCode tenant-local facility code
     * @return visible resources
     */
    public List<ResourceView> resources(String facilityCode) {
        return jdbc.query("""
                SELECT r.id::text, f.code AS facility_code, r.kind, r.code, r.display_name
                  FROM public.scheduling_resource r
                  JOIN public.facility f
                    ON f.organization_id = r.organization_id AND f.id = r.facility_id
                 WHERE f.code = :facilityCode
                 ORDER BY r.kind, r.code
                """, Map.of("facilityCode", facilityCode), RESOURCE_MAPPER);
    }

    /**
     * Check whether a slot lies inside at least one approved weekly window of the
     * resource, evaluated in the facility's local time.
     *
     * <p>A resource with no window is never bookable without a recorded override.
     * A window that would span midnight is not allowed by the schema, so a slot
     * whose local start and end fall on different days is outside availability.</p>
     *
     * @param resourceId resource identifier
     * @param timeZone   facility IANA time zone
     * @param startsAt   UTC slot start
     * @param endsAt     UTC slot end
     * @return {@code true} when the whole slot is covered by one window
     */
    public boolean withinAvailability(String resourceId, String timeZone, Instant startsAt, Instant endsAt) {
        Boolean within = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM public.resource_availability a
                     WHERE a.resource_id = CAST(:resourceId AS uuid)
                       AND a.weekday = EXTRACT(ISODOW FROM
                               CAST(:startsAt AS timestamptz) AT TIME ZONE CAST(:timeZone AS text))::int
                       AND a.start_local <= (CAST(:startsAt AS timestamptz)
                               AT TIME ZONE CAST(:timeZone AS text))::time
                       AND a.end_local >= (CAST(:endsAt AS timestamptz)
                               AT TIME ZONE CAST(:timeZone AS text))::time
                       AND date_trunc('day', CAST(:startsAt AS timestamptz)
                               AT TIME ZONE CAST(:timeZone AS text))
                         = date_trunc('day', CAST(:endsAt AS timestamptz)
                               AT TIME ZONE CAST(:timeZone AS text))
                )
                """, new MapSqlParameterSource().addValue("resourceId", resourceId)
                .addValue("timeZone", timeZone).addValue("startsAt", startsAt.toString())
                .addValue("endsAt", endsAt.toString()), Boolean.class);
        return Boolean.TRUE.equals(within);
    }

    /**
     * Read one approved configuration value through the version fold.
     *
     * @param key tenant-local configuration key
     * @return the approved value when one is effective
     */
    public Optional<String> setting(String key) {
        return jdbc.query("""
                SELECT value FROM public.effective_config(:key)
                """, Map.of("key", key), (resultSet, rowNumber) -> resultSet.getString("value"))
                .stream().findFirst();
    }

    /**
     * Insert a visit type.
     *
     * @param code            tenant-local visit type code
     * @param durationMinutes appointment length in minutes
     * @return number of inserted rows
     */
    public int insertVisitType(String code, int durationMinutes) {
        return jdbc.update("""
                INSERT INTO public.visit_type (organization_id, code, duration_minutes)
                VALUES (public.app_org(), :code, :durationMinutes)
                """, new MapSqlParameterSource().addValue("code", code)
                .addValue("durationMinutes", durationMinutes));
    }

    /**
     * Insert a scheduling resource in the given facility.
     *
     * @param id          new opaque resource identifier
     * @param facilityId  facility identifier
     * @param kind        {@code clinician} or {@code room}
     * @param code        tenant-local resource code
     * @param displayName display name
     * @return number of inserted rows
     */
    public int insertResource(String id, String facilityId, String kind, String code,
                              String displayName) {
        return jdbc.update("""
                INSERT INTO public.scheduling_resource
                    (organization_id, id, facility_id, kind, code, display_name)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:facilityId AS uuid), :kind,
                        :code, :displayName)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("facilityId", facilityId)
                .addValue("kind", kind).addValue("code", code).addValue("displayName", displayName));
    }

    /**
     * Insert one weekly availability window.
     *
     * @param resourceId resource identifier
     * @param weekday    ISO-8601 day of week, 1 = Monday
     * @param startLocal local start time, {@code HH:mm}
     * @param endLocal   local end time, {@code HH:mm}
     * @return number of inserted rows
     */
    public int insertAvailability(String resourceId, int weekday, String startLocal, String endLocal) {
        return jdbc.update("""
                INSERT INTO public.resource_availability
                    (organization_id, resource_id, weekday, start_local, end_local)
                VALUES (public.app_org(), CAST(:resourceId AS uuid), :weekday,
                        CAST(:startLocal AS time), CAST(:endLocal AS time))
                """, new MapSqlParameterSource().addValue("resourceId", resourceId)
                .addValue("weekday", weekday).addValue("startLocal", startLocal)
                .addValue("endLocal", endLocal));
    }

    private static ResourceView mapResource(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ResourceView(resultSet.getString("id"), resultSet.getString("facility_code"),
                resultSet.getString("kind"), resultSet.getString("code"),
                resultSet.getString("display_name"));
    }
}
