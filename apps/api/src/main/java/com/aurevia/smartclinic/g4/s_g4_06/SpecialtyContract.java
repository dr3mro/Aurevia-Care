package com.aurevia.smartclinic.g4.s_g4_06;

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
     * @param actionKey    workflow action the human is attesting
     * @param roleKey      role the caller declares for this action
     * @param patientId    owning patient, verified in the active tenant
     * @param resourceRef  local order, referral, study or prescription reference
     * @param attestedText the human's decision statement
     * @return the immutable command value
     */
    public record AttestationRequest(String actionKey, String roleKey, String patientId,
                                     String resourceRef, String attestedText) {
    }

    /**
     * Immutable attestation evidence (PRD-06).
     *
     * @param id               opaque attestation identifier
     * @param version          immutable revision, always one
     * @param patientId        owning patient
     * @param actionKey        attested workflow action
     * @param roleKey          role that authorized the action
     * @param decision         PRD-06 decision derived from the action
     * @param subject          authenticated subject derived from the transaction actor
     * @param actorId          resolved user identity of the actor
     * @param attestedRevision revision of the artifact the human read before attesting
     * @param resourceRef      local source reference
     * @param attestedText     human decision statement
     * @param occurredAt       server timestamp of the attestation
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
     * @param subject        authenticated subject
     * @param organizationId active organization
     * @param roles          active role keys
     * @param actions        union of actions permitted by those roles
     * @return the immutable permission view
     */
    public record PermissionView(String subject, String organizationId, List<String> roles,
                                 List<String> actions) {
        /**
         * Defensively copy the projected lists.
         *
         * @param subject        authenticated subject
         * @param organizationId active organization
         * @param roles          active role keys
         * @param actions        permitted action keys
         */
        public PermissionView {
            roles = List.copyOf(roles);
            actions = List.copyOf(actions);
        }
    }
}
