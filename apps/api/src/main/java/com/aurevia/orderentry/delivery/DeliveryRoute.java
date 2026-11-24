package com.aurevia.orderentry.delivery;

import java.util.Locale;

/**
 * Channel currently chosen to deliver one placed order (XCH-01, PRD-02).
 */
public enum DeliveryRoute {

    /** Electronic submission through an approved partner interface. */
    ELECTRONIC,
    /** Approved manual handover with recorded delivery and verification evidence. */
    MANUAL,
    /** Printed fallback produced without sending the order again. */
    PRINT;

    /**
     * Stable lower-case wire key.
     *
     * @return the lower-case enum name
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
