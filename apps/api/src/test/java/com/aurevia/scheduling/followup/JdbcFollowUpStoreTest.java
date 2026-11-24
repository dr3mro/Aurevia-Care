package com.aurevia.scheduling.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** CLN-06 persistence adapter: tenant keyed SQL, current version and override mapping. */
class JdbcFollowUpStoreTest {

    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant ENCOUNTER = Instant.parse("2026-03-06T15:00:00Z");
    private static final LocalDate SUGGESTED = LocalDate.of(2026, 3, 20);

    private NamedParameterJdbcTemplate jdbc;
    private JdbcFollowUpStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        store = new JdbcFollowUpStore(jdbc);
    }

    private static FollowUpSuggestion suggestion(FollowUpOverride override) {
        return new FollowUpSuggestion(ID, ORG, "patient", "CLINIC", "FOLLOW", ENCOUNTER,
                "America/New_York", SUGGESTED, 14, "CONFIGURED_INTERVAL", "rule", override,
                false, null, 1);
    }

    private static ResultSet suggestionRow(boolean withOverride) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(ID);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("patient_id")).thenReturn("patient");
        when(row.getString("facility_code")).thenReturn("CLINIC");
        when(row.getString("visit_type")).thenReturn("FOLLOW");
        when(row.getObject("encounter_at", OffsetDateTime.class))
                .thenReturn(ENCOUNTER.atOffset(ZoneOffset.UTC));
        when(row.getString("time_zone")).thenReturn("America/New_York");
        when(row.getObject("suggested_date", LocalDate.class)).thenReturn(SUGGESTED);
        when(row.getObject("interval_days", Integer.class)).thenReturn(14);
        when(row.getString("basis")).thenReturn("CONFIGURED_INTERVAL");
        when(row.getString("rule_explanation")).thenReturn("rule");
        when(row.getBoolean("confirmed")).thenReturn(false);
        when(row.getString("booking_id")).thenReturn(null);
        when(row.getInt("version")).thenReturn(1);
        if (withOverride) {
            when(row.getString("override_actor")).thenReturn("clinician-1");
            when(row.getString("override_reason")).thenReturn("clinician directed");
            when(row.getObject("override_recorded_at", OffsetDateTime.class))
                    .thenReturn(Instant.parse("2026-03-07T10:00:00Z").atOffset(ZoneOffset.UTC));
            when(row.getObject("override_original_date", LocalDate.class)).thenReturn(SUGGESTED);
            when(row.getObject("override_replacement_date", LocalDate.class))
                    .thenReturn(SUGGESTED.plusDays(5));
        }
        return row;
    }

    @Test
    void mapSuggestionWithoutOverrideKeepsTheOverrideAbsent() throws Exception {
        FollowUpSuggestion mapped = JdbcFollowUpStore.mapSuggestion(suggestionRow(false), 0);
        assertEquals(suggestion(null), mapped);
        assertNull(mapped.override());
    }

    @Test
    void mapSuggestionWithOverrideRestoresFullProvenance() throws Exception {
        FollowUpOverride override = new FollowUpOverride("clinician-1", "clinician directed",
                Instant.parse("2026-03-07T10:00:00Z"), SUGGESTED, SUGGESTED.plusDays(5));
        FollowUpSuggestion mapped = JdbcFollowUpStore.mapSuggestion(suggestionRow(true), 0);
        assertEquals(suggestion(override), mapped);
        assertEquals("clinician-1", mapped.override().actor());
        assertEquals(SUGGESTED, mapped.override().originalDate());
    }

    @Test
    void mapTaskRestoresTheOpenTask() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(ID);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("follow_up_id")).thenReturn("follow-up-1");
        when(row.getString("owner")).thenReturn("nurse");
        when(row.getObject("due_date", LocalDate.class)).thenReturn(SUGGESTED);
        when(row.getString("instruction")).thenReturn("call");
        when(row.getString("status")).thenReturn(CareTeamTask.OPEN);
        when(row.getString("outcome")).thenReturn(null);
        when(row.getString("created_by")).thenReturn("clinician-1");
        when(row.getObject("created_at", OffsetDateTime.class))
                .thenReturn(ENCOUNTER.atOffset(ZoneOffset.UTC));
        CareTeamTask expected = new CareTeamTask(ID, ORG, "follow-up-1", "nurse", SUGGESTED,
                "call", CareTeamTask.OPEN, null, "clinician-1", ENCOUNTER);
        assertEquals(expected, JdbcFollowUpStore.mapTask(row, 0));
    }

    @Test
    void saveSuggestionUpsertsTheCurrentVersion() {
        store.saveSuggestion(suggestion(null));
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void saveSuggestionPersistsOverrideProvenance() {
        FollowUpOverride override = new FollowUpOverride("clinician-1", "clinician directed",
                Instant.parse("2026-03-07T10:00:00Z"), SUGGESTED, SUGGESTED.plusDays(5));
        store.saveSuggestion(suggestion(override));
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findSuggestionReturnsTheMappedRowInsideTheTenant() throws Exception {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<FollowUpSuggestion> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(suggestionRow(false), 0));
                });
        assertEquals(Optional.of(suggestion(null)), store.findSuggestion(ORG, ID));
    }

    @Test
    void findSuggestionReturnsEmptyForAnUnknownId() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(), store.findSuggestion(ORG, ID));
    }

    @Test
    void saveTaskUpsertsTheCurrentStatus() {
        store.saveTask(new CareTeamTask(ID, ORG, "follow-up-1", "nurse", SUGGESTED, "call",
                CareTeamTask.OPEN, null, "clinician-1", ENCOUNTER));
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findTaskReturnsTheMappedRowInsideTheTenant() {
        CareTeamTask task = new CareTeamTask(ID, ORG, "follow-up-1", "nurse", SUGGESTED, "call",
                CareTeamTask.OPEN, null, "clinician-1", ENCOUNTER);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(task));
        assertEquals(Optional.of(task), store.findTask(ORG, ID));
    }

    @Test
    void findTaskReturnsEmptyForAnUnknownId() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(), store.findTask(ORG, ID));
    }

    @Test
    void tasksForReturnsAllVisibleTasks() {
        CareTeamTask task = new CareTeamTask(ID, ORG, "follow-up-1", "nurse", SUGGESTED, "call",
                CareTeamTask.OPEN, null, "clinician-1", ENCOUNTER);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(task));
        List<CareTeamTask> result = store.tasksFor(ORG, "follow-up-1");
        assertEquals(1, result.size());
        assertFalse(result.isEmpty());
        assertTrue(result.contains(task));
    }
}
