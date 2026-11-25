package com.aurevia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.JdbcDecisionSink;

/**
 * Production wiring for the durable OPS-02 authorization decision ledger.
 *
 * <p>The gate itself is supplied by the owning command layer; this configuration
 * only chooses the sink. Production uses PostgreSQL, so a decision survives a
 * restart and is visible to every API instance. Unit tests construct
 * {@code InMemoryDecisionSink} directly and are unaffected by this bean.</p>
 */
@Configuration
public class AuthorizationDecisionConfiguration {

    /**
     * Provide the durable decision sink used by production authorization.
     *
     * @param jdbc shared named-parameter JDBC template
     * @return PostgreSQL-backed decision sink
     */
    @Bean
    DecisionSink decisionSink(NamedParameterJdbcTemplate jdbc) {
        return new JdbcDecisionSink(jdbc);
    }
}
