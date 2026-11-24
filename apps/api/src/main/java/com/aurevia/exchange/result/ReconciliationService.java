package com.aurevia.exchange.result;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.result.ReconciliationModel.ReconciliationAction;
import com.aurevia.exchange.result.ReconciliationModel.ResolveRequest;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.tenant.TenantContext;

/**
 * Commands for XCH-04 reconciliation-queue resolution (ARC-02, OPS-02).
 *
 * <p>Each command runs in one tenant-guarded transaction. Membership and role
 * are re-derived from the active tenant on every request, the entry is read
 * inside that transaction, and the reason and optional new owner are validated
 * before the decision is recorded. The immutable decision is kept in an
 * append-only, tenant-keyed projection; a second resolution of the same entry
 * is rejected instead of overwriting the first. The action and its outcome are
 * also appended to the durable audit log with identifiers only - never the
 * inbound clinical payload.</p>
 *
 * <p>The queue status column cannot be advanced here: migration V22 grants
 * {@code aurevia_app} only SELECT and INSERT on the reconciliation table,
 * and this story reserves no migration version. A follow-up storage story must
 * grant a bounded UPDATE before the durable status can mirror the decision.</p>
 */
@Service
public class ReconciliationService {

    private static final String PURPOSE = "XCH04_RECONCILIATION_RESOLVE";
    private static final String SCOPE = "EXCHANGE";
    private static final String RESOURCE_TYPE = "RECONCILIATION_ENTRY";
    private static final String ACTION = ResultActionPolicy.RECONCILIATION_RESOLVE;

    private final ResultRepository repository;
    private final ResultActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;
    private final ConcurrentMap<String, Resolution> resolutions = new ConcurrentHashMap<>();

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded result repository
     * @param policy       in-scope action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        injected clock for deterministic timestamps
     */
    public ReconciliationService(ResultRepository repository, ResultActionPolicy policy,
                                 TransactionTemplate transactions, AuditAppender audit, Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Resolve one reconciliation entry by matching, rejecting or reassigning it.
     *
     * @param entryId identifier of the queue entry
     * @param request action, reason and optional new owner
     * @return the immutable resolution decision
     * @throws ApiException on invalid input, denial, concealment or a repeated
     *                      resolution of the same entry
     */
    public Resolution resolve(String entryId, ResolveRequest request) {
        String id = uuid(entryId, "entryId");
        if (request == null) {
            throw new ApiException(422, "INVALID_REQUEST", "a resolution request is required");
        }
        ReconciliationAction action = ReconciliationAction.from(request.action());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Resolution resolution = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ReconciliationEntry entry = repository.findReconciliation(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "reconciliation entry not found"));
            Instant at = clock.instant();
            Resolution decision = ReconciliationDecision.decide(entry, action,
                    request.reason(), request.assignedOwner(), at);
            Resolution prior = resolutions.putIfAbsent(key(context, id), decision);
            if (prior != null) {
                ApiException conflict = new ApiException(409, "ENTRY_ALREADY_RESOLVED",
                        "this reconciliation entry has already been resolved");
                writeAudit(context, id, "CONFLICT");
                failure.set(conflict);
                return null;
            }
            writeAudit(context, id, "SUCCESS");
            return decision;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return resolution;
    }

    /**
     * Read a recorded resolution so a caller can confirm what was decided.
     *
     * @param entryId queue entry identifier
     * @return the resolution recorded in the active tenant, if any
     */
    public Optional<Resolution> resolution(String entryId) {
        String id = uuid(entryId, "entryId");
        TenantContext context = TenantContext.current();
        return Optional.ofNullable(resolutions.get(key(context, id)));
    }

    private ApiException authorize(TenantContext context, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            writeAudit(context, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(ACTION, role))) {
            writeAudit(context, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing reconciliation permission");
        }
        return null;
    }

    private void writeAudit(TenantContext context, String resourceId, String outcome) {
        audit.append(context, PURPOSE, ACTION, RESOURCE_TYPE, SCOPE, resourceId, outcome);
    }

    private static String key(TenantContext context, String entryId) {
        return context.organizationId() + ":" + entryId;
    }

    private static String uuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new ApiException(422, "INVALID_INPUT", field + " must be a UUID");
        }
    }
}
