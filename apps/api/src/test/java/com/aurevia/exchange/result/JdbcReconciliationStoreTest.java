package com.aurevia.exchange.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.exchange.result.ReconciliationModel.ReconciliationAction;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;

/**
 * Both outcomes of the durable reconciliation-resolution read and insert, without a database.
 *
 * <p>The row mapper is driven with a synthetic result set so its mapping lines are exercised by the
 * unit tier; an empty {@code RETURNING} result is the deterministic conflict side of the unique key.</p>
 */
class JdbcReconciliationStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ENTRY_ID = "cccccccc-cccc-cccc-cccc-ccccccccccc1";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private ReconciliationStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        store = new JdbcReconciliationStore(jdbc);
    }

    private static Resolution decision(String owner) {
        return new Resolution(ENTRY_ID, ReconciliationAction.MATCH, "identifiers verified", owner, AT);
    }

    private void answerWithARow(boolean withOwner) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Resolution>>any()))
                .thenAnswer(invocation -> mapped(invocation, withOwner));
    }

    private static List<Resolution> mapped(InvocationOnMock invocation, boolean withOwner) throws Exception {
        RowMapper<Resolution> mapper = invocation.getArgument(2);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("entry_id")).thenReturn(ENTRY_ID);
        when(resultSet.getString("action")).thenReturn("match");
        when(resultSet.getString("reason")).thenReturn("identifiers verified");
        when(resultSet.getString("assigned_owner")).thenReturn(withOwner ? "owner-b" : null);
        when(resultSet.getObject("resolved_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-11-21T10:00:00Z"));
        return List.of(mapper.mapRow(resultSet, 0));
    }

    @Test
    void insertReturnsTheMappedResolutionWhenReturningYieldsARow() {
        answerWithARow(false);

        Resolution inserted = store.insert(ORG, decision(null));

        assertEquals(decision(null), inserted);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), ArgumentMatchers.<RowMapper<Resolution>>any());
        assertTrue(sql.getValue().contains("ON CONFLICT"), "insert must be idempotent under concurrency");
        assertEquals(ORG, parameters.getValue().getValue("organizationId"));
    }

    @Test
    void insertReturnsNullWhenOnConflictDoesNothing() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Resolution>>any())).thenReturn(List.of());

        assertNull(store.insert(ORG, decision(null)));
    }

    @Test
    void findReturnsTheMappedResolutionWhenVisible() {
        answerWithARow(true);

        Resolution found = store.find(ORG, ENTRY_ID);

        assertEquals(ENTRY_ID, found.entryId());
        assertEquals(ReconciliationAction.MATCH, found.action());
        assertEquals("identifiers verified", found.reason());
        assertEquals("owner-b", found.assignedOwner());
        assertEquals(AT, found.resolvedAt());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), ArgumentMatchers.<RowMapper<Resolution>>any());
        assertTrue(sql.getValue().contains("organization_id"), "read must bind the active tenant");
        assertEquals(ORG, parameters.getValue().getValue("organizationId"));
        assertEquals(ENTRY_ID, parameters.getValue().getValue("entryId"));
    }

    @Test
    void findReturnsNullWhenNoResolutionIsVisible() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Resolution>>any())).thenReturn(List.of());

        assertNull(store.find(ORG, ENTRY_ID));
    }
}
