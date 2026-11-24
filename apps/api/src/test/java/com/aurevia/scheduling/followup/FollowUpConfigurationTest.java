package com.aurevia.scheduling.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.api.ApiException;
import com.aurevia.scheduling.FacilityInfo;
import com.aurevia.scheduling.SchedulingRepository;

/** CLN-06 configuration adapter: tenant-approved fold, facility zone and prior follow-up. */
class FollowUpConfigurationTest {

    private SchedulingRepository scheduling;
    private NamedParameterJdbcTemplate jdbc;
    private FollowUpConfiguration config;

    @BeforeEach
    void setUp() {
        scheduling = mock(SchedulingRepository.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        config = new FollowUpConfiguration(scheduling, jdbc);
    }

    @Test
    void settingDelegatesToTheTenantConfigurationFold() {
        when(scheduling.setting("followup.interval.followup")).thenReturn(Optional.of("14"));
        assertEquals(Optional.of("14"), config.setting("followup.interval.followup"));
        verify(scheduling).setting("followup.interval.followup");
    }

    @Test
    void facilityZoneResolvesTheAuthoritativeZone() {
        when(scheduling.facility("CLINIC")).thenReturn(
                Optional.of(new FacilityInfo("id", "CLINIC", "America/New_York")));
        assertEquals(ZoneId.of("America/New_York"), config.facilityZone("CLINIC"));
    }

    @Test
    void facilityZoneFailsClosedForAnUnknownFacility() {
        when(scheduling.facility("CLINIC")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> config.facilityZone("CLINIC")).status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void priorFollowUpIsTheNearestVisibleAppointmentOnTheFacilityCivilDate() throws Exception {
        Instant encounter = Instant.parse("2026-03-08T04:30:00Z");
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn("appointment-7");
        when(row.getObject("local_date", LocalDate.class)).thenReturn(LocalDate.of(2026, 3, 7));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<PriorFollowUp> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });
        assertEquals(Optional.of(new PriorFollowUp("appointment-7", LocalDate.of(2026, 3, 7))),
                config.applicablePriorFollowUp("patient", "FOLLOW", encounter));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noPriorFollowUpReturnsEmpty() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(),
                config.applicablePriorFollowUp("patient", "FOLLOW", Instant.parse("2026-03-08T04:30:00Z")));
    }
}
