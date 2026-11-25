package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.config.AuthorizationDecisionConfiguration;

/**
 * OPS-02 wiring check: the production Spring configuration must expose the durable
 * sink behind the {@link com.aurevia.api.authorization.CommandAuthorization.DecisionSink}
 * interface so tests can keep using the synthetic in-memory adapter.
 */
class AuthorizationDecisionConfigurationTest {

    @Test
    void configurationProvidesTheJdbcSinkAsTheDecisionBean() {
        NamedParameterJdbcTemplate jdbc = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);

        Object bean = new AuthorizationDecisionConfiguration().authorizationDecisionSink(jdbc);

        assertInstanceOf(JdbcDecisionSink.class, bean);
    }
}
