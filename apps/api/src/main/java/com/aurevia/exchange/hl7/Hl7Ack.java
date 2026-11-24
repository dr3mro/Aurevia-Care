package com.aurevia.exchange.hl7;

/**
 * Application acknowledgment for one inbound HL7 v2 message.
 *
 * <p>{@code AA} accepts the message, {@code AE} reports an error such as a
 * malformed or truncated payload, and {@code AR} rejects a duplicate control
 * identifier. A negative acknowledgment always carries a stable reason code in
 * its reason field (XCH-01, XCH-04).</p>
 *
 * @param code              MSA-1 acknowledgment code
 * @param controlId         MSH-10 identifier assigned to this acknowledgment
 * @param originalControlId MSH-10 identifier of the message being acknowledged
 * @param reason            stable reason code, empty on acceptance
 * @param timestamp         HL7 v2 timestamp placed in the acknowledgment MSH
 */
public record Hl7Ack(String code, String controlId, String originalControlId, String reason,
                     String timestamp) {

    /** Acknowledge code: the message was applied. */
    public static final String ACCEPT = "AA";

    /** Error code: the message was malformed or incomplete. */
    public static final String ERROR = "AE";

    /** Reject code: the message control identifier was already processed. */
    public static final String REJECT = "AR";

    /**
     * Whether this acknowledgment reports a successful import.
     *
     * @return true only for an {@code AA} acknowledgment
     */
    public boolean positive() {
        return ACCEPT.equals(code);
    }
}
