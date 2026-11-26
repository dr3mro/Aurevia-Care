package com.aurevia.api.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.contract.IdempotencyLedger.Decision;
import com.aurevia.api.contract.IdempotencyLedger.Scope;
import com.aurevia.tenant.TenantContext;

/**
 * Branch coverage for the durable idempotency store at a mocked JDBC boundary.
 *
 * <p>Every branch below is driven deterministically: an empty result, a live
 * row with a matching and a differing digest, and both sides of the expired-row
 * delete. Tenant context is bound before each call so the production store can
 * read the active organization. All identifiers and bodies are synthetic, and
 * no token or clinical payload is used.</p>
 */
class JdbcIdempotencyLedgerTest {

    private static final String ORG = "11111111-2222-3333-4444-555555555555";
    private static final String OUTCOME = "{\"marker\":\"idem-a\"}";
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcIdempotencyLedger store;
    private Scope scope;
    private IdempotencyKey key;
    private RequestDigest digest;

    /** Build the store with a fixed clock and a pass-through tenant transaction. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        store = new JdbcIdempotencyLedger(jdbc, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, "actor-1", TenantContext.PURPOSE, "corr-1"));
        scope = new Scope(ORG, "actor-1", "POST /api/v1/idempotency/probe");
        key = new IdempotencyKey("retry-key-1");
        digest = RequestDigest.of(ORG, "actor-1", scope.command(), OUTCOME);
    }

    /** Clear the thread-local context after each case. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void rows(List<JdbcIdempotencyLedger.Row> rows) {
        doReturn(rows).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void anUnknownKeyProceedsWhenTheQueryReturnsNoRow() {
        rows(List.of());

        ResponseResolution resolution = store.evaluate(scope, key, digest);

        assertEquals(Decision.PROCEED, resolution.decision());
        assertNull(resolution.response());
    }

    @Test
    void theCommandIsBoundToTheKeyOnEveryLookup() {
        rows(List.of());

        store.evaluate(scope, key, digest);

        ArgumentCaptor<SqlParameterSource> bound = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(anyString(), bound.capture(), any(RowMapper.class));
        assertEquals(scope.command(), bound.getValue().getValue("command"));
    }

    @Test
    void aLiveRowWithTheSameDigestReplaysItsStoredResponse() {
        rows(List.of(new JdbcIdempotencyLedger.Row(digest.hex(), 201, OUTCOME)));

        ResponseResolution resolution = store.evaluate(scope, key, digest);

        assertEquals(Decision.REPLAY, resolution.decision());
        assertNotNull(resolution.response());
        assertEquals(201, resolution.response().statusCode());
        assertEquals(OUTCOME, resolution.response().body());
    }

    @Test
    void aLiveRowWithADifferentDigestConflicts() {
        rows(List.of(new JdbcIdempotencyLedger.Row("b".repeat(64), 201, OUTCOME)));

        ResponseResolution resolution = store.evaluate(scope, key, digest);

        assertEquals(Decision.CONFLICT, resolution.decision());
        assertNull(resolution.response());
    }

    @Test
    void storingDeletesAnExpiredRowBeforeInsertingAndBindsTheExpiry() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1, 1);

        store.store(scope, key, digest, new StoredResponse(201, OUTCOME));

        ArgumentCaptor<SqlParameterSource> bound = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), bound.capture());
        SqlParameterSource insert = bound.getAllValues().get(1);
        assertEquals(scope.command(), insert.getValue("command"));
        assertEquals(scope.command(), bound.getAllValues().get(0).getValue("command"));
        assertEquals(digest.hex(), insert.getValue("requestHash"));
        assertEquals(OUTCOME, insert.getValue("body"));
        assertEquals(201, insert.getValue("statusCode"));
        assertTrue(((Timestamp) insert.getValue("expiresAt")).after(
                (Timestamp) insert.getValue("createdAt")));
    }

    @Test
    void anInsertThatLosesTheRaceStillReturnsWithoutOverwritingTheWinner() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0, 0);

        store.store(scope, key, digest, new StoredResponse(201, OUTCOME));

        verify(jdbc, times(2)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void aRowWithAnEmptyBodyIsReplayedVerbatim() {
        rows(List.of(new JdbcIdempotencyLedger.Row(digest.hex(), 200, "")));

        ResponseResolution resolution = store.evaluate(scope, key, digest);

        assertEquals("", resolution.response().body());
    }
}
