package com.aurevia.tenant;

import java.util.List;
import java.util.UUID;

/**
 * Authoritative source of organizations eligible for cross-tenant scheduled
 * work.
 *
 * <p>This is a system boundary, not a user authorization path. The enumeration
 * is independent of {@link com.aurevia.tenant.TenantContext} and of the
 * {@code app.current_org} GUC: a scheduled job has neither. A consumer must bind
 * a synthetic tenant context and re-check tenant permissions for every
 * organization it processes; callers must never use this list to authorize a
 * user request or to read tenant data directly.</p>
 */
public interface ActiveTenantSource {

    /**
     * List every active organization identifier in deterministic order.
     *
     * @return immutable list of organization identifiers; empty when no
     *         organization is active
     */
    List<UUID> activeOrganizationIds();
}
