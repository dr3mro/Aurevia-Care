package com.aurevia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.JdbcDecisionSink;

/**
 * Production wiring for the durable authorization decision ledger (OPS-02).
 *
 * <p>The gate receives this sink by injection; unit and synthetic tests keep
 * constructing their own in-memory sink. No command may reach a service
 * without a recorded allow or deny outcome, so the production bean writes to
 * PostgreSQL rather than to process memory.</p>
 */
@Configuration
public class DecisionLedgerConfiguration {

    /**
     * Provide the durable decision sink used by the authorization gate.
     *
     * @param jdbc          shared named-parameter JDBC template
     * @param transactions  shared tenant-guarded transaction template
     * @return the PostgreSQL-backed decision sink
     */
    @Bean
    CommandAuthorization.DecisionSink authorizationDecisionSink(NamedParameterJdbcTemplate jdbc,
                                                                TransactionTemplate transactions) {
        return new JdbcDecisionSink(jdbc, transactions);
    }
}
