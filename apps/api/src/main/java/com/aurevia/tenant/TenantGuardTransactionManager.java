package com.aurevia.tenant;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;

/**
 * Binds transaction-local tenant context after the connection is in a transaction.
 *
 * <p>GUCs are set with {@code set_config(..., true)} only after the transaction
 * has opened, so pooled connections cannot leak a prior tenant's context.</p>
 */
@Component
public class TenantGuardTransactionManager extends DataSourceTransactionManager {

    /**
     * Create the guarded transaction manager.
     *
     * @param dataSource the shared application datasource
     */
    public TenantGuardTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * Open a transaction and bind the current tenant context to it.
     *
     * @param transaction the transaction object
     * @param definition  transaction definition
     * @throws IllegalStateException when no tenant context is bound
     */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new IllegalStateException("tenant context required");
        }
        super.doBegin(transaction, definition);
        new JdbcTemplate(getDataSource()).queryForObject("""
                SELECT set_config('app.current_org', ?, true) ||
                       set_config('app.actor', ?, true) ||
                       set_config('app.purpose', ?, true) ||
                       set_config('app.correlation_id', ?, true)
                """, String.class, context.organizationId(), context.subject(),
                context.purpose(), context.correlationId());
    }
}
