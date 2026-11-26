package com.aurevia.api.authorization;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
 * Immutable registry of every protected command in this slice (PRD-06, ARC-03).
 *
 * <p>A command that is not in the catalog cannot be authorized, so adding a new
 * explicit POST command requires an entry with an existing action key. The
 * catalog never decides roles: it only binds a command to the action and the
 * owning policy, both of which are emitted into the generated contract and
 * client so a missing or stale entry is visible in review.</p>
 */
public final class CommandCatalog {

    /** G0 tenant administration and registration commands owned by the shared policy. */
    public static final String ACTION_POLICY = "ActionPolicy";
    /** Check-in and worklist commands owned by the CLN-02 policy. */
    public static final String WORKLIST_POLICY = "WorklistPolicy";
    /** Laboratory accession and collection commands owned by the LAB-01 policy. */
    public static final String LAB_POLICY = "LabActionPolicy";
    /** Specialty verification, signing and attestation commands. */
    public static final String SPECIALTY_POLICY = "SpecialtyActions";
    /**
     * Recipient order response commands owned by the exchange-response policy.
     *
     * <p>The value is a published {@code x-aurevia-policy} identifier in the
     * generated OpenAPI contract (kept stable for contract/rollback compatibility,
     * like the {@code /api/v1/xch03/orders} route). The owning Java class is
     * {@link com.aurevia.exchange.response.ExchangeResponseActionPolicy};
     * the wire identifier is deliberately retained as documented debt.</p>
     */
    public static final String EXCHANGE_RESPONSE_POLICY = "Xch03ActionPolicy";
    /** Patient release decisions owned by the XCH-06 policy. */
    public static final String RELEASE_POLICY = "ReleaseActionPolicy";
    /** Critical-result workflow commands owned by the XCH-05 policy. */
    public static final String CRITICAL_POLICY = "CriticalActions";
    /** Self-pay finance commands owned by the FIN-01 policy. */
    public static final String FINANCE_POLICY = "FinanceActions";
    /** Patient instruction facts owned by the MSG-05 policy. */
    public static final String INSTRUCTION_POLICY = "InstructionFactActions";

    /** A route variable such as {@code {id}}; variable names are not part of route identity. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/{}]+\\}");

    private final List<CommandDefinition> commands;
    private final Map<String, CommandDefinition> byId;
    private final Map<String, CommandDefinition> byRoute;

    /**
     * Build a catalog with stable insertion order and no duplicate identifiers or routes.
     *
     * @param commands definitions to register
     * @throws IllegalArgumentException when two definitions share an identifier, a path or a
     *         normalized method-and-route key
     */
    public CommandCatalog(List<CommandDefinition> commands) {
        Objects.requireNonNull(commands, "commands");
        Map<String, CommandDefinition> index = new LinkedHashMap<>();
        Map<String, CommandDefinition> routesByIdentity = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        for (CommandDefinition definition : commands) {
            if (index.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("duplicate command id: " + definition.id());
            }
            // One OpenAPI path key per route; a duplicate would silently drop an operation.
            if (!paths.add(definition.path())) {
                throw new IllegalArgumentException("duplicate command path: " + definition.path());
            }
            String route = route(definition.method(), definition.path());
            if (routesByIdentity.putIfAbsent(route, definition) != null) {
                throw new IllegalArgumentException("duplicate command route: " + route);
            }
        }
        this.commands = List.copyOf(commands);
        this.byId = Map.copyOf(index);
        this.byRoute = Map.copyOf(routesByIdentity);
    }

    /**
     * Return every registered command in declaration order.
     *
     * @return immutable command list
     */
    public List<CommandDefinition> commands() {
        return commands;
    }

    /**
     * Look up a command without failing.
     *
     * @param id wire command identifier
     * @return the definition, or empty when the command is not registered
     */
    public Optional<CommandDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Require a registered command.
     *
     * @param id wire command identifier
     * @return the registered definition
     * @throws ApiException {@code 422 UNKNOWN_COMMAND} when the command is not registered
     */
    public CommandDefinition require(String id) {
        CommandDefinition definition = byId.get(id);
        if (definition == null) {
            throw new ApiException(422, "UNKNOWN_COMMAND", "command is not in the catalog");
        }
        return definition;
    }

