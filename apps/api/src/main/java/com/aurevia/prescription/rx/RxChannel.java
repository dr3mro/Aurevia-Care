package com.aurevia.prescription.rx;

/**
 * The two approved RX-02 routing channel families (RX-02, ARC-03).
 *
 * <p>A channel wire key is stored in approved tenant configuration. An
 * electronic channel creates a stable submission id and may reach delivery and
 * pharmacy states; a locally printable channel produces an immutable document
 * only and never implies electronic delivery or pharmacy acceptance.</p>
 */
public enum RxChannel {

    /** Approved electronic prescribing channel with a stable submission id. */
    E_PRESCRIBE("E_PRESCRIBE", true),
    /** Locally approved printable prescription with no partner submission. */
    PRINTABLE_LOCAL("PRINTABLE_LOCAL", false);

    private final String wire;
    private final boolean electronic;

    /**
     * Create a channel with its wire key and transmission family.
     *
     * @param wire       stable uppercase channel key
     * @param electronic whether the channel submits to a partner
     */
    RxChannel(String wire, boolean electronic) {
        this.wire = wire;
        this.electronic = electronic;
    }

    /**
     * Return the wire key stored in approved configuration and recorded on the prescription.
     *
     * @return stable uppercase channel key
     */
    public String wire() {
        return wire;
    }

    /**
     * Return whether this channel submits to a partner.
     *
     * @return {@code true} for an electronic prescribing channel
     */
    public boolean isElectronic() {
        return electronic;
    }

    /**
     * Parse a channel wire key.
     *
     * @param value stored key
     * @return the matching channel
     * @throws IllegalArgumentException when the key is unknown
     */
    public static RxChannel from(String value) {
        for (RxChannel candidate : values()) {
            if (candidate.wire.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown prescription channel: " + value);
    }
}
