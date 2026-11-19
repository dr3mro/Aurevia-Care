package com.aurevia.smartclinic.exchange.result;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.result.ResultCommands.ImportResultRequest;
import com.aurevia.smartclinic.exchange.result.ResultCommands.RegisterOrderRequest;
import com.aurevia.smartclinic.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.smartclinic.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.smartclinic.exchange.result.ResultModel.MatchDecision;
import com.aurevia.smartclinic.exchange.result.ResultModel.MatchStatus;
import com.aurevia.smartclinic.exchange.result.ResultModel.RecipientNotification;
import com.aurevia.smartclinic.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.smartclinic.exchange.result.ResultModel.ReconciliationReason;
import com.aurevia.smartclinic.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultDetail;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultKind;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultReport;
import com.aurevia.smartclinic.exchange.result.ResultModel.Review;
import com.aurevia.smartclinic.exchange.result.ResultModel.ReviewState;
import com.aurevia.smartclinic.exchange.result.ResultModel.Routing;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * XCH-04 result routing, matching, reconciliation and correction commands.
 *
 * <p>Each command runs in one tenant-guarded transaction: authorization is
 * re-derived from active membership and roles, identifiers are matched before
 * any import, the immutable revision, its review task, notifications and the
 * audit event commit together. Corrections append a new revision and a new
 * unreviewed task; the acknowledged prior revision and its attestation are
 * never rewritten. Critical is a routing marker only - due-time escalation is
 * the separate XCH-05 story.</p>
 */
@Service
public class ResultRoutingService {

    private static final String PURPOSE = "XCH04_RESULT_ROUTING";
    private static final String SCOPE = "EXCHANGE";
    private static final String RESOURCE_TYPE = "RESULT_REPORT";
    private static final String RECONCILIATION_TYPE = "RECONCILIATION_ENTRY";
    private static final int MAX_FIELD = 128;
    private static final int MAX_STATUS = 32;

    private final ResultRepository repository;
    private final ResultActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded result repository
     * @param policy       in-scope action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        injected clock for deterministic timestamps
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    public ResultRoutingService(ResultRepository repository, ResultActionPolicy policy,
                                TransactionTemplate transactions, AuditAppender audit, Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Register the synthetic expectation a released order will satisfy.
     *
     * @param request bounded identifier and routing values
     * @return the registered expectation
     * @throws ApiException on invalid input, denial or an existing order
     */
    public ExpectedOrder registerOrder(RegisterOrderRequest request) {
        String orderId = code(request.orderId(), "orderId");
        String patientId = uuid(request.patientId(), "patientId");
        String sourceId = code(request.sourceId(), "sourceId");
        String accessionId = code(request.accessionId(), "accessionId");
        String destinationId = code(request.destinationId(), "destinationId");
        String owner = code(request.owner(), "owner");
        String coverageQueue = code(request.coverageQueue(), "coverageQueue");
        ResultIdentifiers identifiers =
                new ResultIdentifiers(patientId, sourceId, accessionId, destinationId, orderId);
        ExpectedOrder order = new ExpectedOrder(orderId, identifiers, owner, coverageQueue);

        AtomicReference<ApiException> failure = new AtomicReference<>();
        ExpectedOrder registered = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ResultActionPolicy.ORDER_REGISTER, orderId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.findOrder(orderId).isPresent()) {
                ApiException conflict = new ApiException(409, "ORDER_EXISTS",
                        "an expected order with that identifier is already registered");
                writeAudit(context, "XCH04_ORDER_REGISTER", RESOURCE_TYPE, orderId, "CONFLICT");
                failure.set(conflict);
                return null;
            }
            ExpectedOrder created = repository.insertOrder(order);
            writeAudit(context, "XCH04_ORDER_REGISTER", RESOURCE_TYPE, orderId, "SUCCESS");
            return created;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return registered;
    }

    /**
     * Import one inbound result, routing it or diverting it to reconciliation.
     *
     * <p>A repeated import of the same source, accession and version returns the
     * original revision; it never duplicates a report or a notification.</p>
     *
     * @param request bounded inbound result values
     * @return an imported revision or a reconciliation entry, never both
     * @throws ApiException on invalid input or denial
     */
    public ImportOutcome importResult(ImportResultRequest request) {
        String orderId = code(request.orderId(), "orderId");
        String patientId = uuid(request.patientId(), "patientId");
        String sourceId = code(request.sourceId(), "sourceId");
        String accessionId = code(request.accessionId(), "accessionId");
        String destinationId = code(request.destinationId(), "destinationId");
        String checksum = code(request.checksum(), "checksum");
        ResultKind kind = kind(request.kind());
        Instant effectiveAt = instant(request.effectiveAt());
        Integer supersedes = request.supersedesVersion();
        if (supersedes != null && (supersedes < 1 || supersedes > 1_000_000)) {
            throw new ApiException(422, "INVALID_SUPERSEDES", "supersedesVersion must be a positive version");
        }
        ResultIdentifiers identifiers =
                new ResultIdentifiers(patientId, sourceId, accessionId, destinationId, orderId);

        AtomicReference<ApiException> failure = new AtomicReference<>();
        ImportOutcome outcome = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ResultActionPolicy.RESULT_IMPORT, orderId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            return importGuarded(context, identifiers, kind, effectiveAt, checksum, supersedes);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return outcome;
    }

