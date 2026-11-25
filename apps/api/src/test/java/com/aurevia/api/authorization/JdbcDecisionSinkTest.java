package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.tenant.TenantContext;

/**
 * Deterministic branch and boundary tests for the durable decision ledger.
 *
 * <p>The JDBC boundary is mocked so a failed insert and a zero-row insert are
 * reproducible without a database race. Every value is synthetic.</p>
 */
class JdbcDecisionSinkTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);

    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcDecisionSink sink;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        transactions = new TransactionTemplate(manager);
        sink = new JdbcDecisionSink(jdbc, transactions);
        TenantContext.set(new TenantContext(ORG, "actor-a", "AUTH", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Decision decision(boolean allowed, String reasonCode, String subject,
                                     String commandId, String correlationId) {
        return new Decision(commandId, "FACILITY_WRITE", ORG, subject, correlationId,
                allowed, reasonCode, java.util.List.of("tenant_admin"), CLOCK.instant());
    }

    private SqlParameterSource captured() {
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void allowRowIsWrittenWithOnlyAuditableColumns() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        sink.record(decision(true, "ALLOWED", "admin-1", "createFacility", "corr-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains("INSERT INTO public.authorization_decision"));
        assertTrue(sql.getValue().contains("public.app_org()"));
        SqlParameterSource params = captured();
        assertEquals("admin-1", params.getValue("subject"));
        assertEquals("FACILITY_WRITE", params.getValue("action"));
        assertEquals("createFacility", params.getValue("resourceType"));
        assertEquals("allow", params.getValue("decision"));
        assertEquals("ALLOWED", params.getValue("reasonCode"));
        assertEquals("corr-1", params.getValue("correlationId"));
        assertEquals("2026-11-19T12:00:00Z", params.getValue("decidedAt"));
        assertFalse(sql.getValue().contains(":organizationId"));
        assertFalse(java.util.Arrays.stream(params.getParameterNames())
                .anyMatch(name -> name.toLowerCase().contains("body")
                        || name.toLowerCase().contains("patient")
                        || name.toLowerCase().contains("payload")));
    }

    @Test
    void denyRowIsWrittenWithAStableReasonCode() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        sink.record(decision(false, "COMMAND_FORBIDDEN", "reception-1", "createFacility", "corr-2"));

        SqlParameterSource params = captured();
        assertEquals("deny", params.getValue("decision"));
        assertEquals("COMMAND_FORBIDDEN", params.getValue("reasonCode"));
        assertEquals("reception-1", params.getValue("subject"));
    }

    @Test
    void jdbcFailurePropagatesSoTheCallerFailsClosed() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new DataRetrievalFailureException("ledger unavailable"));

        DataRetrievalFailureException failure = assertThrows(DataRetrievalFailureException.class,
                () -> sink.record(decision(true, "ALLOWED", "admin-1", "createFacility", "corr-1")));
        assertEquals("ledger unavailable", failure.getMessage());
    }

    @Test
    void zeroRowInsertPropagatesSoAMissingRowCannotLookSuccessful() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> sink.record(decision(true, "ALLOWED", "admin-1", "createFacility", "corr-1")));
        assertTrue(failure.getMessage().contains("not recorded"));
    }

    @Test
    void absentTenantContextFailsClosedBeforeAnySql() {
        TenantContext.clear();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> sink.record(decision(true, "ALLOWED", "admin-1", "createFacility", "corr-1")));
        assertTrue(failure.getMessage().contains("tenant context"));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void nullDecisionIsRejected() {
        assertThrows(NullPointerException.class, () -> sink.record(null));
    }

    @Test
    void untrustedAuditFieldsAreBoundedAndStrippedOfControlCharacters() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        String longSubject = "a".repeat(400) + "\nFORGED";
        String longCorrelation = "c".repeat(400) + "\rFORGED";
        String longCommand = "x".repeat(400) + "\nFORGED";

        sink.record(decision(true, "ALLOWED", longSubject, longCommand, longCorrelation));

        SqlParameterSource params = captured();
        assertEquals(255, ((String) params.getValue("subject")).length());
        assertEquals(255, ((String) params.getValue("correlationId")).length());
        assertEquals(128, ((String) params.getValue("resourceType")).length());
        assertFalse(((String) params.getValue("subject")).contains("\n"));
        assertFalse(((String) params.getValue("correlationId")).contains("\r"));
        assertFalse(((String) params.getValue("resourceType")).contains("\n"));
    }

    @Test
    void shortUntrustedValuesAreStrippedAndFoldedInsteadOfTruncatedAway() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        sink.record(decision(false, "COMMAND_FORBIDDEN", "bad|name\nX", "bad\ncmd", "corr\n1"));

        SqlParameterSource params = captured();
        assertEquals("bad_nameX", params.getValue("subject"));
        assertEquals("badcmd", params.getValue("resourceType"));
        assertEquals("corr1", params.getValue("correlationId"));
    }

    @Test
    void absentSubjectAndCorrelationAreWrittenAsNull() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        sink.record(decision(false, "UNAUTHENTICATED", null, "", null));

        SqlParameterSource params = captured();
        assertNull(params.getValue("subject"));
        assertNull(params.getValue("correlationId"));
        assertNull(params.getValue("resourceType"));
    }

    @Test
    void blankSubjectAndCorrelationAreWrittenAsNull() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        sink.record(decision(false, "UNAUTHENTICATED", "   ", "createFacility", "   "));

        SqlParameterSource params = captured();
        assertNull(params.getValue("subject"));
        assertNull(params.getValue("correlationId"));
    }
}
