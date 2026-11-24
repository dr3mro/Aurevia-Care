package com.aurevia.authz.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Deterministic mapping of the append-only history ledger into the read model. */
class JdbcRoleHistoryStoreTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcRoleHistoryStore store = new JdbcRoleHistoryStore(jdbc);

    @Test
    void mapsEveryChangeColumn() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        when(row.getString("subject")).thenReturn("employee-a");
        when(row.getString("role_key")).thenReturn("reception");
        when(row.getString("change_kind")).thenReturn("GRANT");
        when(row.getString("actor")).thenReturn("admin-a");
        when(row.getString("recorded_at")).thenReturn("2026-11-21 10:00:00+00");
        when(row.getString("effective_from")).thenReturn("2026-11-21 09:00:00+00");
        when(row.getString("effective_to")).thenReturn(null);
        when(jdbc.query(anyString(), any(java.util.Map.class),
                ArgumentMatchers.<RowMapper<RoleHistory.RoleChange>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<RoleHistory.RoleChange> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        List<RoleHistory.RoleChange> changes = store.findBySubject("employee-a");

        assertEquals(1, changes.size());
        assertEquals(new RoleHistory.RoleChange("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "employee-a",
                "reception", "GRANT", "admin-a", "2026-11-21 10:00:00+00",
                "2026-11-21 09:00:00+00", null), changes.get(0));
    }

    @Test
    void emptyLedgerReturnsNoChanges() {
        when(jdbc.query(anyString(), any(java.util.Map.class),
                ArgumentMatchers.<RowMapper<RoleHistory.RoleChange>>any()))
                .thenReturn(List.of());
        assertTrue(store.findBySubject("unknown-a").isEmpty());
    }
}