    /**
     * Read one revision with its review task, lineage and correction notices.
     *
     * @param id revision identifier
     * @return the result detail
     * @throws ApiException on bad input, denial or concealment
     */
    public ResultDetail getReport(String id) {
        String reportId = uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ResultDetail detail = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ResultActionPolicy.RESULT_READ, reportId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ResultReport report = repository.findReportById(reportId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "result not found"));
            Review review = repository.findReview(reportId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "review task not found"));
            List<ResultReport> lineage = repository.lineage(report.orderId(),
                    report.identifiers().sourceId(), report.identifiers().accessionId());
            List<RecipientNotification> notifications = repository.notifications(reportId);
            writeAudit(context, "XCH04_RESULT_READ", RESOURCE_TYPE, reportId, "SUCCESS");
            return new ResultDetail(report, review, lineage, notifications);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return detail;
    }

    /**
     * Acknowledge one exact revision with a version precondition.
     *
     * @param reportId        revision identifier
     * @param expectedVersion version read by the caller
     * @return the acknowledged review task
     * @throws ApiException on bad input, denial, stale version or repeat
     */
    public Review acknowledge(String reportId, int expectedVersion) {
        String id = uuid(reportId, "reportId");
        if (expectedVersion < 1) {
            throw new ApiException(422, "INVALID_IF_MATCH", "a positive version is required");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Review reviewed = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ResultActionPolicy.REVIEW_ACK, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Review current = repository.findReview(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "review task not found"));
            if (current.state() == ReviewState.ACKNOWLEDGED) {
                ApiException conflict = new ApiException(409, "ALREADY_ACKNOWLEDGED",
                        "this revision has already been acknowledged");
                writeAudit(context, "XCH04_REVIEW_ACK", RESOURCE_TYPE, id, "CONFLICT");
                failure.set(conflict);
                return null;
            }
            int updated = repository.updateReview(id, expectedVersion, clock.instant());
            if (updated == 0) {
                ApiException stale = new ApiException(412, "STALE_VERSION",
                        "the review task changed since it was read");
                writeAudit(context, "XCH04_REVIEW_ACK", RESOURCE_TYPE, id, "CONFLICT");
                failure.set(stale);
                return null;
            }
            writeAudit(context, "XCH04_REVIEW_ACK", RESOURCE_TYPE, id, "SUCCESS");
            return repository.findReview(id).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return reviewed;
    }

    /**
     * List a bounded page of the reconciliation queue.
     *
     * @param status {@code open} or {@code resolved}
     * @return queue entries newest first
     * @throws ApiException on bad input or denial
     */
    public List<ReconciliationEntry> listReconciliation(String status) {
        String requested = code(status, "status", MAX_STATUS);
        if (!"open".equals(requested) && !"resolved".equals(requested)) {
            throw new ApiException(422, "INVALID_STATUS", "status must be open or resolved");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<ReconciliationEntry> entries = transactions.execute(status1 -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ResultActionPolicy.RECONCILIATION_READ, requested);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<ReconciliationEntry> found = repository.listReconciliation(requested);
            writeAudit(context, "XCH04_RECONCILIATION_READ", RECONCILIATION_TYPE, requested, "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return entries;
    }

    private ImportOutcome importGuarded(TenantContext context, ResultIdentifiers identifiers,
                                        ResultKind kind, Instant effectiveAt, String checksum,
                                        Integer supersedes) {
        Optional<ExpectedOrder> expected = repository.findOrder(identifiers.orderId());
        MatchDecision decision = ResultRules.match(expected, identifiers);
        if (decision.status() != MatchStatus.MATCHED) {
            ReconciliationReason reason = decision.status() == MatchStatus.UNMATCHED
                    ? ReconciliationReason.UNMATCHED : ReconciliationReason.CONFLICT;
            String detail = reason == ReconciliationReason.UNMATCHED
                    ? "no expected order registered for " + identifiers.orderId()
                    : "identifier mismatch: " + String.join(",", decision.mismatches());
            ReconciliationEntry entry = new ReconciliationEntry(UUID.randomUUID().toString(), identifiers,
                    reason, decision.mismatches(), detail, ReconciliationStatus.OPEN, clock.instant());
            repository.insertReconciliation(entry);
            writeAudit(context, "XCH04_RESULT_RECONCILED", RECONCILIATION_TYPE, entry.id(), "SUCCESS");
            return new ImportOutcome(null, entry);
        }

        ExpectedOrder order = expected.get();
        Routing routing = ResultRules.route(order, kind);
        int version;
        String previousId = null;
        List<String> recipients = List.of();
        Optional<Review> priorReview = Optional.empty();
        if (kind.isCorrection()) {
            if (supersedes == null) {
                throw new ApiException(422, "SUPERSEDES_REQUIRED",
                        "a corrected result must name the version it supersedes");
            }
            version = supersedes + 1;
            Optional<ResultReport> prior = repository.findReport(
                    identifiers.orderId(), identifiers.sourceId(), identifiers.accessionId(), supersedes);
            if (prior.isEmpty()) {
                ReconciliationEntry entry = new ReconciliationEntry(UUID.randomUUID().toString(),
                        identifiers, ReconciliationReason.CONFLICT, List.of("supersedesVersion"),
                        "the superseded result version was not found", ReconciliationStatus.OPEN,
                        clock.instant());
                repository.insertReconciliation(entry);
                writeAudit(context, "XCH04_RESULT_RECONCILED", RECONCILIATION_TYPE, entry.id(), "SUCCESS");
                return new ImportOutcome(null, entry);
            }
            previousId = prior.get().id();
            recipients = repository.priorRecipients(identifiers.orderId());
            priorReview = repository.findReview(previousId);
        } else {
            if (supersedes != null) {
                throw new ApiException(422, "SUPERSEDES_NOT_ALLOWED",
                        "only a corrected result may supersede a prior version");
            }
            version = repository.lineage(identifiers.orderId(), identifiers.sourceId(),
                    identifiers.accessionId()).stream().mapToInt(ResultReport::version).max().orElse(0) + 1;
        }

        Optional<ResultReport> duplicate = repository.findReport(
                identifiers.orderId(), identifiers.sourceId(), identifiers.accessionId(), version);
        if (duplicate.isPresent()) {
            writeAudit(context, "XCH04_RESULT_REPLAY", RESOURCE_TYPE, duplicate.get().id(), "SUCCESS");
            return new ImportOutcome(duplicate.get(), null);
        }

        Instant receivedAt = clock.instant();
        ResultReport report = new ResultReport(UUID.randomUUID().toString(), version, identifiers.orderId(),
                identifiers, kind, supersedes, previousId, routing.owner(), routing.coverageQueue(),
                effectiveAt, receivedAt, checksum);
        if (!repository.insertReport(report)) {
            // A racing import won the unique key. Return that exact revision; never
            // attach a second review task or a duplicate set of notifications.
            ResultReport winner = repository.findReport(identifiers.orderId(), identifiers.sourceId(),
                    identifiers.accessionId(), version)
                    .orElseThrow(() -> new ApiException(409, "REPLAY_UNRESOLVED",
                            "a concurrent import owns this result version; retry the read"));
            writeAudit(context, "XCH04_RESULT_REPLAY", RESOURCE_TYPE, winner.id(), "SUCCESS");
            return new ImportOutcome(winner, null);
        }
        repository.insertReview(report.id());
        for (String recipient : recipients) {
            repository.insertNotification(new RecipientNotification(UUID.randomUUID().toString(),
                    report.id(), recipient, supersedes, receivedAt));
        }
        if (priorReview.isPresent() && priorReview.get().state() == ReviewState.ACKNOWLEDGED) {
            writeAudit(context, "XCH04_REVIEW_REOPENED", RESOURCE_TYPE, previousId, "SUCCESS");
        }
        writeAudit(context, kind == ResultKind.CRITICAL
                ? "XCH04_CRITICAL_ROUTE" : "XCH04_RESULT_IMPORT", RESOURCE_TYPE, report.id(), "SUCCESS");
        return new ImportOutcome(report, null);
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            writeAudit(context, action, RESOURCE_TYPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            writeAudit(context, action, RESOURCE_TYPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing result permission");
        }
        return null;
    }

    private void writeAudit(TenantContext context, String action, String resourceType,
                            String resourceId, String outcome) {
        audit.append(context, PURPOSE, action, resourceType, SCOPE, resourceId, outcome);
    }

    private static String code(String value, String field) {
        return code(value, field, MAX_FIELD);
    }

    private static String code(String value, String field, int maximum) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_INPUT", field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maximum) {
            throw new ApiException(422, "INVALID_INPUT", field + " is too long");
        }
        return trimmed;
    }

    private static String uuid(String value, String field) {
        String text = code(value, field);
        try {
            return UUID.fromString(text).toString();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_INPUT", field + " must be a UUID");
        }
    }

    private static ResultKind kind(String value) {
        String text = code(value, "kind", MAX_STATUS);
        try {
            return ResultKind.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_KIND", "kind must be final, corrected or critical");
        }
    }

    private static Instant instant(String value) {
        String text = code(value, "effectiveAt", MAX_FIELD);
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_INPUT", "effectiveAt must be an ISO-8601 instant");
        }
    }
}
