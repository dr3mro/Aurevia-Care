package com.aurevia.authz.history;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.tenant.TenantContext;

/**
 * IAM-02 membership-history policy: tenant administrators only, re-derived each request.
 *
 * <p>Membership and roles are re-read from the authoritative database fold on every
 * call, so a revocation or expiry denies the very next request without a token or
 * session waiting to expire. A cross-tenant or unknown subject is concealed as 404.
 * Denials are audited by returning the failure from the guarded transaction rather
 * than throwing inside it.</p>
 */
@Service
public class RoleHistoryService {

    /** Business purpose stamped on role-history reads (OPS-02). */
    public static final String PURPOSE = "ROLE_HISTORY";
    /** Audit resource scope for role-history reads (OPS-02). */
    public static final String SCOPE = "AUTHZ";

    private static final Pattern SUBJECT = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");

    private final RoleHistoryStore store;
    private final AccessRepository access;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param store        RLS-bounded append-only history store
     * @param access       authoritative membership and role folds plus audit writer
     * @param policy       bounded action policy
     * @param transactions tenant-guarded transaction template
     */
    public RoleHistoryService(RoleHistoryStore store, AccessRepository access, ActionPolicy policy,
                              TransactionTemplate transactions) {
        this.store = store;
        this.access = access;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * Read the immutable role-change history of a staff subject.
     *
     * @param subject subject whose history is requested
     * @return the visible append-only history
     * @throws ApiException on invalid input, denied access or an unknown subject
     */
    public RoleHistory history(String subject) {
        validateSubject(subject);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        RoleHistory history = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, subject);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<RoleHistory.RoleChange> changes = store.findBySubject(subject);
            if (changes.isEmpty()) {
                access.appendAudit(context, PURPOSE, ActionPolicy.ROLE_HISTORY_READ, SCOPE,
                        SCOPE, subject, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "staff history not found"));
                return null;
            }
            access.appendAudit(context, PURPOSE, ActionPolicy.ROLE_HISTORY_READ, SCOPE,
                    SCOPE, subject, "SUCCESS");
            return new RoleHistory(subject, changes);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return history;
    }

    private ApiException authorize(TenantContext context, String resourceId) {
        String action = ActionPolicy.ROLE_HISTORY_READ;
        if (!access.hasActiveMembership(context.subject())) {
            access.appendAudit(context, PURPOSE, action, SCOPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (access.activeRoles(context.subject()).stream().noneMatch(role -> policy.allows(action, role))) {
            access.appendAudit(context, PURPOSE, action, SCOPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing action permission");
        }
        return null;
    }

    private void validateSubject(String subject) {
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw new ApiException(422, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        }
    }
}
