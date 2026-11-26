package com.aurevia.exchange.result;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
 * before the decision is recorded. The immutable decision is appended to a
 * tenant-owned fact table through {@link com.aurevia.exchange.result.ReconciliationStore};
 * its unique key rejects a second resolution instead of overwriting the first,
 * and its presence removes the entry from the open queue through the read-side
 * fold. The action and its outcome are also appended to the durable audit log
 * with identifiers only - never the inbound clinical payload.</p>
 *
 * <p>The V22 reconciliation ledger stays immutable: its status column is never
 * updated, so the original queue row remains an unaltered record of diversion.</p>
 */
@Service
public class ReconciliationService {

    private static final String PURPOSE = "XCH04_RECONCILIATION_RESOLVE";
    private static final String READ_PURPOSE = "XCH04_RECONCILIATION_RESOLUTION_READ";
    private static final String SCOPE = "EXCHANGE";
    private static final String RESOURCE_TYPE = "RECONCILIATION_ENTRY";
    private static final String ACTION = ResultActionPolicy.RECONCILIATION_RESOLVE;
    private static final String READ_ACTION = ResultActionPolicy.RECONCILIATION_READ;

    private final ResultRepository repository;
    private final ResultActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;
    private final ReconciliationStore store;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded result repository
     * @param policy       in-scope action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        injected clock for deterministic timestamps
     * @param store        durable tenant-scoped decision store
     */
    public ReconciliationService(ResultRepository repository, ResultActionPolicy policy,
                                 TransactionTemplate transactions, AuditAppender audit, Clock clock,
                                 ReconciliationStore store) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
        this.store = store;
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
            ApiException denial = authorize(context, PURPOSE, ACTION, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ReconciliationEntry entry = repository.findReconciliation(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "reconciliation entry not found"));
            Instant at = clock.instant();
            Resolution decision = ReconciliationDecision.decide(entry, action,
                    request.reason(), request.assignedOwner(), at);
            // The tenant-scoped unique key is the only arbiter of a repeat: an empty result means
            // another worker appended the first decision, so this call must not overwrite it.
            Resolution inserted = store.insert(context.organizationId(), decision);
            if (inserted == null) {
                ApiException conflict = new ApiException(409, "ENTRY_ALREADY_RESOLVED",
                        "this reconciliation entry has already been resolved");
                writeAudit(context, PURPOSE, ACTION, id, "CONFLICT");
                failure.set(conflict);
                return null;
            }
            writeAudit(context, PURPOSE, ACTION, id, "SUCCESS");
            return inserted;
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
        // The read must run inside the guarded transaction: app.current_org is a transaction-local
        // GUC, so a bare query would be hidden by forced RLS even for the owning tenant. A decision
        // is clinical-review metadata, so read access requires the same bounded role as the queue.
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Optional<Resolution> found = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, READ_PURPOSE, READ_ACTION, id);
            if (denial != null) {
                // Report after the callback so the DENIED audit is committed with the transaction.
                failure.set(denial);
                return Optional.empty();
            }
            Optional<Resolution> resolution = Optional.ofNullable(store.find(context.organizationId(), id));
            writeAudit(context, READ_PURPOSE, READ_ACTION, id,
                    resolution.isPresent() ? "SUCCESS" : "NOT_FOUND");
            return resolution;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return found;
    }

    private ApiException authorize(TenantContext context, String purpose, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            writeAudit(context, purpose, action, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            writeAudit(context, purpose, action, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing reconciliation permission");
        }
        return null;
    }

    private void writeAudit(TenantContext context, String purpose, String action, String resourceId,
                            String outcome) {
        audit.append(context, purpose, action, RESOURCE_TYPE, SCOPE, resourceId, outcome);
    }

    private static String uuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new ApiException(422, "INVALID_INPUT", field + " must be a UUID");
        }
    }
}
