package com.aurevia.exchange.task;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * XCH-02 command service: three independent state channels and human review.
 *
 * <p>Every command runs in one tenant-guarded transaction. Membership and roles
 * are re-read on each request, so a revocation denies the next protected
 * action. The clinical channel is clinician-only; a delivery receipt writes only
 * the transmission channel and cannot close a clinical task. Commit, event and
 * audit are atomic.</p>
 */
@Service
public class ExchangeTaskService {

    private static final String SCOPE = "EXCHANGE";
    private static final String RESOURCE_TYPE = "EXCHANGE_TASK";
    private static final String CREATE_ACTION = "EXCHANGE_CREATE";
    private static final String READ_ACTION = "EXCHANGE_READ";
    private static final String INITIAL_WORKFLOW = "requested";

    private final ExchangeTaskRepository repository;
    private final ExchangeActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded state repository
     * @param policy       scoped action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        injected clock for review evidence
     */
    public ExchangeTaskService(ExchangeTaskRepository repository, ExchangeActionPolicy policy,
                               TransactionTemplate transactions, AuditAppender audit, Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Open a work item in the requested / pending / unreviewed initial channels.
     *
     * @param request create command
     * @return the created state
     * @throws ApiException on invalid input or missing permission
     */
    public ExchangeTask create(CreateExchangeRequest request) {
        String orderRef = orderRef(request.orderRef());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ExchangeTask created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, CREATE_ACTION, orderRef, policy::allowsCreate);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            String id = UUID.randomUUID().toString();
            try {
                repository.insert(context.organizationId(), id, orderRef);
            } catch (DuplicateKeyException exception) {
                // A competing create can win the unique order reference. Audit the
                // failed sensitive mutation before returning while the row rolls back.
                audit.append(context, CREATE_ACTION, CREATE_ACTION, RESOURCE_TYPE, SCOPE,
                        orderRef, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT",
                        "an exchange task already exists for this order"));
                return null;
            }
            repository.appendEvent(id, 1, ExchangeDimension.WORKFLOW, CREATE_ACTION,
                    null, INITIAL_WORKFLOW, null, null);
            audit.append(context, CREATE_ACTION, CREATE_ACTION, RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return repository.find(id).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Read one task with its append-only history.
     *
     * @param id task identifier
     * @return state and history
     * @throws ApiException when malformed, denied or concealed
     */
    public ExchangeTaskView get(String id) {
        String taskId = id(id);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ExchangeTaskView view = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, READ_ACTION, taskId, policy::allowsRead);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ExchangeTask task = repository.find(taskId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "exchange task not found"));
            List<ExchangeTaskEvent> history = repository.history(taskId);
            audit.append(context, READ_ACTION, READ_ACTION, RESOURCE_TYPE, SCOPE, taskId, "SUCCESS");
            return new ExchangeTaskView(task, history);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view;
    }

    /**
     * Apply one explicit state command under a version precondition.
     *
     * @param id              task identifier
     * @param expectedVersion version read by the caller
     * @param command         command to apply
     * @param reason          reason for reject or cancel, otherwise {@code null}
     * @return the next state
     * @throws ApiException when invalid, denied, concealed, stale or illegal
     */
    public ExchangeTask execute(String id, int expectedVersion, ExchangeCommand command, String reason) {
        String taskId = id(id);
        String recordedReason = reason(reason, command);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ExchangeTask result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            String action = command.auditAction();
            ApiException denial = authorize(context, command.dimension().policyAction(), taskId,
                    role -> policy.allows(command.dimension().policyAction(), role));
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ExchangeTask current = repository.loadForUpdate(taskId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "exchange task not found"));
            requireVersion(current, expectedVersion);
            ExchangeTask next = ExchangeStateMachine.next(current, command, recordedReason, clock.instant());
            if (repository.updateState(taskId, expectedVersion, next) == 0) {
                throw staleVersion();
            }
            Integer reviewedRevision = command.dimension() == ExchangeDimension.CLINICAL_REVIEW
                    ? current.version() : null;
            // A reason belongs to a workflow decision only. Carrying a caller's
            // text into a transmission or review event would put evidence in the
            // ledger that the task itself does not hold (XCH-02, ARC-01).
            String eventReason = command.dimension() == ExchangeDimension.WORKFLOW
                    ? recordedReason : null;
            repository.appendEvent(taskId, next.version(), command.dimension(), action,
                    ExchangeStateMachine.fromState(current, command.dimension()),
                    command.targetState(), eventReason, reviewedRevision);
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, taskId, "SUCCESS");
            return repository.find(taskId).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private ApiException authorize(TenantContext context, String action, String resourceId,
                                   Predicate<String> allowed) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream().noneMatch(allowed)) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing exchange permission");
        }
        return null;
    }

    private static void requireVersion(ExchangeTask current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw staleVersion();
        }
    }

    private static ApiException staleVersion() {
        return new ApiException(412, "STALE_VERSION", "the exchange task changed since it was read");
    }

    private static String id(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_ID", "task id must be a UUID");
        }
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_ID", "task id must be a UUID");
        }
    }

    private static String orderRef(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiException(422, "INVALID_ORDER_REF", "orderRef must be bounded printable text");
        }
        return value;
    }

    private static String reason(String value, ExchangeCommand command) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (command.requiresReason() && normalized == null) {
            throw new ApiException(422, "REASON_REQUIRED", "a reason is required for this transition");
        }
        if (normalized != null && normalized.length() > 500) {
            throw new ApiException(422, "INVALID_REASON", "reason must be at most 500 characters");
        }
        return normalized;
    }
}
