package com.aurevia.exchange.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ReconciliationReason;
import com.aurevia.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;

/**
 * Both outcomes of the reconciliation read, without a database.
 *
 * <p>Only SELECT is granted on the reconciliation table, so the operator
 * decision is an append-only domain projection rather than a status UPDATE.</p>
 */
class ResultRepositoryReconciliationTest {

    private static final String ENTRY_ID = "cccccccc-cccc-cccc-cccc-ccccccccccc1";

    private NamedParameterJdbcTemplate jdbc;
    private ResultRepository repository;
    private ReconciliationEntry entry;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        repository = new ResultRepository(jdbc);
        entry = new ReconciliationEntry(ENTRY_ID,
                new ResultIdentifiers("33333333-3333-3333-3333-333333333333", "src", "acc", "dest", "order-1"),
                ReconciliationReason.CONFLICT, List.of("patientId"), "identifier mismatch",
                ReconciliationStatus.OPEN, Instant.parse("2026-11-21T10:00:00Z"));
    }

    @Test
    void findReconciliationReturnsTheEntryWhenOneIsVisible() {
        when(jdbc.query(anyString(), ArgumentMatchers.<Map<String, Object>>any(),
                ArgumentMatchers.<RowMapper<ReconciliationEntry>>any())).thenReturn(List.of(entry));
        assertEquals(Optional.of(entry), repository.findReconciliation(ENTRY_ID));
    }

    @Test
    void findReconciliationIsEmptyWhenTheEntryIsNotVisible() {
        when(jdbc.query(anyString(), ArgumentMatchers.<Map<String, Object>>any(),
                ArgumentMatchers.<RowMapper<ReconciliationEntry>>any())).thenReturn(List.of());
        assertEquals(Optional.empty(), repository.findReconciliation(ENTRY_ID));
    }
}
