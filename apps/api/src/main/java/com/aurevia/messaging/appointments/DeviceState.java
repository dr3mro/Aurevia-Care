package com.aurevia.messaging.appointments;

/**
 * Lifecycle of a registered push device.
 */
public enum DeviceState {

    /** Eligible for dispatch. */
    ACTIVE,
    /** Explicitly revoked by the patient; terminal. */
    REVOKED,
    /** Rejected by the push provider and logged; terminal until re-registered. */
    INVALID;

    /**
     * Whether this device may receive a push at dispatch time.
     *
     * @return {@code true} only for an active registration
     */
    public boolean deliverable() {
        return this == ACTIVE;
    }
}
