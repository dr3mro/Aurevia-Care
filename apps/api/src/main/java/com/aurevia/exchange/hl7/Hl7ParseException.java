package com.aurevia.exchange.hl7;

/**
 * Signals an HL7 v2 message that cannot be safely mapped to an internal command.
 *
 * <p>The machine-readable {@link com.aurevia.exchange.hl7.Hl7ParseException#reason()} is carried into the MSA/ERR portion
 * of the negative acknowledgment so a partner receives a stable error code
 * rather than a free-text diagnosis (XCH-01, XCH-04).</p>
 */
public class Hl7ParseException extends RuntimeException {

    /** Serialization identifier for this runtime exception. */
    private static final long serialVersionUID = 1L;

    private final String reason;

    /**
     * Create the exception.
     *
     * @param reason stable machine-readable reason code
     * @param detail human-readable detail for the log, never sent to a partner
     */
    public Hl7ParseException(String reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    /**
     * Return the stable reason code for the negative acknowledgment.
     *
     * @return reason code such as {@code truncated_message}
     */
    public String reason() {
        return reason;
    }
}
