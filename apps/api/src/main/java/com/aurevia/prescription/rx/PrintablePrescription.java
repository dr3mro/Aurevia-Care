package com.aurevia.prescription.rx;

import java.time.Instant;

/**
 * Immutable, print-ready prescription carrying the prescriber attestation.
 *
 * <p>The document is derived only from a prescription that actually used the
 * locally approved print channel, so the recorded channel is copied verbatim and
 * no submission, delivery or pharmacy acceptance is ever implied. The
 * attestation note is the optional non-sensitive note the prescriber supplied
 * when attesting; the attestation fact and its time are always present.</p>
 *
 * @return the printable prescription value
 */
public record PrintablePrescription(String prescriptionId, String patientId, String medication,
                                    String strength, String dose, String route, String frequency,
                                    int quantity, String prescriberSubject, String destinationPharmacy,
                                    String attestationNote, Instant attestedAt, String channel) {
}
