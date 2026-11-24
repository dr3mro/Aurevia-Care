package com.aurevia.staffmsg.directory;

import org.springframework.stereotype.Component;

import com.aurevia.api.ApiException;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * The single "active staff" authorization rule for directory and messaging commands.
 *
 * <p>Membership alone is not enough: a deactivated member or a member without an
 * effective role must not address other staff. The rule is read inside the caller's
 * tenant-guarded transaction so the database folds see the active organization.</p>
 */
@Component
public class ActiveStaffGuard {

    private final StaffRepository staff;

    /**
     * Create the gate over the authoritative staff membership source.
     *
     * @param staff staff membership and effective role repository
     */
    public ActiveStaffGuard(StaffRepository staff) {
        this.staff = staff;
    }

    /**
     * Require an active membership with at least one effective role.
     *
     * @param context bound tenant context carrying the authenticated subject
     * @throws ApiException with status 403 and code ACTIVE_STAFF_REQUIRED when the
     *         subject is not an active member or holds no effective role
     */
    public void require(TenantContext context) {
        if (!staff.hasActiveMembership(context.subject()) || staff.activeRoles(context.subject()).isEmpty()) {
            throw new ApiException(403, "ACTIVE_STAFF_REQUIRED",
                    "an active staff membership with a role is required");
        }
    }
}
