package com.aurevia.messaging.appointments;

/**
 * A device registered by a verified patient subject, bound to one patient in
 * one organization (PAT-01, ARC-02).
 *
 * @param tokenId  opaque device identifier, safe to log
 * @param token    push provider token; never written to an audit or log sink
 * @param subject  verified patient subject that registered the device
 * @param patientId patient identifier of the verified link
 * @param state    current delivery state
 * @return this record
 */
public record DeviceToken(String tokenId, String token, String subject, String patientId,
                          DeviceState state) {

    /**
     * Return a revoked copy without mutating this registration.
     *
     * @return the revoked registration
     */
    public DeviceToken revoke() {
        return new DeviceToken(tokenId, token, subject, patientId, DeviceState.REVOKED);
    }

    /**
     * Return an invalidated copy without mutating this registration.
     *
     * @return the invalid registration
     */
    public DeviceToken invalidate() {
        return new DeviceToken(tokenId, token, subject, patientId, DeviceState.INVALID);
    }
}
