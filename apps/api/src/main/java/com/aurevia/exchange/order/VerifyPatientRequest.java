package com.aurevia.exchange.order;

/**
 * Receiver command that binds a locally verified patient to an exchange work item.
 *
 * <p>The local patient is accepted only when its issuer-scoped MRN matches the
 * MRN projected with the work item and the receiver records an attestation. A
 * demographic resemblance alone cannot bind a chart.</p>
 *
 * @param localPatientId patient identifier in the receiving organization; never a sender reference
 * @param localMrn       MRN the receiver verified for that local patient
 * @param mrnIssuer      issuer of the verified MRN
 * @param attestation   receiver attestation of the identity check
 * @return this record
 */
public record VerifyPatientRequest(String localPatientId, String localMrn, String mrnIssuer,
                                   String attestation) {
}
