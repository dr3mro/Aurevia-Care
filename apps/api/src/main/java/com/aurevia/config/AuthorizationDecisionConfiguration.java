package com.aurevia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.JdbcDecisionSink;

/**
 * Production wiring for the durable OPS-02 authorization decision ledger.
 *
 * <p>The bean is the only production
 * {@link com.aurevia.api.authorization.CommandAuthorization.DecisionSink}: it appends to the
 * V65 table through the tenant-guarded transaction. Unit tests construct the
 * synthetic in-memory sink directly and do not load this configuration, so a
 * test never silently replaces the durable audit path.</p>
 */
@Configuration
public class AuthorizationDecisionConfiguration {

    /**
     * Expose the durable decision sink to the authorization gate.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     * @return the PostgreSQL-backed decision sink
     */
    @Bean
    DecisionSink authorizationDecisionSink(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        return new JdbcDecisionSink(jdbc, transactions);
    }
}
