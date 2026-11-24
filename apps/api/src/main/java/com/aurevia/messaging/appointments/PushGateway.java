package com.aurevia.messaging.appointments;

/**
 * Synthetic push channel boundary (PAT-01, ARC-05). The real APNs/FCM adapter
 * is a deployment decision and is not part of this slice.
 */
public interface PushGateway {

    /** Provider disposition of one dispatch attempt. */
    enum Outcome {
        /** The provider accepted the notification. */
        DELIVERED,
        /** The provider rejected the device token; it must be invalidated. */
        INVALID_TOKEN
    }

    /**
     * Send one generic notice to one device token.
     *
     * @param token  provider token value, never logged
     * @param notice generic notice with no clinical detail
     * @return the provider disposition
     */
    Outcome send(String token, GenericPushNotice notice);
}
