package com.aurevia.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Unit gate for the JDBC active-tenant source: it maps the authoritative
 * function's rows and never consults request-scoped tenant state.
 */
class JdbcActiveTenantSourceTest {

    private static final UUID ORG_A = UUID.fromString("61111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("62222222-2222-2222-2222-222222222222");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcActiveTenantSource source;

    /** Create a fresh mocked JDBC boundary for each test. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        source = new JdbcActiveTenantSource(jdbc);
    }

    /**
     * The function's rows become UUIDs and the query names only the narrow
     * authoritative function, even when a request tenant is bound on the thread.
     */
    @Test
    void functionRowsMapToUuidListWithoutReadingTenantContext() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of(ORG_A, ORG_B));
        TenantContext.set(new TenantContext(ORG_B.toString(), "admin", "SCHEDULER", "corr-1"));
        try {
            List<UUID> ids = source.activeOrganizationIds();
            assertEquals(List.of(ORG_A, ORG_B), ids);
            assertThrows(UnsupportedOperationException.class, () -> ids.add(ORG_B),
                    "the returned list must be immutable");
        } finally {
            TenantContext.clear();
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).query(sql.capture(), ArgumentMatchers.<RowMapper<UUID>>any());
        assertTrue(sql.getValue().contains("public.active_organization_ids()"),
                "the only read must be the narrow definer function");
        assertFalse(sql.getValue().contains("app.current_org"),
                "the enumerator must not depend on the tenant GUC");
        assertFalse(sql.getValue().contains("FROM public.organization"),
                "the enumerator must not read the tenant table directly");
    }

    /** No active organizations produces an empty result instead of an error. */
    @Test
    void emptyFunctionResultYieldsEmptyList() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of());
        assertTrue(source.activeOrganizationIds().isEmpty());
    }

    /** The row mapper reads the function's single organization_id column. */
    @Test
    void rowMapperReadsTheOrganizationIdColumn() throws Exception {
        RowMapper<UUID> mapper = captureMapper();
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("organization_id")).thenReturn(ORG_A.toString());
        assertEquals(ORG_A, mapper.mapRow(resultSet, 0));
    }

    /** A malformed identifier fails closed instead of becoming a partial tenant id. */
    @Test
    void malformedOrganizationIdFailsClosed() throws Exception {
        RowMapper<UUID> mapper = captureMapper();
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("organization_id")).thenReturn("not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> mapper.mapRow(resultSet, 0));
    }

    /** Capture the mapper the source passes to the JDBC boundary. */
    private RowMapper<UUID> captureMapper() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(ORG_A));
        source.activeOrganizationIds();
        ArgumentCaptor<RowMapper<UUID>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc).query(anyString(), mapper.capture());
        return mapper.getValue();
    }
}
