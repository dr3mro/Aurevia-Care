package com.aurevia.tenant.retention;

import java.util.List;

import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;

/**
 * legal-hold coverage predicate.
 *
 * <p>A hold scope is an exact record id, an ancestor separated by {@code /}, or
 * {@code *} for the whole tenant. Coverage is bidirectional: deleting a parent
 * or expiring a tenant-wide backup can destroy a held descendant, so a hold on
 * a descendant also blocks an ancestor target. An inactive hold only stops
 * covering when its lift names both an actor and a reason; a crafted artifact
 * cannot release held data by merely flipping the active flag.</p>
 */
public final class HoldScope {

    private HoldScope() {
    }

    /**
     * Report whether a hold still covers data.
     *
     * @param hold legal hold to inspect
     * @return {@code true} while the hold is active or lacks an authorized lift
     */
    public static boolean effective(LegalHold hold) {
        return hold.active() || !hasAuthorizedLift(hold);
    }

    /**
     * Report whether one normalized hold scope covers one target.
     *
     * @param scope  hold scope, possibly padded or ending with a separator
     * @param target record id or backup scope to protect
     * @return {@code true} for an exact, descendant or tenant-wide match
     */
    public static boolean covers(String scope, String target) {
        if (scope == null || target == null) {
            return false;
        }
        // Validation rejects padded identifiers, but matching still normalizes
        // them so a padded hold never accidentally uncovers the record it names.
        String normalizedScope = scope.trim();
        String normalizedTarget = target.trim();
        if (normalizedScope.isEmpty() || normalizedTarget.isEmpty()) {
            return false;
        }
        if ("*".equals(normalizedScope)) {
            return true;
        }
        if (normalizedScope.equals(normalizedTarget)) {
            return true;
        }
        String prefix = normalizedScope.endsWith("/") ? normalizedScope : normalizedScope + "/";
        return normalizedTarget.startsWith(prefix);
    }

    /**
     * Report whether any effective hold covers a deletion or expiration target.
     *
     * @param holds  tenant-scoped legal holds
     * @param target record id or backup scope to check
     * @return {@code true} when deletion or expiration must be refused
     */
    public static boolean blocks(List<LegalHold> holds, String target) {
        for (LegalHold hold : holds) {
            if (effective(hold)
                    && (covers(hold.resourceId(), target) || covers(target, hold.resourceId()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Report whether a hold covers either the backup set or its scope.
     *
     * @param holds     tenant-scoped legal holds
     * @param backupSet backup set identifier
     * @param scope     resource scope covered by the backup
     * @return {@code true} when the backup expiration must be refused
     */
    public static boolean blocksBackup(List<LegalHold> holds, String backupSet, String scope) {
        return blocks(holds, backupSet) || blocks(holds, scope);
    }

    private static boolean hasAuthorizedLift(LegalHold hold) {
        return present(hold.releasedBy()) && present(hold.releasedReason());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
