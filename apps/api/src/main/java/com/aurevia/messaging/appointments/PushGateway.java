package com.aurevia.messaging.appointments;

/**
 * Push channel boundary for generic appointment notices. A real APNs/FCM adapter
 * is a deployment decision; the local-only bean carries no clinical detail and
 * is documented as not proving provider delivery.
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
