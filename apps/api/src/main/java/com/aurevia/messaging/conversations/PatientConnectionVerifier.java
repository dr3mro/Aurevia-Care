package com.aurevia.messaging.conversations;

import com.aurevia.api.ApiException;

/**
 * Boundary that resolves the verified connection a patient is using for a
 * protected messaging action.
 *
 * <p>The port is owned by the conversation domain so it depends only on
 * conversation vocabulary. The implementation lives in the care-unit directory
 * and derives every identity from the authenticated context and the persistent
 * connection record, never from the request body; a client may name a
 * connection but never establish one.</p>
 */
public interface PatientConnectionVerifier {

    /**
     * The authoritative identities a conversation command may persist.
     *
     * @param patientId    verified chart identifier derived from the connection
     * @param connectionId identifier of the exact connection that was verified
     * @param unitKey      recipient unit key derived from that connection's unit
     * @return the immutable verified identities
     */
    record VerifiedConnection(String patientId, String connectionId, String unitKey) {
    }

    /**
     * Require that the exact named connection is active, belongs to the
     * authenticated patient's verified chart, and targets the expected unit.
     *
     * <p>Every failure is reported as one indistinguishable denial so a caller
     * cannot probe which chart, connection or unit is present.</p>
     *
     * @param patientId    verified chart identifier named by the caller
     * @param unitKey      expected recipient care-unit key
     * @param connectionId exact connection identifier named by the caller
     * @return the server-derived identities to persist
     * @throws ApiException on invalid input, missing context, denial or a
     *                      connection that is absent, revoked or for another
     *                      patient or unit
     */
    VerifiedConnection requireActive(String patientId, String unitKey, String connectionId);
}