    /**
     * Resolve the command bound to a matched HTTP route.
     *
     * <p>Only the HTTP method and the Spring route template are compared. Path variable names
     * are normalized because a handler may name a parameter differently from the documented
     * catalog entry; the concrete request path is never used, so identifiers in the URI cannot
     * select a command. A blank or absent input has no match and fails closed at the caller.</p>
     *
     * @param method HTTP method of the matched handler
     * @param path   matched route template, for example {@code /api/v1/orders/{id}/accept}
     * @return the owning definition, or empty when the route is not registered
     */
    public Optional<CommandDefinition> findByPath(String method, String path) {
        if (method == null || path == null || method.isBlank() || path.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byRoute.get(route(method, path)));
    }

    /**
     * Return every POST route template the live HTTP gate registers, in declaration order.
     *
     * <p>Definitions that only bind a delivered handler are deliberately excluded:
     * adding a handler-to-command mapping must not silently gate a route whose
     * owning service has not been reviewed for the decision ledger. The full set,
     * including mapping-only entries, is available through {@code commands()}.</p>
     *
     * @return immutable route templates used to register the single command gate
     */
    public List<String> paths() {
        return commands.stream().filter(CommandDefinition::gated).map(CommandDefinition::path).toList();
    }

    /**
     * Return the distinct action keys named by the catalog.
     *
     * @return immutable, declaration-ordered action keys
     */
    public List<String> actions() {
        return commands.stream().map(CommandDefinition::action).distinct().toList();
    }

    /**
     * Build the route identity of a method and template, ignoring variable names.
     *
     * @param method HTTP method, already checked non-blank by the caller
     * @param path   route template, already checked non-blank by the caller
     * @return stable {@code METHOD /template} key
     */
    private static String route(String method, String path) {
        return method.trim() + " " + PATH_VARIABLE.matcher(path.trim()).replaceAll("{}");
    }

