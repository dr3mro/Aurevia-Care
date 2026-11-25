package com.aurevia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.JdbcDecisionSink;

/**
 * Production wiring for the OPS-02 authorization decision ledger.
 *
 * <p>The gate takes its sink by injection, so the application supplies the
 * durable JDBC adapter here. Unit tests construct
 * {@code com.aurevia.api.authorization.InMemoryDecisionSink} directly and are
 * unaffected. The caller's tenant-guarded transaction is used by the sink, so
 * no new transaction boundary is introduced by this bean.</p>
 */
@Configuration
public class AuthorizationDecisionConfiguration {

    /**
     * Expose the durable ledger as the single production decision sink.
     *
     * @param jdbc shared named-parameter JDBC template
     * @return append-only sink over the V65 authorization_decision table
     */
    @Bean
    public DecisionSink authorizationDecisionSink(NamedParameterJdbcTemplate jdbc) {
        return new JdbcDecisionSink(jdbc);
    }
}
