package com.aurevia.exchange.conformance;

/**
 * Conformance wire adapter that carries a synthetic partner event to a wire
 * format and back without losing identity, ordering or clinical payload.
 *
 * <p>The FHIR and HL7 implementations reuse the same wire primitives as the
 * exchange adapters (the FHIR JSON factory and the HL7 delimiters and escape
 * codec), so a fixture replay exercises the real partner encodings. Each
 * adapter is stateless and deterministic.</p>
 */
public interface PartnerEventAdapter {

    /**
     * Stable wire name of the adapter.
     *
     * @return {@code fhir} or {@code hl7}
     */
    String name();

    /**
     * Encode one event into its wire representation.
     *
     * @param event event to encode
     * @return wire text carrying every event field
     */
    String encode(PartnerEvent event);

    /**
     * Decode one wire representation into an event.
     *
     * @param wire wire text previously produced by {@code encode}
     * @return the decoded event
     */
    PartnerEvent decode(String wire);

    /**
     * Encode and immediately decode an event, as a partner wire round trip does.
     *
     * @param event event to carry across the wire
     * @return the reconstructed event
     */
    default PartnerEvent roundTrip(PartnerEvent event) {
        return decode(encode(event));
    }
}
