package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.tenant.TenantContext;

/**
 * OPS-02 deterministic branch coverage for the durable decision ledger boundary.
 *
 * <p>The mocked template forces the failure and empty-write sides that a healthy
 * PostgreSQL cannot produce on demand. Every decision and identifier is synthetic.</p>
 */
class JdbcDecisionSinkTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final Instant AT = Instant.parse("2026-11-25T09:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcDecisionSink sink;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        sink = new JdbcDecisionSink(jdbc);
        bind(ORG_A);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static void bind(String organization) {
        TenantContext.set(new TenantContext(organization, "svc-a", "AUTHORIZATION", "corr-1"));
    }

    private static Decision decision(String commandId, boolean allowed, String reason) {
        return new Decision(commandId, "FACILITY_WRITE", ORG_A, "svc-a", "corr-1", allowed,
                reason, List.of("tenant_admin"), AT);
    }

    private MapSqlParameterSource captured() {
        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void allowDecisionWritesOnlyStructuredColumns() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        sink.record(decision("createFacility", true, "ALLOWED"));

        MapSqlParameterSource parameters = captured();
        assertEquals(ORG_A, parameters.getValue("organizationId"));
        assertEquals("svc-a", parameters.getValue("subject"));
        assertEquals("FACILITY_WRITE", parameters.getValue("action"));
        assertEquals("createFacility", parameters.getValue("resourceType"));
        assertEquals("allow", parameters.getValue("decision"));
        assertEquals("ALLOWED", parameters.getValue("reasonCode"));
        assertEquals("corr-1", parameters.getValue("correlationId"));
        assertEquals(7, parameters.getValues().size(),
                "the ledger must not carry a request body or patient field");
    }

    @Test
    void denyDecisionIsPersistedAsDeny() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        sink.record(decision("createFacility", false, "COMMAND_FORBIDDEN"));

        assertEquals("deny", captured().getValue("decision"));
        assertEquals("COMMAND_FORBIDDEN", captured().getValue("reasonCode"));
    }

    @Test
    void untrustedValuesAreBoundedAndStrippedOfControlCharacters() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        String commandId = "a".repeat(10) + "\u2028" + "b".repeat(10) + "\u2029" + "\u202E"
                + "c".repeat(300) + "\nFORGED";
        Decision unbounded = new Decision(commandId, "ACTION\nX", ORG_A, null, "c".repeat(300),
                true, "ALLOWED", List.of(), AT);

        sink.record(unbounded);

        MapSqlParameterSource parameters = captured();
        assertEquals("a".repeat(10) + "b".repeat(10) + "c".repeat(108),
                parameters.getValue("resourceType"));
        assertEquals("ACTIONX", parameters.getValue("action"));
        assertEquals("", parameters.getValue("subject"));
        assertEquals("c".repeat(128), parameters.getValue("correlationId"));
    }

    @Test
    void everyAuditedCodeIsBoundedByItsColumnLength() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        String oversized = "x".repeat(300);
        Decision unbounded = new Decision(oversized, oversized, ORG_A, oversized, oversized,
                true, oversized, List.of(), AT);

        sink.record(unbounded);

        MapSqlParameterSource parameters = captured();
        assertEquals(JdbcDecisionSink.SUBJECT_MAX,
                ((String) parameters.getValue("subject")).length());
        assertEquals(JdbcDecisionSink.ACTION_MAX,
                ((String) parameters.getValue("action")).length());
        assertEquals(JdbcDecisionSink.REASON_CODE_MAX,
                ((String) parameters.getValue("reasonCode")).length());
        assertEquals(JdbcDecisionSink.CORRELATION_MAX,
                ((String) parameters.getValue("correlationId")).length());
        assertEquals("x".repeat(JdbcDecisionSink.REASON_CODE_MAX), parameters.getValue("reasonCode"));
    }

    @Test
    void codePointTruncationDoesNotSplitSurrogatePairs() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        String emoji = "\uD83D\uDE00";
        String commandId = "x".repeat(127) + emoji + "z".repeat(300);

        sink.record(decision(commandId, true, "ALLOWED"));

        String stored = (String) captured().getValue("resourceType");
        assertEquals("x".repeat(127) + emoji, stored);
        assertTrue(Character.isLowSurrogate(stored.charAt(stored.length() - 1)));
    }

    @Test
    void absentTenantContextFailsClosedWithoutSql() {
        TenantContext.clear();

        DecisionAuditException failure = assertThrows(DecisionAuditException.class,
                () -> sink.record(decision("createFacility", true, "ALLOWED")));

        assertDenial(failure);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void blankTenantOrganizationFailsClosedWithoutSql() {
        TenantContext.set(new TenantContext("  ", "svc-a", "AUTHORIZATION", "corr-1"));

        DecisionAuditException failure = assertThrows(DecisionAuditException.class,
                () -> sink.record(decision("createFacility", true, "ALLOWED")));

        assertDenial(failure);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void nullTenantOrganizationFailsClosedWithoutSql() {
        TenantContext.set(new TenantContext(null, "svc-a", "AUTHORIZATION", "corr-1"));

        DecisionAuditException failure = assertThrows(DecisionAuditException.class,
                () -> sink.record(decision("createFacility", true, "ALLOWED")));

        assertDenial(failure);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void decisionOutsideTheActiveTenantFailsClosedWithoutSql() {
        bind(ORG_B);

        DecisionAuditException failure = assertThrows(DecisionAuditException.class,
                () -> sink.record(decision("createFacility", true, "ALLOWED")));

        assertDenial(failure);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void emptyInsertResultFailsClosed() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

        DecisionAuditException failure = assertThrows(DecisionAuditException.class,
                () -> sink.record(decision("createFacility", true, "ALLOWED")));

        assertDenial(failure);
    }

    @Test
    void databaseFailureFailsClosedAndKeepsTheCause() {
        TransientDataAccessResourceException cause =
                new TransientDataAccessResourceException("ledger unavailable");
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenThrow(cause);

        DecisionAuditException failure = assertThrows(DecisionAuditException.class,
                () -> sink.record(decision("createFacility", true, "ALLOWED")));

        assertDenial(failure);
        assertEquals(cause, failure.getCause());
    }

    @Test
    void nullDecisionIsRejectedBeforeAnySql() {
        assertThrows(NullPointerException.class, () -> sink.record(null));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    private static void assertDenial(DecisionAuditException failure) {
        Decision denial = failure.denial();
        assertFalse(denial.allowed());
        assertEquals(DecisionAuditException.REASON_CODE, denial.reasonCode());
        assertEquals("AUDIT_UNAVAILABLE", denial.reasonCode());
        assertEquals(ORG_A, denial.organizationId());
        assertEquals(List.of("tenant_admin"), denial.rolesUsed());
        assertEquals("FACILITY_WRITE", denial.action());
    }
}
