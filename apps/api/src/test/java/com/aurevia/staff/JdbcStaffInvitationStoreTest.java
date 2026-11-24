package com.aurevia.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * BUILD-01 / IAM-02 deterministic branch coverage for the invitation SQL boundary.
 * The mocked boundary covers the empty-result and null-function sides that a real
 * PostgreSQL race cannot force; normal paths are proven by the integration test.
 */
class JdbcStaffInvitationStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SUBJECT = "new-staff-a";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TOKEN = "synthetic-token-abcdefghijklmnop";

    private NamedParameterJdbcTemplate jdbc;
    private JdbcStaffInvitationStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        store = new JdbcStaffInvitationStore(jdbc);
    }

    private static ResultSet row() throws Exception {
        Array roles = mock(Array.class);
        when(roles.getArray()).thenReturn(new String[] {"reception", "tenant_admin"});
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getArray("roles")).thenReturn(roles);
        when(resultSet.getString("id")).thenReturn(ID);
        when(resultSet.getString("subject")).thenReturn(SUBJECT);
        when(resultSet.getString("display_name")).thenReturn("New Staff");
        when(resultSet.getString("facility_id")).thenReturn(FACILITY);
        when(resultSet.getString("expires_at")).thenReturn("2026-11-21T13:00:00Z");
        when(resultSet.getString("status")).thenReturn("pending");
        when(resultSet.getString("invited_by")).thenReturn("admin-a");
        return resultSet;
    }

    @SuppressWarnings("unchecked")
    private void queryReturns(List<ResultSet> rows) throws Exception {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<StaffInvitation>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<StaffInvitation> mapper = invocation.getArgument(2);
                    return rows.stream().map(resultSet -> {
                        try {
                            return mapper.mapRow(resultSet, 0);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }).toList();
                });
    }

    @Test
    void insertPassesTheRawTokenAndReturnsTheRowCount() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertEquals(1, store.insert(ORG, ID, SUBJECT, "New Staff", FACILITY,
                List.of("reception", "tenant_admin"), "admin-a", TOKEN,
                Instant.parse("2026-11-21T13:00:00Z")));
    }

    @Test
    void findPendingMapsRowsAndHandlesAnEmptyPage() throws Exception {
        queryReturns(List.of(row()));
        List<StaffInvitation> pending = store.findPending();
        assertEquals(1, pending.size());
        assertEquals(ID, pending.get(0).id());
        assertEquals(List.of("reception", "tenant_admin"), pending.get(0).roles());
        assertEquals("pending", pending.get(0).status());

        queryReturns(List.of());
        assertTrue(store.findPending().isEmpty());
    }

    @Test
    void revokeMapsTheReturnedRowAndHandlesAnUnusableInvitation() throws Exception {
        queryReturns(List.of(row()));
        Optional<StaffInvitation> revoked = store.revoke(ID);
        assertTrue(revoked.isPresent());
        assertEquals(ID, revoked.get().id());

        queryReturns(List.of());
        assertTrue(store.revoke(ID).isEmpty());
    }

    @Test
    void acceptReturnsTheSubjectWhenTheDatabaseFunctionSucceeds() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(SUBJECT);
        Optional<String> accepted = store.accept(TOKEN);
        assertTrue(accepted.isPresent());
        assertEquals(SUBJECT, accepted.get());
    }

    @Test
    void acceptReturnsEmptyWhenTheTokenIsUnusable() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(null);
        Optional<String> accepted = store.accept(TOKEN);
        assertFalse(accepted.isPresent());
    }
}
