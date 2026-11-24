package com.aurevia.exchange.conformance;

/**
 * Outcome of applying one partner event, with a stable machine-readable detail.
 *
 * @param disposition the recorded conformance outcome
 * @param detail      short reason code such as {@code duplicate} or {@code outage}
 * @return this record
 */
public record ProcessingResult(Disposition disposition, String detail) {
}
