package com.aurevia.exchange.result;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;

/**
 * Both outcomes of the idempotent order insert, without a database.
 *
 * <p>The service checks for an existing order first, so an insert that returns no
 * row happens only when a concurrent registration wins the race. A mocked empty
 * result makes that outcome deterministic.</p>
 */
class ResultRepositoryInsertOrderTest {

    private NamedParameterJdbcTemplate jdbc;
    private ResultRepository repository;
    private ExpectedOrder order;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        repository = new ResultRepository(jdbc);
        order = new ExpectedOrder("order-1",
                new ResultIdentifiers("33333333-3333-3333-3333-333333333333", "source-1",
                        "accession-1", "destination-1", "order-1"),
                "owner-a", "queue-a");
    }

    @Test
    void insertOrderReturnsTheOrderWhenARowWasInserted() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("new-id"));
        assertSame(order, repository.insertOrder(order));
    }

    @Test
    void insertOrderReturnsNullWhenAConcurrentRegistrationOwnsTheReference() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of());
        assertNull(repository.insertOrder(order));
    }
}
