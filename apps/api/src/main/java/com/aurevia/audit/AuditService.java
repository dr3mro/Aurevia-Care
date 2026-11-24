package com.aurevia.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Audit-review invariants: authorization, bounded filters and cursor paging.
 *
 * <p>The query itself is appended as an OPS-02 audit event in the same guarded
 * transaction, so every disclosure review is recorded. Denials are committed
 * by returning the failure from the transaction and throwing afterwards.</p>
 */
@Service
public class AuditService {

    private static final Pattern FILTER = Pattern.compile("[A-Za-z0-9_.:/@-]{1,64}");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final AuditRepository repository;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded audit repository
     * @param policy       action policy
     * @param transactions tenant-guarded transaction template
     */
    public AuditService(AuditRepository repository, ActionPolicy policy, TransactionTemplate transactions) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * Query one page of the active tenant's audit trail.
     *
     * @param cursor       opaque seq cursor, or {@code null} for the newest page
     * @param limit        page size, or {@code null} for the default of 50
     * @param action       optional exact action filter
     * @param outcome      optional exact outcome filter
     * @param resourceType optional exact resource-type filter
     * @param from         optional inclusive UTC instant
     * @param to           optional inclusive UTC instant
     * @return one page with an opaque next cursor when more rows exist
     * @throws ApiException on invalid input or denied access
     */
    public AuditPage query(String cursor, String limit, String action, String outcome,
                           String resourceType, String from, String to) {
        Long parsedCursor = parseCursor(cursor);
        int parsedLimit = parseLimit(limit);
        String actionFilter = parseFilter(action, "action");
        String outcomeFilter = parseFilter(outcome, "outcome");
        String resourceFilter = parseFilter(resourceType, "resourceType");
        String fromInstant = parseInstant(from, "from");
        String toInstant = parseInstant(to, "to");

        AtomicReference<ApiException> failure = new AtomicReference<>();
        AuditPage page = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            // Fetch one extra row to decide whether a next page exists.
            List<AuditEvent> rows = repository.query(parsedCursor, parsedLimit + 1,
                    actionFilter, outcomeFilter, resourceFilter, fromInstant, toInstant);
            boolean hasMore = rows.size() > parsedLimit;
            List<AuditEvent> events = hasMore ? rows.subList(0, parsedLimit) : rows;
            String next = hasMore ? String.valueOf(events.get(events.size() - 1).seq()) : null;
            repository.appendAudit(context, "AUDIT_READ", actionFilter == null ? "ALL" : actionFilter, "SUCCESS");
            return new AuditPage(events, next);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return page;
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(cursor);
            if (value < 1) {
                throw new NumberFormatException("cursor must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be a positive sequence number");
        }
    }

    private int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(limit);
            if (value < 1 || value > MAX_LIMIT) {
                throw new NumberFormatException("limit out of range");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be 1-200");
        }
    }

    private String parseFilter(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!FILTER.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + name.toUpperCase(java.util.Locale.ROOT),
                    name + " has invalid characters");
        }
        return value;
    }

    private String parseInstant(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_" + name.toUpperCase(java.util.Locale.ROOT),
                    name + " must be a UTC instant");
        }
    }

    private ApiException authorize(TenantContext context) {
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, "AUDIT_READ", "ALL", "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows("AUDIT_READ", role))) {
            repository.appendAudit(context, "AUDIT_READ", "ALL", "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing audit permission");
        }
        return null;
    }
}
