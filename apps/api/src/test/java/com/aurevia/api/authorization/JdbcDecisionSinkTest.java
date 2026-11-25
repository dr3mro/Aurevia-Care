package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization.Decision;

/**
 * Branch tests for the durable OPS-02 decision ledger sink.
 *
 * <p>The JDBC boundary is mocked so each outcome and the failure path are
 * deterministic; the real PostgreSQL behavior is proved by the integration
 * test. All identifiers are synthetic.</p>
 */
class JdbcDecisionSinkTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);

    private NamedParameterJdbcTemplate jdbc;
    private JdbcDecisionSink sink;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        sink = new JdbcDecisionSink(jdbc, transactions);
    }

    private static Decision decision(boolean allowed, String reasonCode) {
        return new Decision("createFacility", "FACILITY_WRITE", ORG, "admin-1", "corr-1",
                allowed, reasonCode, List.of("tenant_admin"), CLOCK.instant());
    }

    private ArgumentCaptor<MapSqlParameterSource> captureInsert() {
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(1)).update(anyString(), params.capture());
        return params;
    }

    @Test
    void allowDecisionWritesOnlyBoundedLedgerColumns() {
        sink.record(decision(true, "ALLOWED"));

        MapSqlParameterSource values = captureInsert().getValue();
        assertEquals(Set.of("organizationId", "commandId", "subject", "action", "resourceType",
                "decision", "reasonCode", "correlationId", "decidedAt"), values.getValues().keySet());
        assertEquals(ORG, values.getValue("organizationId"));
        assertEquals("createFacility", values.getValue("commandId"));
        assertEquals("admin-1", values.getValue("subject"));
        assertEquals("FACILITY_WRITE", values.getValue("action"));
        assertEquals("command", values.getValue("resourceType"));
        assertEquals("allow", values.getValue("decision"));
        assertEquals("ALLOWED", values.getValue("reasonCode"));
        assertEquals("corr-1", values.getValue("correlationId"));
        assertEquals(Timestamp.from(CLOCK.instant()), values.getValue("decidedAt"));
    }

    @Test
    void denyDecisionWritesTheDenyOutcomeAndItsReasonCode() {
        sink.record(decision(false, "COMMAND_FORBIDDEN"));

        MapSqlParameterSource values = captureInsert().getValue();
        assertEquals("deny", values.getValue("decision"));
        assertEquals("COMMAND_FORBIDDEN", values.getValue("reasonCode"));
        assertEquals("FACILITY_WRITE", values.getValue("action"));
    }

    @Test
    void blankOrganizationIsRecordedAsTenantlessEvidence() {
        sink.record(new Decision("createFacility", "FACILITY_WRITE", "", "", "corr-1",
                false, "UNAUTHENTICATED", List.of(), CLOCK.instant()));

        assertNull(captureInsert().getValue().getValue("organizationId"));
    }

    @Test
    void absentOrganizationIsRecordedAsTenantlessEvidence() {
        sink.record(new Decision("", "", null, "", "", false, "UNAUTHENTICATED",
                List.of(), CLOCK.instant()));

        assertNull(captureInsert().getValue().getValue("organizationId"));
    }

    @Test
    void ledgerFailureFailsClosedWithAuditUnavailable() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new DataRetrievalFailureException("authorization ledger unavailable"));

        ApiException failure = assertThrows(ApiException.class, () -> sink.record(decision(true, "ALLOWED")));
        assertEquals(500, failure.status());
        assertEquals("AUDIT_UNAVAILABLE", failure.code());
        assertTrue(failure.getMessage().contains("authorization decision ledger"));
    }
}
