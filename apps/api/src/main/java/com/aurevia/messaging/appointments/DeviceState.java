package com.aurevia.messaging.appointments;

import java.util.Locale;

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

    /**
     * Return the lower-case value stored in the tenant-owned device table.
     *
     * @return wire value such as {@code revoked}
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse the stored lower-case state.
     *
     * @param value lower-case or upper-case state name
     * @return the matching lifecycle state
     * @throws IllegalArgumentException when the value is unknown
     */
    public static DeviceState from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
