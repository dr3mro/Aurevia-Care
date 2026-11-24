package com.aurevia.tenant.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.tenant.TenantContext;
import com.aurevia.tenant.export.ExportModel.ExportJob;

/** OPS-07: the durable job store handles both outcomes of each guarded write. */
class JdbcExportStoreTest {

    private static final ExportJob JOB = new ExportJob("job-1", "COMPLETE", "obj-1", 1,
            "a".repeat(64), "2026-11-21T10:00:00Z", "2026-11-22T10:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcExportStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        store = new JdbcExportStore(jdbc);
        TenantContext.set(new TenantContext("org-a", "admin", "EXPORT", "corr-1"));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void saveReportsInsertedRow() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertTrue(store.save(JOB));
    }

    @Test
    void saveReportsMissingRow() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertFalse(store.save(JOB));
    }

    @Test
    void findReturnsFirstRowWhenPresent() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ExportJob>>any())).thenReturn(List.of(JOB));
        assertEquals(JOB, store.find("job-1").orElseThrow());
    }

    @Test
    void findReturnsEmptyWhenNoRowIsVisible() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ExportJob>>any())).thenReturn(List.of());
        assertTrue(store.find("other-tenant-job").isEmpty());
    }

    @Test
    void markExpiredReportsUpdatedRow() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertTrue(store.markExpired("job-1"));
    }

    @Test
    void markExpiredReportsMissingRow() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertFalse(store.markExpired("missing"));
    }
}
