package com.aurevia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.JdbcDecisionSink;

/**
 * Production registration for the durable authorization decision ledger.
 *
 * <p>Tests construct {@code InMemoryDecisionSink} directly; only this
 * configuration supplies the singleton production sink. The bean is injected
 * with the shared JDBC template so the insert joins the caller's
 * tenant-guarded transaction (OPS-02, ARC-02).</p>
 */
@Configuration
public class AuthorizationDecisionConfiguration {

    /**
     * Provide the single durable decision sink used outside tests.
     *
     * @param jdbc shared named-parameter JDBC template bound to the active tenant
     * @return append-only JDBC decision sink
     */
    @Bean
    public DecisionSink authorizationDecisionSink(NamedParameterJdbcTemplate jdbc) {
        return new JdbcDecisionSink(jdbc);
    }
}
