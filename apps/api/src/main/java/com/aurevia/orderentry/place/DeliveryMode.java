package com.aurevia.orderentry.place;

import java.util.Locale;

import com.aurevia.api.ApiException;

/**
 * How a placed clinical order reaches the performing partner and the patient (LAB-01, RAD-01, XCH-01).
 *
 * <p>The mode is chosen by the ordering clinician, validated at the HTTP trust
 * boundary and stored unchanged on the immutable placed order. It is a recorded
 * fact, never inferred from the destination or the catalog item: an electronic
 * send, a paper copy the patient carries, or both are separate clinical choices.
 * Actual transmission and printing are later slices; this type only records the
 * choice and answers whether each channel is included.</p>
 */
public enum DeliveryMode {

    /** Send the order electronically to the destination partner. */
    ELECTRONIC,
    /** Produce a printed order for the patient to carry. */
    PRINT,
    /** Send electronically and also produce a printed order. */
    BOTH;

    /**
     * Parse a caller-supplied delivery mode.
     *
     * @param value raw mode text, matched case-insensitively after trimming
     * @return the approved delivery mode
     * @throws ApiException {@code 422} when the value is missing or not approved
     */
    public static DeliveryMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_DELIVERY_MODE", "a delivery mode is required");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ELECTRONIC" -> ELECTRONIC;
            case "PRINT" -> PRINT;
            case "BOTH" -> BOTH;
            default -> throw new ApiException(422, "INVALID_DELIVERY_MODE", "delivery mode is not approved");
        };
    }

    /**
     * Whether the electronic channel is part of this mode.
     *
     * @return {@code true} for electronic and for both
     */
    public boolean electronic() {
        return this == ELECTRONIC || this == BOTH;
    }

    /**
     * Whether the printed channel is part of this mode.
     *
     * @return {@code true} for print and for both
     */
    public boolean print() {
        return this == PRINT || this == BOTH;
    }
}
