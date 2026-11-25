package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.api.authorization.CommandAuthorization.Decision;

/** OPS-02: the production sink writes bounded authorization facts, never a payload. */
class JdbcDecisionSinkTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant DECIDED_AT = Instant.parse("2026-11-25T10:15:30Z");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcDecisionSink sink;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        sink = new JdbcDecisionSink(jdbc);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    }

    private static Decision decision(boolean allowed, String organizationId) {
        return new Decision("verifyIdentity", "IDENTITY_VERIFY", organizationId, "verifier-1", "corr-1",
                allowed, allowed ? "ALLOWED" : "COMMAND_FORBIDDEN", List.of("lab_verifier"), DECIDED_AT);
    }

    private SqlParameterSource capturedParameters() {
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), parameters.capture());
        return parameters.getValue();
    }

    @Test
    void insertsAllowedDecisionWithAllowCode() {
        sink.record(decision(true, ORG));

        SqlParameterSource parameters = capturedParameters();
        assertEquals(ORG, parameters.getValue("organizationId"));
        assertEquals("verifier-1", parameters.getValue("subject"));
        assertEquals("IDENTITY_VERIFY", parameters.getValue("action"));
        assertEquals("verifyIdentity", parameters.getValue("resourceType"));
        assertEquals("allow", parameters.getValue("decision"));
        assertEquals("ALLOWED", parameters.getValue("reasonCode"));
        assertEquals("corr-1", parameters.getValue("correlationId"));
        assertEquals(java.sql.Timestamp.from(DECIDED_AT), parameters.getValue("decidedAt"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains("public.authorization_decision"));
        assertTrue(sql.getValue().contains("INSERT"));
    }

    @Test
    void insertsDeniedDecisionWithDenyCode() {
        sink.record(decision(false, ORG));
        assertEquals("deny", capturedParameters().getValue("decision"));
        assertEquals("COMMAND_FORBIDDEN", capturedParameters().getValue("reasonCode"));
    }

    @Test
    void rejectsNullDecision() {
        assertThrows(NullPointerException.class, () -> sink.record(null));
    }

    @Test
    void rejectsDecisionWithoutActiveOrganization() {
        for (String missing : new String[] {null, "", "   "}) {
            Decision unauthenticated = decision(true, missing);
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> sink.record(unauthenticated));
            assertTrue(failure.getMessage().contains("active organization"));
        }
    }

    @Test
    void rejectsNullJdbcTemplate() {
        assertThrows(NullPointerException.class, () -> new JdbcDecisionSink(null));
    }

    @Test
    void propagatesJdbcFailureSoTheCommandFailsClosed() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new IllegalStateException("ledger unavailable"));
        assertEquals("ledger unavailable", assertThrows(IllegalStateException.class,
                () -> sink.record(decision(true, ORG))).getMessage());
    }
}
