package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization.ActionRule;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;

/** OPS-02 decision ledger adapter: bounded codes only, tenant-bound insert, fail closed on audit loss. */
class JdbcDecisionSinkTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant WHEN = Instant.parse("2026-11-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(WHEN, ZoneOffset.UTC);

    private NamedParameterJdbcTemplate jdbc;
    private JdbcDecisionSink sink;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        sink = new JdbcDecisionSink(jdbc);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    }

    private static Decision decision(boolean allowed, String reasonCode, String commandId,
                                     String subject, String correlationId) {
        return new Decision(commandId, "FACILITY_WRITE", ORG, subject, correlationId,
                allowed, reasonCode, List.of("tenant_admin"), WHEN);
    }

    private SqlParameterSource capturedParameters() {
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        return params.getValue();
    }

    @Test
    void allowDecisionIsWrittenAsBoundedCodesWithoutPayloadFields() {
        sink.record(decision(true, "ALLOWED", "createFacility", "admin-1", "corr-1"));
        SqlParameterSource params = capturedParameters();
        assertEquals("allow", params.getValue("decision"));
        assertEquals("ALLOWED", params.getValue("reasonCode"));
        assertEquals("createFacility", params.getValue("resourceType"));
        assertEquals("FACILITY_WRITE", params.getValue("action"));
        assertEquals("admin-1", params.getValue("subject"));
        assertEquals("corr-1", params.getValue("correlationId"));
        assertEquals(ORG, params.getValue("organizationId"));
        assertEquals(WHEN.toString(), params.getValue("decidedAt"));
        assertNotNullId(params.getValue("id"));
    }

    @Test
    void denyDecisionKeepsItsReasonCodeAndDecisionValue() {
        sink.record(decision(false, "COMMAND_FORBIDDEN", "createFacility", "reception-1", "corr-2"));
        SqlParameterSource params = capturedParameters();
        assertEquals("deny", params.getValue("decision"));
        assertEquals("COMMAND_FORBIDDEN", params.getValue("reasonCode"));
        assertEquals("reception-1", params.getValue("subject"));
    }

    @Test
    void freeTextValuesAreBoundedAndControlCharactersAreRemoved() {
        sink.record(decision(false, "x".repeat(200), "y".repeat(300), "a\nb", "c\n" + "d".repeat(300)));
        SqlParameterSource params = capturedParameters();
        assertEquals("x".repeat(64), params.getValue("reasonCode"));
        assertEquals("y".repeat(128), params.getValue("resourceType"));
        assertEquals("ab", params.getValue("subject"));
        assertEquals("c" + "d".repeat(127), params.getValue("correlationId"));
    }

    @Test
    void absentFreeTextValuesBecomeEmptyStrings() {
        sink.record(new Decision("createFacility", "FACILITY_WRITE", ORG, null, null,
                true, "ALLOWED", List.of(), WHEN));
        SqlParameterSource params = capturedParameters();
        assertEquals("", params.getValue("subject"));
        assertEquals("", params.getValue("correlationId"));
    }

    @Test
    void decisionsWithoutAUsableTenantAreNotStoredAndBecomeAuditUnavailableDenials() {
        for (String badOrg : new String[] {null, "", "   ", "not-a-uuid", "11111111-1111-1111-1111-11111111111g"}) {
            Decision source = new Decision("createFacility", "FACILITY_WRITE", badOrg, "admin-1", "corr-3",
                    true, "ALLOWED", List.of("tenant_admin"), WHEN);
            DecisionAuditUnavailable failure = assertThrows(DecisionAuditUnavailable.class,
                    () -> sink.record(source));
            assertEquals("AUDIT_UNAVAILABLE", failure.decision().reasonCode());
            assertFalse(failure.decision().allowed());
            assertEquals(badOrg, failure.decision().organizationId());
        }
    }

    @Test
    void jdbcFailureBecomesAnAuditUnavailableDenyThatFailsTheWholeCommandClosed() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new DataRetrievalFailureException("ledger unavailable"));
        Decision source = decision(true, "ALLOWED", "createFacility", "admin-1", "corr-4");
        DecisionAuditUnavailable failure = assertThrows(DecisionAuditUnavailable.class,
                () -> sink.record(source));
        assertEquals("AUDIT_UNAVAILABLE", failure.decision().reasonCode());
        assertFalse(failure.decision().allowed());
        assertEquals("admin-1", failure.decision().subject());
        assertEquals("corr-4", failure.decision().correlationId());
        assertEquals(List.of("tenant_admin"), failure.decision().rolesUsed());
        assertEquals(WHEN, failure.decision().occurredAt());

        Map<String, ActionRule> owners = Map.of(CommandCatalog.ACTION_POLICY,
                (action, role) -> "tenant_admin".equals(role));
        CommandAuthorization authorization = new CommandAuthorization(CommandCatalog.synthetic(),
                owners, identity -> new MemberContext(List.of("tenant_admin"), true), sink, CLOCK);
        ApiException denied = assertThrows(ApiException.class,
                () -> authorization.authorize(new CommandRequest("createFacility", null, null),
                        new ServerIdentity(ORG, "admin-1", "corr-4")));
        assertEquals(500, denied.status());
        assertEquals("DECISION_NOT_RECORDED", denied.code());
    }

    @Test
    void nullDecisionIsRejectedBeforeAnySql() {
        assertThrows(NullPointerException.class, () -> sink.record(null));
        assertThrows(NullPointerException.class, () -> new JdbcDecisionSink(null));
    }

    private static void assertNotNullId(Object id) {
        assertInstanceOf(String.class, id);
        assertTrue(((String) id).matches("[0-9a-f-]{36}"));
    }
}
