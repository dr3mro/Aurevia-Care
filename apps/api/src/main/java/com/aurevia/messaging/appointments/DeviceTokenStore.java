package com.aurevia.messaging.appointments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped device registry and invalid-token log (PAT-01, ARC-02, OPS-05).
 * The token value is stored for delivery only and must never be written to an
 * audit or operational log.
 */
public interface DeviceTokenStore {

    /**
     * Insert or replace one device registration.
     *
     * @param token registration bound to a verified patient and subject
     */
    void save(DeviceToken token);

    /**
     * Find a device within one verified patient's scope.
     *
     * @param tokenId   opaque device identifier
     * @param patientId verified patient identifier
     * @return the registration or empty when it is outside the scope
     */
    Optional<DeviceToken> find(String tokenId, String patientId);

    /**
     * Find a device by id within the active tenant, ignoring patient scope.
     *
     * <p>Registration uses this to reject an id already owned by a different
     * patient in the same organization; rebinding would silently strip the
     * displaced patient of the ability to revoke their own device.</p>
     *
     * @param tokenId opaque device identifier
     * @return the existing registration or empty when the id is unused
     */
    Optional<DeviceToken> findByTokenId(String tokenId);

    /**
     * Return deliverable devices at dispatch time, not from a long-lived cache.
     *
     * @param patientId verified patient identifier
     * @return currently active registrations
     */
    List<DeviceToken> active(String patientId);

    /**
     * Append an invalid-token disposition. The provider token value is omitted.
     *
     * @param tokenId   opaque device identifier
     * @param patientId verified patient identifier
     * @param reason    non-sensitive disposition code
     * @param at        server instant of the disposition
     */
    void appendInvalidTokenLog(String tokenId, String patientId, String reason, Instant at);
}
