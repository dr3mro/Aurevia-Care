package com.aurevia.exchange.hl7;

/**
 * Sender-side facts that cannot be carried in a clinical ORM payload.
 *
 * <p>XCH-01 requires the sending organization to authorize the specific payload,
 * recipient, purpose and duration. Those facts are supplied by the caller from
 * its authorization store rather than trusted from the wire; the mapper checks
 * them before producing an order command.</p>
 *
 * @param sendingFacility sending facility written to MSH-4
 * @param workQueue       destination work queue that receives the exchange item
 * @param purpose         approved business purpose of the disclosure
 * @param authorizationId identifier of the sender's approved authorization
 * @param durationMinutes authorized validity of the released content
 */
public record Hl7SenderContext(String sendingFacility, String workQueue, String purpose,
                               String authorizationId, int durationMinutes) {
}
