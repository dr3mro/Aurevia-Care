package com.aurevia.api.authorization;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.authz.specialty.SpecialtyActions;
import com.aurevia.exchange.critical.CriticalActions;
import com.aurevia.exchange.release.ReleaseActionPolicy;
import com.aurevia.exchange.response.ExchangeResponseActionPolicy;
import com.aurevia.finance.FinanceActions;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.scheduling.checkin.WorklistPolicy;

/**
 * Server-side authorization gate for every command in the catalog (PRD-06, IAM-01, IAM-02, OPS-02).
 *
 * <p>Authority is never derived from the command request. The client may send a
 * command identifier and inert claimed fields; the subject comes from the
 * server request context, membership and roles are re-resolved on every call,
 * and the action is decided by exactly the policy that owns it. Other policies
 * cannot widen a command through a colliding action key. Every tenant-scoped
 * outcome, including malformed and denied calls, is recorded before the caller
 * receives a response. A tenantless unauthenticated caller keeps its defined
 * 401 because forced RLS cannot own a row; a verified-tenant audit failure
 * still denies with the fixed audit error.</p>
 *
 * <p>This gate is deliberately coarse: it does not replace the owning service's
 * object authorization, revision preconditions or human clinical attestation
 * (PRD-06). The caller supplies the {@code DecisionSink}; production owns an
 * independent tenant-guarded transaction so a denial survives the rejected
 * command's rollback. The in-memory sink in this package is synthetic only.</p>
 */
public final class CommandAuthorization {

    /**
     * Delegates one action/role decision to the policy that owns the action.
     */
    @FunctionalInterface
    public interface ActionRule {

        /**
         * Decide whether a role may perform an action.
         *
         * @param action action key from the owning policy
         * @param role   role key active in the current organization
         * @return {@code true} only when the owning policy explicitly allows it
         */
        boolean allows(String action, String role);
    }

    /**
     * Receives every authorization outcome so denials are durable evidence.
     */
    @FunctionalInterface
    public interface DecisionSink {

        /**
         * Record one decision.
         *
         * @param decision allow or deny with its stable reason code
         */
        void record(Decision decision);
    }

    /**
     * Re-reads membership and roles for an identity inside a guarded transaction.
     */
    @FunctionalInterface
    public interface MembershipResolver {

        /**
         * Resolve the current fold for the exact active organization and subject.
         *
         * @param identity server-derived active identity
         * @return fresh active roles and membership, or {@code null} when unavailable
         */
        MemberContext resolve(ServerIdentity identity);
    }

    /**
     * Client-controlled command request. The claimed fields are deliberately inert.
     *
     * @param commandId          registered command identifier; the only field used
     * @param claimedRole        role a client may claim; never consulted
     * @param claimedPermissions permissions a client may claim; never consulted
     */
    public record CommandRequest(String commandId, String claimedRole, List<String> claimedPermissions) {

        /**
         * Normalize the inert claim list.
         */
        public CommandRequest {
            // Claims are never consulted, but a malformed client payload (for example
            // a null element) must not throw before authorize() can record a decision.
            List<String> claims = new ArrayList<>();
            if (claimedPermissions != null) {
                for (String claim : claimedPermissions) {
                    if (claim != null) {
                        claims.add(claim);
                    }
                }
            }
            claimedPermissions = List.copyOf(claims);
        }
    }

    /**
     * Server-derived identity of the current request, never taken from the command body.
     *
     * @param organizationId explicitly selected active tenant
     * @param subject        authenticated subject
     * @param correlationId  correlation identifier of this request
     */
    public record ServerIdentity(String organizationId, String subject, String correlationId) {
    }

    /**
     * Fresh membership fold for one identity.
     *
     * @param activeRoles    roles currently in force in the active organization
     * @param activeMembership whether the subject still has active membership
     */
    public record MemberContext(List<String> activeRoles, boolean activeMembership) {

        /**
         * Defensively copy the freshly resolved role set.
         */
        public MemberContext {
            activeRoles = List.copyOf(Objects.requireNonNull(activeRoles, "activeRoles"));
        }
    }

    /**
     * Auditable OPS-02 authorization outcome for one command.
     *
     * @param commandId     command that was evaluated
     * @param action        action key from the catalog
     * @param organizationId active tenant of the request
     * @param subject       authenticated subject, or empty when unauthenticated
     * @param correlationId correlation identifier, or empty when absent
     * @param allowed       whether the command may proceed
     * @param reasonCode    ALLOWED, UNAUTHENTICATED, INVALID_COMMAND, UNKNOWN_COMMAND,
     *                      MEMBERSHIP_INACTIVE, MEMBERSHIP_UNAVAILABLE, POLICY_UNAVAILABLE or COMMAND_FORBIDDEN
     * @param rolesUsed     freshly resolved roles considered for the decision
     * @param occurredAt    decision instant from the injected clock
     */
    public record Decision(String commandId, String action, String organizationId, String subject,
                           String correlationId, boolean allowed, String reasonCode,
                           List<String> rolesUsed, Instant occurredAt) {

        /**
         * Defensively copy the roles considered.
         */
        public Decision {
            rolesUsed = List.copyOf(Objects.requireNonNull(rolesUsed, "rolesUsed"));
        }
    }

