package com.aurevia.scheduling.followup;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.aurevia.api.ApiException;
import com.aurevia.scheduling.SchedulingRepository;

/**
 * Tenant facts used to compute a follow-up date.
 *
 * <p>Intervals come only from the tenant's approved configuration fold, the zone
 * from the facility record, and an applicable prior follow-up from the tenant's
 * non-terminal appointments. Every query is confined to the active organization
 * by forced row-level security; no organization is accepted from the caller.</p>
 */
@Component
public class FollowUpConfiguration implements FollowUpConfig {

    private final SchedulingRepository scheduling;
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the adapter.
     *
     * @param scheduling tenant scheduling reference data and configuration fold
     * @param jdbc       shared named-parameter JDBC template bound to the active tenant
     */
    public FollowUpConfiguration(SchedulingRepository scheduling, NamedParameterJdbcTemplate jdbc) {
        this.scheduling = scheduling;
        this.jdbc = jdbc;
    }
    /**
     * Read one approved configuration value through the version fold.
     *
     * @param key tenant-local configuration key
     * @return the approved value when one is effective
     */
    @Override
    public Optional<String> setting(String key) {
        return scheduling.setting(key);
    }
    /**
     * Resolve the facility's authoritative IANA time zone.
     *
     * @param facilityCode tenant-local facility code
     * @return the facility time zone
     */
    @Override
    public ZoneId facilityZone(String facilityCode) {
        return ZoneId.of(scheduling.facility(facilityCode)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"))
                .timeZone());
    }

    @Override
    public Optional<PriorFollowUp> applicablePriorFollowUp(String patientId, String visitType,
                                                            Instant encounterAt) {
        return jdbc.query("""
                SELECT a.id::text AS id, (a.starts_at AT TIME ZONE f.time_zone)::date AS local_date
                  FROM public.appointment a
                  JOIN public.facility f
                    ON f.organization_id = a.organization_id AND f.id = a.facility_id
                 WHERE a.patient_id = CAST(:patientId AS uuid)
                   AND a.visit_type = :visitType
                   AND a.state IN ('booked', 'checked_in', 'in_progress', 'completed')
                   AND a.starts_at < CAST(:encounterAt AS timestamptz)
                 ORDER BY a.starts_at DESC
                 LIMIT 1
                """, new MapSqlParameterSource().addValue("patientId", patientId)
                .addValue("visitType", visitType).addValue("encounterAt", encounterAt.toString()),
                (ResultSet resultSet, int rowNumber) -> new PriorFollowUp(
                        resultSet.getString("id"),
                        resultSet.getObject("local_date", LocalDate.class)))
                .stream().findFirst();
    }
}
