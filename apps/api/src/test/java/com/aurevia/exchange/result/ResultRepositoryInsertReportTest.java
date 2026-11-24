package com.aurevia.exchange.result;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.ResultReport;

/**
 * Both outcomes of the idempotent report insert, without a database.
 *
 * <p>The unique tenant-scoped revision key makes a replayed report insert no
 * row, so the repository must answer {@code false} for it and {@code true} for
 * a first insert. The first revision has no predecessor and a correction names
 * one; both shapes are exercised.</p>
 */
class ResultRepositoryInsertReportTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private ResultRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        repository = new ResultRepository(jdbc);
    }

    private static ResultReport report(Integer supersedesVersion, String previousId) {
        return new ResultReport("44444444-4444-4444-4444-444444444444", 1, "order-1",
                new ResultIdentifiers("33333333-3333-3333-3333-333333333333", "source-1",
                        "accession-1", "destination-1", "order-1"),
                ResultKind.FINAL, supersedesVersion, previousId, "owner-a", "queue-a", AT, AT,
                "checksum-1");
    }

    @Test
    void insertReportReturnsTrueForAFirstRevisionThatWasInserted() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("new-id"));
        assertTrue(repository.insertReport(report(null, null)));
    }

    @Test
    void insertReportReturnsFalseForAReplayedCorrectionThatInsertedNothing() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of());
        assertFalse(repository.insertReport(report(1, "55555555-5555-5555-5555-555555555555")));
    }
}