    private final CommandCatalog catalog;
    private final Map<String, ActionRule> rulesByPolicy;
    private final MembershipResolver resolver;
    private final DecisionSink sink;
    private final Clock clock;

    /**
     * Create the authorizer.
     *
     * @param catalog      registry that binds commands to actions and owning policies
     * @param rulesByPolicy owning policies keyed by the catalog policy names
     * @param resolver     re-reads membership on every call
     * @param sink         receives allow and deny decisions
     * @param clock        decision time source
     */
    public CommandAuthorization(CommandCatalog catalog, Map<String, ActionRule> rulesByPolicy,
                                MembershipResolver resolver, DecisionSink sink, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.rulesByPolicy = Map.copyOf(Objects.requireNonNull(rulesByPolicy, "rulesByPolicy"));
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Authorize one command against freshly resolved server state.
     *
     * @param request  client command plus inert claimed fields
     * @param identity server-derived active identity
     * @return the allowed decision
     * @throws ApiException {@code 401} when unauthenticated, {@code 403} when membership is
     *         inactive, the owner is unknown, no active role is permitted or the durable
     *         ledger is unavailable ({@code AUDIT_UNAVAILABLE}), {@code 422} for a malformed
     *         or unknown command, or {@code 500} when the decision cannot be recorded
     */
    public Decision authorize(CommandRequest request, ServerIdentity identity) {
        if (identity == null || !token(identity.organizationId()) || !token(identity.subject())) {
            record("", "", identity, false, "UNAUTHENTICATED", List.of());
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        String commandId = request == null ? null : request.commandId();
        if (!token(commandId)) {
            record("", "", identity, false, "INVALID_COMMAND", List.of());
            throw new ApiException(422, "INVALID_COMMAND", "command identifier is required");
        }
        CommandDefinition definition = catalog.find(commandId).orElse(null);
        if (definition == null) {
            // Bound untrusted evidence before it reaches any DecisionSink. The V65 sink
            // persists only the action and fixed discriminator, but injected sinks must
            // not receive an oversized or control-character-bearing command identifier.
            record(auditableCommandId(commandId), "", identity, false, "UNKNOWN_COMMAND", List.of());
            throw new ApiException(422, "UNKNOWN_COMMAND", "command is not in the catalog");
        }
        MemberContext member;
        try {
            member = resolver.resolve(identity);
        } catch (RuntimeException failure) {
            record(commandId, definition.action(), identity, false, "MEMBERSHIP_UNAVAILABLE", List.of());
            throw new ApiException(403, "FORBIDDEN", "membership could not be resolved");
        }
        if (member == null) {
            record(commandId, definition.action(), identity, false, "MEMBERSHIP_UNAVAILABLE", List.of());
            throw new ApiException(403, "FORBIDDEN", "membership could not be resolved");
        }
        if (!member.activeMembership()) {
            record(commandId, definition.action(), identity, false, "MEMBERSHIP_INACTIVE", member.activeRoles());
            throw new ApiException(403, "FORBIDDEN", "no active membership");
        }
        List<String> roles = member.activeRoles();
        ActionRule owner = rulesByPolicy.get(definition.policy());
        if (owner == null) {
            record(commandId, definition.action(), identity, false, "POLICY_UNAVAILABLE", roles);
            throw new ApiException(403, "FORBIDDEN", "command owner is not registered");
        }
        boolean allowed;
        try {
            allowed = permits(owner, definition.action(), roles);
        } catch (RuntimeException failure) {
            record(commandId, definition.action(), identity, false, "POLICY_UNAVAILABLE", roles);
            throw new ApiException(403, "FORBIDDEN", "command owner could not decide");
        }
        Decision decision = record(commandId, definition.action(), identity, allowed,
                allowed ? "ALLOWED" : "COMMAND_FORBIDDEN", roles);
        if (!allowed) {
            throw new ApiException(403, "FORBIDDEN", "command is not permitted for the active roles");
        }
        return decision;
    }

    /**
     * Record one decision before the caller is answered.
     *
     * @param commandId  evaluated command
     * @param action     evaluated action
     * @param identity   server identity
     * @param allowed    whether the command proceeds
     * @param reasonCode stable reason code
     * @param roles      roles considered
     * @return the recorded decision
     * @throws ApiException {@code 403 AUDIT_UNAVAILABLE} when the durable sink rejects the
     *         write, {@code 500 AUDIT_UNAVAILABLE} for an unstructured sink failure, or
     *         {@code 500 DECISION_NOT_RECORDED} for any other sink-supplied API failure
     */
    private Decision record(String commandId, String action, ServerIdentity identity, boolean allowed,
                            String reasonCode, List<String> roles) {
        Decision decision = new Decision(commandId, action, value(identity, ServerIdentity::organizationId),
                value(identity, ServerIdentity::subject), value(identity, ServerIdentity::correlationId),
                allowed, reasonCode, roles, clock.instant());
        try {
            sink.record(decision);
        } catch (ApiException failure) {
            // Only the durable sink's own deny signal may reach the caller unchanged,
            // and even that signal uses a fixed message so SQL, bind or tenant details cannot leak.
            if (failure.status() == 403 && "AUDIT_UNAVAILABLE".equals(failure.code())) {
                throw new ApiException(403, "AUDIT_UNAVAILABLE", "authorization decision could not be recorded");
            }
            throw new ApiException(500, "DECISION_NOT_RECORDED", "authorization decision could not be recorded");
        } catch (RuntimeException failure) {
            // A failed audit write must fail the command closed with a defined,
            // machine-readable reason: AUDIT_UNAVAILABLE is itself a deny outcome,
            // not a licence to proceed without evidence.
            throw new ApiException(500, "AUDIT_UNAVAILABLE", "authorization decision could not be recorded");
        }
        return decision;
    }

    /**
     * Normalize a possibly absent server identity field for the audit record.
     *
     * @param identity server identity
     * @param field    field to read
     * @return the field value or an empty string
     */
    private static String value(ServerIdentity identity, java.util.function.Function<ServerIdentity, String> field) {
        if (identity == null) {
            return "";
        }
        String result = field.apply(identity);
        return result == null ? "" : result;
    }

    /**
     * Require a non-blank server value.
     *
     * @param value value to check
     * @return {@code true} when the value is present and non-blank
     */
    private static boolean token(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Bound an untrusted command identifier before passing it to a decision sink.
     *
     * <p>The value is only evidence for an {@code UNKNOWN_COMMAND} denial; it never selects
     * a policy or a role. The V65 ledger stores only the action and fixed discriminator,
     * but injected sinks must not receive unbounded or control-character-bearing text.
     * Truncation and control-character removal keep the 422 outcome unchanged.</p>
     *
     * @param commandId client-supplied identifier, already checked non-blank
     * @return a bounded, control-character-free decision value
     */
    private static String auditableCommandId(String commandId) {
        String bounded = commandId.trim();
        if (bounded.length() > 128) {
            bounded = bounded.substring(0, 128);
        }
        return bounded.replaceAll("\\p{Cntrl}", "");
    }

    /**
     * Ask only the owning policy, so a colliding action in another policy cannot widen access.
     *
     * @param owner  policy that owns the command action
     * @param action action key from the catalog
     * @param roles  freshly resolved active roles
     * @return {@code true} on the first explicit allow
     */
    private static boolean permits(ActionRule owner, String action, List<String> roles) {
        for (String role : roles) {
            if (owner.allows(action, role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wire the delivered domain policies as owning rules, keyed by catalog policy name.
     *
     * @param catalog  registry of protected commands
     * @param resolver membership resolver supplied by the caller
     * @param sink     decision sink supplied by the caller
     * @param clock    decision time source
     * @return authorizer backed by the existing policy classes
     */
    public static CommandAuthorization synthetic(CommandCatalog catalog, MembershipResolver resolver,
                                                 DecisionSink sink, Clock clock) {
        Map<String, ActionRule> owners = new LinkedHashMap<>();
        owners.put(CommandCatalog.ACTION_POLICY, new ActionPolicy()::allows);
        owners.put(CommandCatalog.WORKLIST_POLICY, new WorklistPolicy()::allows);
        owners.put(CommandCatalog.LAB_POLICY, new LabActionPolicy()::allows);
        owners.put(CommandCatalog.SPECIALTY_POLICY, new SpecialtyActions()::allows);
        owners.put(CommandCatalog.EXCHANGE_RESPONSE_POLICY, new ExchangeResponseActionPolicy()::allows);
        owners.put(CommandCatalog.RELEASE_POLICY, new ReleaseActionPolicy()::allows);
        owners.put(CommandCatalog.CRITICAL_POLICY, new CriticalActions()::allows);
        owners.put(CommandCatalog.FINANCE_POLICY, new FinanceActions()::allows);
        owners.put(CommandCatalog.INSTRUCTION_POLICY, new InstructionFactActions()::allows);
        return new CommandAuthorization(catalog, owners, resolver, sink, clock);
    }
}
