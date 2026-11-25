package com.aurevia.web;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.CommandDefinition;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.authz.specialty.SpecialtyActions;
import com.aurevia.exchange.critical.CriticalActions;
import com.aurevia.exchange.release.ReleaseActionPolicy;
import com.aurevia.exchange.response.ExchangeResponseActionPolicy;
import com.aurevia.finance.FinanceActions;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.scheduling.checkin.WorklistPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Re-reads live, command-specific authorization before a keyed response is replayed.
 *
 * <p>Every protected write in this codebase re-resolves the caller's membership
 * and role fold from PostgreSQL at request time and asks the policy that owns
 * the command's action. The replay path must do the same even though it does not
 * re-run the handler. The matched route is resolved against the immutable command
 * catalog, so a replay is refused for any route without a registered action.
 * Both reads run inside the tenant-guarded transaction, so the active
 * organization's RLS policy scopes the authoritative fold and a missing, revoked
 * or insufficiently privileged caller fails closed.</p>
 */
@Component
final class DurableReplayAuthorization implements ReplayAuthorization {

    private static final CommandCatalog CATALOG = CommandCatalog.synthetic();
    private static final Map<String, CommandAuthorization.ActionRule> RULES = policyRules();

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the live authorization check.
     *
     * @param jdbc shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template that binds the tenant GUC
     */
    DurableReplayAuthorization(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public boolean stillAuthorized(TenantContext context, String command) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Boolean active = jdbc.queryForObject("SELECT public.has_active_membership(:subject)",
                    Map.of("subject", context.subject()), Boolean.class);
            if (!Boolean.TRUE.equals(active)) {
                return false;
            }
            List<String> roles = jdbc.queryForList("SELECT role_key FROM public.active_roles(:subject)",
                    Map.of("subject", context.subject()), String.class);
            if (roles.isEmpty()) {
                return false;
            }
            CommandDefinition definition = definition(command);
            if (definition == null) {
                // No registered action means no authorization decision can be made.
                return false;
            }
            CommandAuthorization.ActionRule rule = RULES.get(definition.policy());
            return roles.stream().anyMatch(role -> rule.allows(definition.action(), role));
        }));
    }

    /**
     * Resolve the catalog action that owns one matched POST route.
     *
     * @param command command name built from the HTTP method and matched route
     * @return the owning definition, or {@code null} when the route is not registered
     */
    private static CommandDefinition definition(String command) {
        if (!command.startsWith("POST ")) {
            return null;
        }
        String path = command.substring("POST ".length());
        for (CommandDefinition candidate : CATALOG.commands()) {
            if (candidate.path().equals(path)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Bind every catalog policy name to its existing owning action rule.
     *
     * @return immutable policy-to-rule map, identical to the catalog's own wiring
     */
    private static Map<String, CommandAuthorization.ActionRule> policyRules() {
        Map<String, CommandAuthorization.ActionRule> owners = new java.util.LinkedHashMap<>();
        owners.put(CommandCatalog.ACTION_POLICY, new ActionPolicy()::allows);
        owners.put(CommandCatalog.WORKLIST_POLICY, new WorklistPolicy()::allows);
        owners.put(CommandCatalog.LAB_POLICY, new LabActionPolicy()::allows);
        owners.put(CommandCatalog.SPECIALTY_POLICY, new SpecialtyActions()::allows);
        owners.put(CommandCatalog.EXCHANGE_RESPONSE_POLICY, new ExchangeResponseActionPolicy()::allows);
        owners.put(CommandCatalog.RELEASE_POLICY, new ReleaseActionPolicy()::allows);
        owners.put(CommandCatalog.CRITICAL_POLICY, new CriticalActions()::allows);
        owners.put(CommandCatalog.FINANCE_POLICY, new FinanceActions()::allows);
        owners.put(CommandCatalog.INSTRUCTION_POLICY, new InstructionFactActions()::allows);
        return Map.copyOf(owners);
    }
}
