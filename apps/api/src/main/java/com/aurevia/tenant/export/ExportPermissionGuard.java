package com.aurevia.tenant.export;

import org.springframework.stereotype.Component;

import com.aurevia.audit.AuditRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Export permission check.
 *
 * <p>Membership and role folds are read from the authoritative database views
 * on every command, so a deactivation or revoked role takes effect immediately.
 * The bounded export permission is limited to an active {@code tenant_admin};
 * a privacy auditor may read audit data but cannot export a tenant.</p>
 */
@Component
public class ExportPermissionGuard {

    private static final String TENANT_ADMIN = "tenant_admin";

    private final AuditRepository repository;

    /**
     * Create the permission check over the shared audit folds.
     *
     * @param repository authoritative membership and role repository
     */
    public ExportPermissionGuard(AuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Decide whether the current subject may export the active tenant.
     *
     * @param context request tenant context
     * @return {@code true} only for an active tenant admin
     */
    public boolean mayExport(TenantContext context) {
        return repository.hasActiveMembership(context.subject())
                && repository.activeRoles(context.subject()).stream().anyMatch(TENANT_ADMIN::equals);
    }
}
