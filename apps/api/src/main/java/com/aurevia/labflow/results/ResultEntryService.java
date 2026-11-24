package com.aurevia.labflow.results;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.critical.CriticalContract.RegisterRequest;
import com.aurevia.exchange.critical.CriticalResultService;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabInputs;
import com.aurevia.lab.result.LabCommands.CaptureCommand;
import com.aurevia.lab.result.LabCommands.CorrectCommand;
import com.aurevia.lab.result.LabCommands.ErrorCommand;
import com.aurevia.lab.result.LabCommands.VerifyCommand;
import com.aurevia.lab.result.LabObservation;
import com.aurevia.lab.result.LabPolicyCatalog.CriticalPolicy;
import com.aurevia.lab.result.LabPolicyCatalog.ReferenceRange;
import com.aurevia.lab.result.LabResultService;
import com.aurevia.lab.result.LabRules;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;
import com.aurevia.lab.result.LabTypes.VerificationState;
import com.aurevia.lab.result.ReportRevision;
import com.aurevia.tenant.TenantContext;

/**
 * LAB-02 result entry, verification and revision invariants.
 *
 * <p>Observations are parsed and flagged against the site's approved reference
 * and critical bounds before a transaction opens. Inside one tenant-guarded unit
 * of work the service re-checks membership and duty, repeats the patient and
 * accession label match, delegates revision and state transitions to the
 * existing laboratory kernel, and starts the durable XCH-05 workflow for every
 * critical finding. The verifier must be a different authenticated subject from
 * the enterer, so one person cannot capture and confirm the same report.
 * Accession and audit reads and writes run under forced row-level security, while
 * report revisions are additionally keyed by the active organization in the
 * kernel, so a peer tenant's report is concealed as not found. The XCH-05
 * alert is registered once on first verification; a correction must not create
 * a duplicate alert for the same finding.</p>
 *
 * <p>Invariant: a refusal that must leave an audit row returns {@code null} from
 * the guarded callback and throws the deferred failure after commit; a refusal
 * that writes no audit may throw inside the callback. Never append an audit row
 * immediately before an in-callback throw — the rollback would discard it.</p>
 */
@Service
public class ResultEntryService {

    /** Audit purpose stamped on every result command. */
    public static final String PURPOSE = "LAB_RESULT_ENTRY";
    /** Audit action for observation capture. */
    public static final String ENTER = "LAB_RESULT_ENTER";
    /** Audit action for verification. */
    public static final String VERIFY = "LAB_RESULT_VERIFY";
    /** Audit action for correction. */
    public static final String CORRECT = "LAB_RESULT_CORRECT";
    /** Audit action for withdrawal. */
    public static final String ERROR = "LAB_RESULT_ERROR";
    /** Audit action for report reads. */
    public static final String READ = "LAB_RESULT_READ";

