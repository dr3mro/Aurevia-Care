package com.aurevia.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.JdbcDecisionSink;
import com.aurevia.tenant.TenantContext;

/**
 * Verifies that production wiring exposes one durable authorization sink and
 * starts its audit write in an independent tenant-guarded transaction.
 */
class AuthorizationConfigurationTest {

    @Test
    void configurationProvidesTheTransactionalJdbcSink() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        AuthorizationConfiguration configuration = new AuthorizationConfiguration();

        Object bean = configuration.authorizationDecisionSink(jdbc, transactionManager);

        JdbcDecisionSink sink = assertInstanceOf(JdbcDecisionSink.class, bean);
        TenantContext.set(new TenantContext("11111111-1111-1111-1111-111111111111",
                "admin-1", "FACILITY_ADMIN", "corr-1"));
        try {
            sink.record(new Decision("createFacility", "FACILITY_WRITE",
                    "11111111-1111-1111-1111-111111111111", "admin-1", "corr-1", true,
                    "ALLOWED", List.of("tenant_admin"), Instant.parse("2026-11-25T06:00:00Z")));
        } finally {
            TenantContext.clear();
        }

        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getValue().getPropagationBehavior());
    }
}
