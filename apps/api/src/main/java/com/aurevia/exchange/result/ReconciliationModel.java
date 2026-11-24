package com.aurevia.exchange.result;

import java.time.Instant;
import java.util.Locale;

import com.aurevia.api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Value contracts for operator resolution of unmatched XCH-04 results.
 *
 * <p>A result whose patient, accession, destination or order identifier does not
 * match the registered expectation is diverted to the reconciliation queue and
 * never imported. An authorized operator then resolves that entry by matching,
 * rejecting or reassigning it with a recorded reason. These immutable contracts
 * carry no framework or persistence dependency so the decision rules stay in
 * pure Java.</p>
 */
public final class ReconciliationModel {

    private ReconciliationModel() {
    }

    /** An operator disposition of one reconciliation-queue entry. */
    public enum ReconciliationAction {
        /** The operator confirms the identifiers and closes the entry. */
        MATCH,
        /** The operator rejects the inbound result and closes the entry. */
        REJECT,
        /** The operator hands the entry to a different named owner. */
        REASSIGN;

        /**
         * Return the lower-case wire form used by the API and the audit event.
         *
         * @return wire form such as {@code reassign}
         */
        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Parse the wire form into an action.
         *
         * @param value wire form, case-insensitive
         * @return the parsed action
         * @throws ApiException when the value is absent or not a known action
         */
        public static ReconciliationAction from(String value) {
            if (value == null || value.isBlank()) {
                throw new ApiException(422, "INVALID_ACTION", "action is required");
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new ApiException(422, "INVALID_ACTION", "action must be match, reject or reassign");
            }
        }
    }

    /**
     * Command resolving one reconciliation entry.
     *
     * @param action        wire form of the operator action (match, reject or reassign)
     * @param reason        operator's non-sensitive justification
     * @param assignedOwner new named owner, required only for a reassignment
     */
    public record ResolveRequest(String action, String reason, String assignedOwner) {
    }

    /**
     * Immutable record of an operator's reconciliation decision.
     *
     * @param entryId      identifier of the resolved queue entry
     * @param action       operator disposition
     * @param reason       recorded justification
     * @param assignedOwner new owner when reassigned, otherwise {@code null}
     * @param resolvedAt   server instant of the decision
     */
    public record Resolution(String entryId, ReconciliationAction action, String reason,
                             String assignedOwner, Instant resolvedAt) {
    }
}
