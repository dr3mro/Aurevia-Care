package com.aurevia.smartclinic.tenant;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;

/** Binds transaction-local tenant context after the connection is in a transaction. */
@Component
public class TenantGuardTransactionManager extends DataSourceTransactionManager {

    private final JdbcTemplate jdbc;

    public TenantGuardTransactionManager(DataSource dataSource) {
        super(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new IllegalStateException("tenant context required");
        }
        super.doBegin(transaction, definition);
        jdbc.queryForObject("""
                SELECT set_config('app.current_org', ?, true) ||
                       set_config('app.actor', ?, true) ||
                       set_config('app.purpose', ?, true) ||
                       set_config('app.correlation_id', ?, true)
                """, String.class, context.organizationId(), context.subject(),
                context.purpose(), context.correlationId());
    }
}
