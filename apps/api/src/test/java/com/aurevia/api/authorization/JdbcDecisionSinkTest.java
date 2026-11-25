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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.tenant.TenantContext;

/**
 * OPS-02 decision ledger unit assertions. Every branch of the durable sink is
 * taken with a mocked JDBC boundary so the failure paths are deterministic.
 */
class JdbcDecisionSinkTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final Instant DECIDED = Instant.parse("2026-11-25T06:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static JdbcDecisionSink sink(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        return new JdbcDecisionSink(jdbc, transactions);
    }

    private static Decision decision(String organization, boolean allowed, String reasonCode) {
        return new Decision("createFacility", "FACILITY_WRITE", organization, "admin-1", "corr-1",
                allowed, reasonCode, List.of("tenant_admin"), DECIDED);
    }

    private static void bind(String organization) {
        TenantContext.set(new TenantContext(organization, "admin-1", "FACILITY_ADMIN", "corr-1"));
    }

    private static SqlParameterSource captured(NamedParameterJdbcTemplate jdbc) {
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        return params.getValue();
    }

    private static void assertDenied(Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(403, failure.status());
        assertEquals("AUDIT_UNAVAILABLE", failure.code());
        assertEquals("authorization decision could not be recorded", failure.getMessage());
    }

    @Test
    void allowDecisionWritesOnlyCodedColumns() {
        bind(ORG_A);
        sink(jdbc, transactions).record(decision(ORG_A, true, "ALLOWED"));
        SqlParameterSource params = captured(jdbc);
        assertEquals(ORG_A, params.getValue("organizationId"));
        assertEquals("admin-1", params.getValue("subject"));
        assertEquals("FACILITY_WRITE", params.getValue("action"));
        assertEquals(JdbcDecisionSink.RESOURCE_TYPE, params.getValue("resourceType"));
        assertEquals("allow", params.getValue("decision"));
        assertEquals("ALLOWED", params.getValue("reasonCode"));
        assertEquals("corr-1", params.getValue("correlationId"));
        assertEquals(OffsetDateTime.ofInstant(DECIDED, ZoneOffset.UTC), params.getValue("decidedAt"));
        assertFalse(params.hasValue("rolesUsed"));
        assertFalse(params.hasValue("payload"));
    }

    @Test
    void denyDecisionWritesTheDenyOutcomeAndReason() {
        bind(ORG_A);
        sink(jdbc, transactions).record(decision(ORG_A, false, "COMMAND_FORBIDDEN"));
        SqlParameterSource params = captured(jdbc);
        assertEquals("deny", params.getValue("decision"));
        assertEquals("COMMAND_FORBIDDEN", params.getValue("reasonCode"));
        assertEquals("FACILITY_WRITE", params.getValue("action"));
    }

    @Test
    void blankDecisionOrganizationUsesTheVerifiedTenant() {
        bind(ORG_A);
        sink(jdbc, transactions).record(decision("", true, "ALLOWED"));
        assertEquals(ORG_A, captured(jdbc).getValue("organizationId"));
    }

    @Test
    void nullDecisionOrganizationUsesTheVerifiedTenant() {
        bind(ORG_A);
        sink(jdbc, transactions).record(decision(null, false, "COMMAND_FORBIDDEN"));
        assertEquals(ORG_A, captured(jdbc).getValue("organizationId"));
    }

    @Test
    void foreignDecisionOrganizationDeniesWithoutWriting() {
        bind(ORG_A);
        JdbcDecisionSink sink = sink(jdbc, transactions);
        assertDenied(() -> sink.record(decision(ORG_B, true, "ALLOWED")));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void unknownReasonCodeDeniesWithoutWriting() {
        bind(ORG_A);
        JdbcDecisionSink sink = sink(jdbc, transactions);
        assertDenied(() -> sink.record(decision(ORG_A, false, "FREE_TEXT_REASON")));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void missingTenantContextDeniesWithoutWriting() {
        TenantContext.clear();
        JdbcDecisionSink sink = sink(jdbc, transactions);
        assertDenied(() -> sink.record(decision(ORG_A, true, "ALLOWED")));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void nullOrganizationInContextDeniesWithoutWriting() {
        TenantContext.set(new TenantContext(null, "admin-1", "FACILITY_ADMIN", "corr-1"));
        JdbcDecisionSink sink = sink(jdbc, transactions);
        assertDenied(() -> sink.record(decision(ORG_A, true, "ALLOWED")));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void blankOrganizationInContextDeniesWithoutWriting() {
        bind("   ");
        JdbcDecisionSink sink = sink(jdbc, transactions);
        assertDenied(() -> sink.record(decision(ORG_A, true, "ALLOWED")));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void jdbcFailureDeniesWithoutLeakingSqlDetails() {
        bind(ORG_A);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenThrow(
                new DataAccessException("SECRET_SQL_TOKEN authorization_decision") { });
        JdbcDecisionSink sink = sink(jdbc, transactions);
        ApiException failure = assertThrows(ApiException.class,
                () -> sink.record(decision(ORG_A, true, "ALLOWED")));
        assertEquals(403, failure.status());
        assertEquals("AUDIT_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("SECRET_SQL_TOKEN"));
    }

    @Test
    void nullDecisionIsAProgrammingErrorAndNeverWrites() {
        bind(ORG_A);
        JdbcDecisionSink sink = sink(jdbc, transactions);
        assertThrows(NullPointerException.class, () -> sink.record(null));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void collaboratorsAreRequired() {
        assertThrows(NullPointerException.class, () -> new JdbcDecisionSink(null, transactions));
        assertThrows(NullPointerException.class, () -> new JdbcDecisionSink(jdbc, null));
        assertTrue(JdbcDecisionSink.REASON_CODES.contains("AUDIT_UNAVAILABLE"));
    }
}