    /**
     * Build the synthetic catalog used by this slice.
     *
     * <p>The action keys are the constants of the named policies; no role set
     * is restated here. Every ARC-03 explicit command family is present: sign,
     * correct, acknowledge, release and exchange, alongside the delivered G0
     * administrative and clinical commands. The published baseline routes are
     * kept for contract compatibility; the appended definitions bind delivered
     * Spring handler templates to their commands. Those mapping-only entries
     * are not registered by the live gate until their owning services have been
     * reviewed for decision-ledger enforcement.</p>
     *
     * @return the synthetic command catalog
     */
    public static CommandCatalog synthetic() {
        return new CommandCatalog(List.of(
                new CommandDefinition("createFacility", "FACILITY_WRITE", "POST",
                        "/api/v1/facilities", ACTION_POLICY),
                new CommandDefinition("inviteStaff", "STAFF_INVITE", "POST",
                        "/api/v1/staff", ACTION_POLICY),
                new CommandDefinition("deactivateStaff", "STAFF_DEACTIVATE", "POST",
                        "/api/v1/staff/{subject}/deactivate", ACTION_POLICY),
                new CommandDefinition("createConfiguration", "CONFIG_CREATE", "POST",
                        "/api/v1/configuration", ACTION_POLICY),
                new CommandDefinition("approveConfiguration", "CONFIG_APPROVE", "POST",
                        "/api/v1/configuration/{key}/versions/{version}/approve", ACTION_POLICY),
                new CommandDefinition("registerPatient", "PATIENT_REGISTER", "POST",
                        "/api/v1/patients", ACTION_POLICY),
                new CommandDefinition("bookAppointment", "APPOINTMENT_BOOK", "POST",
                        "/api/v1/appointments", ACTION_POLICY),
                new CommandDefinition("verifyIdentity", WorklistPolicy.IDENTITY_VERIFY, "POST",
                        "/api/v1/checkin/{id}/identity", WORKLIST_POLICY),
                new CommandDefinition("checkIn", WorklistPolicy.CHECK_IN, "POST",
                        "/api/v1/checkin/{id}/complete", WORKLIST_POLICY),
                new CommandDefinition("assignWorkItem", WorklistPolicy.WORKLIST_ASSIGN, "POST",
                        "/api/v1/worklist/{id}/assign", WORKLIST_POLICY),
                new CommandDefinition("accessionRequisition", LabActionPolicy.ACCESSION_CREATE, "POST",
                        "/api/v1/lab/accessions", LAB_POLICY),
                new CommandDefinition("collectSpecimen", LabActionPolicy.SPECIMEN_COLLECT, "POST",
                        "/api/v1/lab/specimens/{id}/collect", LAB_POLICY),
                new CommandDefinition("verifyLabResult", SpecialtyActions.LAB_VERIFY_RELEASE, "POST",
                        "/api/v1/lab/results/{id}/verify", SPECIALTY_POLICY),
                new CommandDefinition("signInterpretation", SpecialtyActions.RAD_SIGN_INTERPRETATION, "POST",
                        "/api/v1/radiology/interpretations/{id}/sign", SPECIALTY_POLICY),
                new CommandDefinition("acceptTherapyReferral", SpecialtyActions.PT_ACCEPT_REFERRAL, "POST",
                        "/api/v1/therapy/referrals/{id}/accept", SPECIALTY_POLICY),
                new CommandDefinition("attestClinicalDecision", SpecialtyActions.ATTEST, "POST",
                        "/api/v1/specialty/attestations", SPECIALTY_POLICY),
                new CommandDefinition("acceptOrder", ExchangeResponseActionPolicy.EXCHANGE_ACCEPT, "POST",
                        "/api/v1/orders/{id}/accept", EXCHANGE_RESPONSE_POLICY),
                new CommandDefinition("rejectOrder", ExchangeResponseActionPolicy.EXCHANGE_REJECT, "POST",
                        "/api/v1/orders/{id}/reject", EXCHANGE_RESPONSE_POLICY),
                new CommandDefinition("authorizeRelease", ReleaseActionPolicy.AUTHORIZE, "POST",
                        "/api/v1/releases/{id}/authorize", RELEASE_POLICY),
                new CommandDefinition("correctRelease", ReleaseActionPolicy.CORRECT, "POST",
                        "/api/v1/releases/{id}/correct", RELEASE_POLICY),
                new CommandDefinition("withdrawRelease", ReleaseActionPolicy.WITHDRAW, "POST",
                        "/api/v1/releases/{id}/withdraw", RELEASE_POLICY),
                new CommandDefinition("acknowledgeCriticalResult", CriticalActions.ACKNOWLEDGE, "POST",
                        "/api/v1/critical/{id}/acknowledge", CRITICAL_POLICY),
                new CommandDefinition("issueInvoice", FinanceActions.CREATE, "POST",
                        "/api/v1/invoices", FINANCE_POLICY),
                new CommandDefinition("acknowledgeInstruction", InstructionFactActions.ACKNOWLEDGE, "POST",
                        "/api/v1/instructions/{id}/acknowledge", INSTRUCTION_POLICY),
                // Handler-to-command bindings for delivered POST routes. The owning
                // controller method is the only authority for the transition; these
                // entries make its route resolvable to a command, not enforce it here.
                new CommandDefinition("verifyClinicIdentity", WorklistPolicy.IDENTITY_VERIFY, "POST",
                        "/api/v1/clinic/verifications", WORKLIST_POLICY, false),
                new CommandDefinition("checkInClinicAppointment", WorklistPolicy.CHECK_IN, "POST",
                        "/api/v1/clinic/appointments/{id}/check-in", WORKLIST_POLICY, false),
                new CommandDefinition("assignClinicWorkItem", WorklistPolicy.WORKLIST_ASSIGN, "POST",
                        "/api/v1/clinic/worklist/{id}/assign", WORKLIST_POLICY, false),
                new CommandDefinition("acceptTherapyEpisode", SpecialtyActions.PT_ACCEPT_REFERRAL, "POST",
                        "/api/v1/therapy/episodes", SPECIALTY_POLICY, false),
                new CommandDefinition("acceptExchangeResponse", ExchangeResponseActionPolicy.EXCHANGE_ACCEPT,
                        "POST", "/api/v1/xch03/orders/{id}/accept", EXCHANGE_RESPONSE_POLICY, false),
                new CommandDefinition("rejectExchangeResponse", ExchangeResponseActionPolicy.EXCHANGE_REJECT,
                        "POST", "/api/v1/xch03/orders/{id}/reject", EXCHANGE_RESPONSE_POLICY, false),
                new CommandDefinition("authorizePatientRelease", ReleaseActionPolicy.AUTHORIZE, "POST",
                        "/api/v1/releases", RELEASE_POLICY, false),
                new CommandDefinition("acknowledgeReportedCriticalResult", CriticalActions.ACKNOWLEDGE, "POST",
                        "/api/v1/critical-results/{id}/acknowledge", CRITICAL_POLICY, false),
                new CommandDefinition("createFinanceInvoice", FinanceActions.CREATE, "POST",
                        "/api/v1/finance/invoices", FINANCE_POLICY, false),
                new CommandDefinition("acknowledgePublishedInstruction", InstructionFactActions.ACKNOWLEDGE,
                        "POST", "/api/v1/instructions/acknowledge", INSTRUCTION_POLICY, false)));
    }
}
