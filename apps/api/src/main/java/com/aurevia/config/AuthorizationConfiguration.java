package com.aurevia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.JdbcDecisionSink;

/**
 * Production wiring for the OPS-02 authorization decision ledger (ARC-04).
 *
 * <p>The command gate receives its sink by injection; supplying the bean here
 * keeps the synthetic in-memory sink available to unit tests without a Spring
 * context. The ledger write uses a fresh tenant-guarded transaction so a deny
 * row survives the rollback of the command it rejected.</p>
 */
@Configuration
public class AuthorizationConfiguration {

    /**
     * Provide the single durable decision sink used by the command gate.
     *
     * @param jdbc               shared named-parameter JDBC template
     * @param transactionManager tenant-guarded platform transaction manager
     * @return the production decision sink writing to the V65 ledger
     */
    @Bean
    DecisionSink authorizationDecisionSink(NamedParameterJdbcTemplate jdbc,
                                           PlatformTransactionManager transactionManager) {
        TransactionTemplate ledgerTransactions = new TransactionTemplate(transactionManager);
        ledgerTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new JdbcDecisionSink(jdbc, ledgerTransactions);
    }
}
