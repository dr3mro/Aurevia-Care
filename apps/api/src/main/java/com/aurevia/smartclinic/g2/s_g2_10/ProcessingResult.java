package com.aurevia.smartclinic.g2.s_g2_10;

/**
 * Outcome of applying one partner event, with a stable machine-readable detail.
 *
 * @param disposition the recorded conformance outcome
 * @param detail      short reason code such as {@code duplicate} or {@code outage}
 */
public record ProcessingResult(Disposition disposition, String detail) {
}
