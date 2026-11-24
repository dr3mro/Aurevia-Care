package com.aurevia.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.events.DurableEventContract.AuditAction;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.OutboxEvent;

/**
 * Deterministic branch coverage for the JDBC delivery store.
 *
 * <p>The empty-result side of the idempotent insert and the null aggregate
 * version cannot be forced reliably through a real database race, so the
 * boundary is mocked here while the normal paths are proven by
 * {@code JdbcDeliveryStoreIntegrationTest}. All records are synthetic.</p>
 */
class JdbcDeliveryStoreBranchTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String EVENT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String DIGEST = "a".repeat(64);

    private NamedParameterJdbcTemplate jdbc;
    private JdbcDeliveryStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        store = new JdbcDeliveryStore(jdbc, transactions);
    }

    private static OutboxEvent event() {
        return new OutboxEvent(EVENT, ORG, AGGREGATE, 1, "lab-partner", "observation.result",
                "partner-lab", DIGEST, "key-1", DeliveryState.PENDING, 0, null,
                Instant.parse("2026-11-20T12:00:00Z"), null);
    }

    private static DeliveryAuditEvent audit() {
        return new DeliveryAuditEvent("ffffffff-ffff-ffff-ffff-ffffffffffff", ORG, "worker-a", "DELIVERY",
                AuditAction.PUBLISH, EVENT, "corr-1", Outcome.SUCCESS,
                Instant.parse("2026-11-20T12:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void idempotentInsertWithNoNewRowReturnsTheOriginalEvent() {
        OutboxEvent original = event();
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<OutboxEvent>>any()))
                .thenReturn(List.of(), List.of(original));

        assertEquals(original, store.publishAtomically(ORG, original, audit()));
    }

    @Test
    void maxVersionReturnsEmptyWhenNoReceiptWasProcessed() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(null);
        assertTrue(store.maxVersion(ORG, "partner-lab", "lab-partner", "observation.result", AGGREGATE)
                .isEmpty());
    }

    @Test
    void maxVersionReturnsTheHighestProcessedVersion() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(7);
        assertEquals(7, store.maxVersion(ORG, "partner-lab", "lab-partner", "observation.result", AGGREGATE)
                .getAsInt());
    }

    @Test
    void inactiveMembershipIsReportedAsDenied() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.FALSE);
        assertFalse(store.hasActiveMembership(ORG, "inactive"));
    }

    @Test
    void activeMembershipIsReportedAsAllowed() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE);
        assertTrue(store.hasActiveMembership(ORG, "worker-a"));
    }
}
