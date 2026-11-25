package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.config.AuthorizationDecisionConfiguration;

/** Production wiring: one durable decision sink bean, constructed by injection only. */
class AuthorizationDecisionConfigurationTest {

    @Test
    void configurationSuppliesTheDurableSinkAsTheDecisionSinkBean() {
        AuthorizationDecisionConfiguration configuration = new AuthorizationDecisionConfiguration();
        DecisionSink sink = configuration.authorizationDecisionSink(mock(NamedParameterJdbcTemplate.class));
        assertInstanceOf(JdbcDecisionSink.class, sink);
    }

    @Test
    void configurationRejectsAMissingJdbcTemplate() {
        AuthorizationDecisionConfiguration configuration = new AuthorizationDecisionConfiguration();
        assertThrows(NullPointerException.class, () -> configuration.authorizationDecisionSink(null));
    }
}
