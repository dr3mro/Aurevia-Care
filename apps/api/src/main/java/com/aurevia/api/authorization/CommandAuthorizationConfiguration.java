package com.aurevia.api.authorization;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.staff.StaffRepository;

/**
 * Production wiring for the single server-side command gate (OPS-02, ARC-04).
 *
 * <p>The catalog, membership resolver and decision sink are injected into one authorizer;
 * controllers and interceptors never construct their own policy rules. The sink is the
 * durable bean delivered by the authorization ledger, so every allow and deny reaches the
 * append-only table. No second sink is created here.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CommandAuthorizationConfiguration {

    /**
     * Provide the immutable command catalog shared by the gate and HTTP registration.
     *
     * @return the synthetic production catalog
     */
    @Bean
    public CommandCatalog commandCatalog() {
        return CommandCatalog.synthetic();
    }

    /**
     * Provide the one authorizer used by the HTTP gate.
     *
     * @param catalog       registered commands and their owning policies
     * @param sink          durable decision ledger supplied by the application configuration
     * @param staff         tenant-guarded membership and role folds
     * @param transactions  tenant-guarded transaction template
     * @param clock         shared application clock used for decision timestamps
     * @return an authorizer backed by the delivered domain policies and live membership
     */
    @Bean
    public CommandAuthorization commandAuthorization(CommandCatalog catalog, DecisionSink sink,
                                                     StaffRepository staff, TransactionTemplate transactions,
                                                     Clock clock) {
        MembershipResolver resolver = new JdbcMembershipResolver(staff, transactions);
        return CommandAuthorization.synthetic(catalog, resolver, sink, clock);
    }
}