    private static final String RESOURCE_TYPE = "LAB_REPORT";
    private static final String SCOPE = "LAB";
    private static final String MISMATCH_OUTCOME = "MISMATCH";
    private static final String SOURCE_SYSTEM = "LAB";
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:/-]{1,32}");
    private static final Pattern TEXT = Pattern.compile("[\\p{L}\\p{M}0-9 '.,/:@_<>=-]{1,200}");
    private static final int DEFAULT_POLICY_VERSION = 1;
    private static final Duration DEFAULT_DUE_AFTER = Duration.ofMinutes(30);

    private final LabAccessionRepository accessions;
    private final LabResultService reports;
    private final CriticalResultService criticalResults;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param accessions      RLS-bounded accession repository
     * @param reports         append-only laboratory report kernel
     * @param criticalResults XCH-05 critical-result workflow
     * @param transactions    tenant-guarded transaction template
     * @param audit           transactional audit appender
     * @param clock           application clock for effective time
     */
    public ResultEntryService(LabAccessionRepository accessions, LabResultService reports,
                              CriticalResultService criticalResults, TransactionTemplate transactions,
                              AuditAppender audit, Clock clock) {
        this.accessions = accessions;
        this.reports = reports;
        this.criticalResults = criticalResults;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Capture observations as a preliminary report after a successful label match.
     *
     * @param id      opaque specimen identifier
     * @param request bounded capture command
     * @return the preliminary report projection
     * @throws ApiException on invalid input, denial, concealment, mismatch, stale version or illegal state
     */
    public ReportView enter(String id, ResultEntryRequest request) {
        String specimenId = LabInputs.id(id);
        String patientId = LabInputs.patientId(request.patientId());
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        int expectedVersion = LabInputs.version(request.expectedVersion());
        List<ObservationInput> inputs = request.observations();
        if (inputs.isEmpty()) {
            throw new ApiException(422, "NO_OBSERVATIONS", "at least one observation is required");
        }
        String owner = bounded(request.owner(), TEXT, "INVALID_OWNER", "owner is required");
        String queue = bounded(request.queue(), TEXT, "INVALID_QUEUE", "queue is required");
        String escalation = bounded(request.escalation(), TEXT, "INVALID_ESCALATION", "escalation is required");
        Duration dueAfter = dueAfter(request.dueAfter());
        Instant at = clock.instant();
        List<LabObservation> observations = parse(inputs, owner, queue, escalation, dueAfter, at);

        AtomicReference<ApiException> failure = new AtomicReference<>();
        ReportRevision result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ENTER, specimenId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession current = accessions.findById(specimenId).orElse(null);
            if (current == null) {
                audit.append(context, PURPOSE, ENTER, RESOURCE_TYPE, SCOPE, specimenId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "specimen not found"));
                return null;
            }
            if (!matches(current, patientId, number, issuer)) {
                audit.append(context, PURPOSE, ENTER, RESOURCE_TYPE, SCOPE, specimenId, MISMATCH_OUTCOME);
                failure.set(new ApiException(422, "PATIENT_ACCESSION_MISMATCH",
                        "patient and accession identity do not match the stored requisition"));
                return null;
            }
            if (current.version() != expectedVersion) {
                throw staleVersion();
            }
            if (current.state() != AccessionState.COLLECTED) {
                throw new ApiException(409, "INVALID_STATE", "only a collected specimen can receive results");
            }
            CaptureCommand command = new CaptureCommand(context.organizationId(), context.subject(),
                    Set.copyOf(accessions.activeRoles(context.subject())), specimenId, patientId, specimenId,
                    current.sourceOrderRef(), observations);
            ReportRevision revision;
            synchronized (reports) {
                revision = reports.capture(command);
            }
            audit.append(context, PURPOSE, ENTER, RESOURCE_TYPE, SCOPE, specimenId, "SUCCESS");
            return revision;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view(result);
    }

    /**
     * Verify a preliminary report as a different authenticated subject and issue it as final.
     *
     * @param id      opaque report identifier (the specimen identifier)
     * @param request bounded verification command
     * @return the issued final projection
     * @throws ApiException on invalid input, denial, concealment, mismatch, self-verification,
     *                      stale version or illegal state
     */
    public ReportView verify(String id, ResultDecisionRequest request) {
        return decide(id, request, VERIFY, true, false);
    }

    /**
     * Append a linked corrected revision that preserves and supersedes the issued one.
     *
     * @param id      opaque report identifier
     * @param request bounded correction command
     * @return the corrected projection
     * @throws ApiException on denial, concealment, mismatch, stale version or illegal state
     */
    public ReportView correct(String id, ResultDecisionRequest request) {
        return decide(id, request, CORRECT, false, true);
    }

    /**
     * Append a terminal entered-in-error revision that withdraws the current one.
     *
     * @param id      opaque report identifier
     * @param request bounded withdrawal command
     * @return the terminal projection
     * @throws ApiException on denial, concealment, mismatch, stale version or illegal state
     */
    public ReportView enterInError(String id, ResultDecisionRequest request) {
        return decide(id, request, ERROR, false, true);
    }

    /**
     * Read the current revision of a report in the active tenant.
     *
     * @param id opaque report identifier
     * @return the current projection
     * @throws ApiException on denial or concealment
     */
    public ReportView read(String id) {
        String reportId = LabInputs.id(id);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ReportRevision result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, READ, reportId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ReportRevision current;
            synchronized (reports) {
                current = reports.read(context.organizationId(), reportId);
            }
            audit.append(context, PURPOSE, READ, RESOURCE_TYPE, SCOPE, reportId, "SUCCESS");
            return current;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view(result);
    }

    private ReportView decide(String id, ResultDecisionRequest request, String action,
                              boolean requirePreliminary, boolean reasonRequired) {
        String reportId = LabInputs.id(id);
        String patientId = LabInputs.patientId(request.patientId());
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        int expectedVersion = LabInputs.version(request.expectedVersion());
        final String owner = ERROR.equals(action) ? null
                : bounded(request.owner(), TEXT, "OWNER_REQUIRED", "a named owner is required");
        final String queue = ERROR.equals(action) ? null
                : bounded(request.queue(), TEXT, "QUEUE_REQUIRED", "a coverage queue is required");
        final String reason = reasonRequired
                ? bounded(request.reason(), TEXT, "REASON_REQUIRED", "a reason is required") : null;
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ReportRevision result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, action, reportId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession specimen = accessions.findById(reportId).orElse(null);
            if (specimen == null) {
                audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, reportId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "specimen not found"));
                return null;
            }
            if (!matches(specimen, patientId, number, issuer)) {
                audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, reportId, MISMATCH_OUTCOME);
                failure.set(new ApiException(422, "PATIENT_ACCESSION_MISMATCH",
                        "patient and accession identity do not match the stored requisition"));
                return null;
            }
            ReportRevision current;
            String enterer;
            synchronized (reports) {
                current = reports.read(context.organizationId(), reportId);
                // The first immutable revision names the original enterer; later
                // revisions carry the verifier, so they cannot prove segregation.
                enterer = reports.revisions(context.organizationId(), reportId).get(0).actor();
            }
            if (current.version() != expectedVersion) {
                throw staleVersion();
            }
            if (requirePreliminary && current.state() != VerificationState.PRELIMINARY) {
                audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, reportId, "CONFLICT");
                failure.set(new ApiException(409, "INVALID_STATE", "only a preliminary report can be verified"));
                return null;
            }
            String actor = context.subject();
            if (!ERROR.equals(action) && actor.equals(enterer)) {
                audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, reportId, "CONFLICT");
                failure.set(new ApiException(422, "SELF_VERIFICATION",
                        "the enterer cannot verify or correct their own report"));
                return null;
            }
            ReportRevision revision;
            synchronized (reports) {
                Set<String> roles = Set.copyOf(accessions.activeRoles(actor));
                if (VERIFY.equals(action)) {
                    revision = reports.verify(new VerifyCommand(context.organizationId(), actor, roles,
                            reportId, expectedVersion, patientId, reportId, current.orderId(),
                            actor, owner, queue));
                } else if (CORRECT.equals(action)) {
                    revision = reports.correct(new CorrectCommand(context.organizationId(), actor, roles,
                            reportId, expectedVersion, patientId, reportId, current.orderId(),
                            actor, owner, queue, reason));
                } else {
                    revision = reports.enterInError(new ErrorCommand(context.organizationId(), actor,
                            roles, reportId, expectedVersion, reason));
                }
            }
            // Only first issuance registers an alert: CriticalResultService has no
            // source-level deduplication, so registering again on correction would
            // create a second XCH-05 record for the same finding.
            if (VERIFY.equals(action)) {
                for (LabObservation observation : revision.observations()) {
                    if (observation.abnormalFlag() == AbnormalFlag.CRITICAL) {
                        criticalResults.register(
                                new RegisterRequest(patientId, SOURCE_SYSTEM, reportId,
                                        current.orderId(), specimen.accessionNo(),
                                        observation.code() + " " + observation.display(),
                                        observation.qualifier().symbol() + observation.value().toPlainString(),
                                        observation.unit(), false));
                    }
                }
            }
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, reportId, "SUCCESS");
            return revision;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view(result);
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!accessions.hasActiveMembership(context.subject())) {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (!allows(action, Set.copyOf(accessions.activeRoles(context.subject())))) {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing laboratory permission");
        }
        return null;
    }

    private static boolean allows(String action, Set<String> roles) {
        return switch (action) {
            case ENTER -> roles.contains(LabRules.COLLECTOR);
            case READ -> roles.contains(LabRules.COLLECTOR) || roles.contains(LabRules.VERIFIER);
            default -> roles.contains(LabRules.VERIFIER);
        };
    }

    private static boolean matches(LabAccession current, String patientId, String number, String issuer) {
        return current.patientId().equals(patientId)
                && current.accessionNo().equals(number)
                && current.accessionIssuer().equals(issuer);
    }

    private static ApiException staleVersion() {
        return new ApiException(412, "STALE_VERSION", "the report changed since it was read");
    }

    private static List<LabObservation> parse(List<ObservationInput> inputs, String owner, String queue,
                                              String escalation, Duration dueAfter, Instant at) {
        List<LabObservation> parsed = new ArrayList<>();
        for (ObservationInput input : inputs) {
            String code = bounded(input.code(), CODE, "INVALID_OBSERVATION_CODE", "observation code is malformed");
            ObservationKind kind = enumValue(ObservationKind.class, input.kind(), "INVALID_OBSERVATION_KIND");
            ObservationPresence presence = enumValue(ObservationPresence.class, input.presence(),
                    "INVALID_OBSERVATION_PRESENCE");
            Qualifier qualifier = input.qualifier() == null
                    ? Qualifier.NONE : enumValue(Qualifier.class, input.qualifier(), "INVALID_QUALIFIER");
            BigDecimal value = decimal(input.value(), "INVALID_VALUE");
            String unit = optionalText(input.unit(), CODE, "INVALID_UNIT");
            String textValue = optionalText(input.textValue(), TEXT, "INVALID_TEXT_VALUE");
            String codedValue = optionalText(input.codedValue(), CODE, "INVALID_CODED_VALUE");
            String method = optionalText(input.method(), TEXT, "INVALID_METHOD");
            String instrument = optionalText(input.instrument(), TEXT, "INVALID_INSTRUMENT");
            AbnormalFlag flag = flag(input, kind, presence, value, code, unit, owner, queue,
                    escalation, dueAfter, at);
            parsed.add(new LabObservation(code, kind, presence, value, qualifier, unit, textValue,
                    codedValue, method, flag, instrument, null));
        }
        return List.copyOf(parsed);
    }

    private static AbnormalFlag flag(ObservationInput input, ObservationKind kind,
                                     ObservationPresence presence, BigDecimal value, String code,
                                     String unit, String owner, String queue, String escalation,
                                     Duration dueAfter, Instant at) {
        AbnormalFlag flag = AbnormalFlag.NONE;
        if (input.referenceLow() == null || input.referenceHigh() == null) {
            return flag;
        }
        ReferenceRange range = new ReferenceRange(code, unit, decimal(input.referenceLow(),
                "INVALID_REFERENCE_LOW"), decimal(input.referenceHigh(), "INVALID_REFERENCE_HIGH"),
                DEFAULT_POLICY_VERSION, true, at, null);
        if (presence == ObservationPresence.PRESENT && kind == ObservationKind.NUMERIC && value != null) {
            flag = range.flag(value);
        }
        if (input.criticalLow() != null && input.criticalHigh() != null
                && presence == ObservationPresence.PRESENT && kind == ObservationKind.NUMERIC
                && value != null) {
            CriticalPolicy policy = new CriticalPolicy(code, DEFAULT_POLICY_VERSION,
                    decimal(input.criticalLow(), "INVALID_CRITICAL_LOW"),
                    decimal(input.criticalHigh(), "INVALID_CRITICAL_HIGH"), true, at, null,
                    owner, queue, dueAfter, escalation);
            if (policy.isCritical(value)) {
                flag = AbnormalFlag.CRITICAL;
            }
        }
        return flag;
    }

    private static String bounded(String value, Pattern pattern, String code, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new ApiException(422, code, message);
        }
        return value;
    }

    private static String optionalText(String value, Pattern pattern, String code) {
        if (value == null) {
            return null;
        }
        return bounded(value, pattern, code, code + " is malformed");
    }

    private static BigDecimal decimal(String value, String code) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new ApiException(422, code, code + " must be a decimal number");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String code) {
        String normalized = Objects.requireNonNullElse(value, "").trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, code, code + " is not a supported value");
        }
    }

    private static Duration dueAfter(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_DUE_AFTER;
        }
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_DUE_AFTER", "dueAfter must be an ISO-8601 duration");
        }
    }

    private static ReportView view(ReportRevision revision) {
        List<ReportView.ObservationView> observations = new ArrayList<>();
        for (LabObservation observation : revision.observations()) {
            observations.add(new ReportView.ObservationView(observation.code(), observation.kind().wire(),
                    observation.presence().wire(),
                    observation.value() == null ? null : observation.value().toPlainString(),
                    observation.qualifier().symbol(), observation.unit(), observation.textValue(),
                    observation.codedValue(), observation.method(), observation.instrument(),
                    observation.abnormalFlag().wire()));
        }
        return new ReportView(revision.reportId(), revision.version(), revision.supersedesVersion(),
                revision.state().wire(), revision.patientId(), revision.specimenId(), revision.orderId(),
                revision.actor(), revision.verifier(), revision.effectiveAt(), revision.reason(),
                observations);
    }
}
