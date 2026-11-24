package com.aurevia.authz.specialty;

import java.time.Instant;
import java.util.List;

/**
 * Wire contract for the S-G4-06 specialty permission and attestation routes.
 *
 * <p>All identifiers are opaque tenant-owned UUID text. {@code roleKey} is the
 * role the caller declares; it never grants authority on its own and is checked
 * against the active-role fold of the current organization before a write.</p>
 */
public final class SpecialtyContract {

    private SpecialtyContract() {
    }

    /**
     * Command to record one human clinical attestation.
     *
     * @return the immutable command value
     */
    public record AttestationRequest(String actionKey, String roleKey, String patientId,
                                     String resourceRef, String attestedText) {
    }

    /**
     * Immutable attestation evidence (PRD-06).
     *
     * @return the immutable evidence value
     */
    public record Attestation(String id, int version, String patientId, String actionKey,
                              String roleKey, SpecialtyDecision decision, String subject,
                              String actorId, int attestedRevision, String resourceRef,
                              String attestedText, Instant occurredAt) {
    }

    /**
     * Effective permissions of the authenticated subject in the active organization.
     *
     * @return the immutable permission view
     */
    public record PermissionView(String subject, String organizationId, List<String> roles,
                                 List<String> actions) {
        /**
         * Defensively copy the projected lists.
         *
         */
        public PermissionView {
            roles = List.copyOf(roles);
            actions = List.copyOf(actions);
        }
    }
}
