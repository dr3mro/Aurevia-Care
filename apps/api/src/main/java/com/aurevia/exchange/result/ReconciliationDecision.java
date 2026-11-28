package com.aurevia.exchange.result;

import java.time.Instant;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.exchange.result.ReconciliationModel.ReconciliationAction;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;

/**
 * Pure reconciliation-resolution rules.
 *
 * <p>An entry may be resolved only once. Every disposition requires a recorded
 * reason; only a reassignment may name a new owner, and that owner is required
 * and bounded. No I/O or framework state lives here, so each guard is decided
 * from its arguments alone.</p>
 */
public final class ReconciliationDecision {

    private static final int MAX_FIELD = 128;

    private ReconciliationDecision() {
    }

    /**
     * Validate an operator action against an open queue entry.
     *
     * @param entry      the queue entry being resolved
     * @param action     operator disposition
     * @param reason     recorded justification
     * @param owner      proposed new owner, required only for a reassignment
     * @param resolvedAt server instant of the decision
     * @return the validated, immutable resolution
     * @throws ApiException when the entry is closed or a required field is
     *                      missing, oversized or not allowed for the action
     */
    public static Resolution decide(ReconciliationEntry entry, ReconciliationAction action,
                                    String reason, String owner, Instant resolvedAt) {
        if (entry.status() != ReconciliationStatus.OPEN) {
            throw new ApiException(409, "ENTRY_RESOLVED", "this reconciliation entry is already resolved");
        }
        String justification = required(reason, "reason", "REASON_REQUIRED");
        if (action == ReconciliationAction.REASSIGN) {
            return new Resolution(entry.id(), action, justification,
                    required(owner, "assignedOwner", "OWNER_REQUIRED"), resolvedAt);
        }
        if (owner != null && !owner.isBlank()) {
            throw new ApiException(422, "OWNER_NOT_ALLOWED", "only a reassignment names a new owner");
        }
        return new Resolution(entry.id(), action, justification, null, resolvedAt);
    }

    private static String required(String value, String field, String code) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, code, field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_FIELD) {
            throw new ApiException(422, code, field + " is too long");
        }
        return trimmed;
    }
}
