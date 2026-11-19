package com.aurevia.smartclinic.messaging.s_g3_01;

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
