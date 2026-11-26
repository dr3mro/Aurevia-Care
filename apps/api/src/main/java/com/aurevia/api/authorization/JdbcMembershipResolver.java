package com.aurevia.api.authorization;

import java.util.Objects;

import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Re-reads the caller's membership and roles from the tenant-guarded database folds on every
 * authorization decision (OPS-02, IAM-01).
 *
 * <p>The server-derived identity must match the request's bound tenant context exactly. A
 * mismatch returns no fold so the gate denies without consulting a role from another tenant.
 * Both folds are read inside the caller's tenant transaction, where forced RLS confines the
 * authoritative membership and role rows to the active organization.</p>
 */
public final class JdbcMembershipResolver implements MembershipResolver {

    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the resolver.
     *
     * @param staff        tenant-scoped staff membership repository
     * @param transactions tenant-guarded transaction template that binds the RLS context
     */
    public JdbcMembershipResolver(StaffRepository staff, TransactionTemplate transactions) {
        this.staff = Objects.requireNonNull(staff, "staff");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /**
     * Resolve the current fold for the exact active organization and subject.
     *
     * @param identity server-derived active identity
     * @return fresh roles and membership, or {@code null} when the request context does not match
     */
    @Override
    public MemberContext resolve(ServerIdentity identity) {
        TenantContext context = TenantContext.current();
        if (context == null
                || !Objects.equals(context.organizationId(), identity.organizationId())
                || !Objects.equals(context.subject(), identity.subject())) {
            return null;
        }
        return transactions.execute(status -> new MemberContext(
                staff.activeRoles(identity.subject()), staff.hasActiveMembership(identity.subject())));
    }
}
