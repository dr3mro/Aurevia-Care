package com.aurevia.prescription.rx;

import java.util.Optional;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.rx.RxContract.Event;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxContract.View;

/**
 * Builds a print-ready prescription from the immutable transmission trail (RX-02).
 *
 * <p>This unit decides only whether the prescription itself can be printed: it
 * must have taken the locally approved print channel and carry the prescriber's
 * attestation. Market entitlement and channel approval are re-checked by the
 * domain service immediately before this document is produced, so a revocation
 * between attestation and printing fails closed. The output never invents an
 * electronic submission or a pharmacy outcome.</p>
 */
public final class LocalPrescriptionPrint {

    /**
     * Produce the printable document for one prescription.
     *
     * @param view the prescription and its complete event trail
     * @return the immutable print-ready prescription
     * @throws ApiException when the prescription was not printed through the local
     *         channel or carries no prescriber attestation
     */
    public PrintablePrescription issue(View view) {
        Prescription prescription = view.prescription();
        if (prescription.state() != State.PRINTED) {
            throw new ApiException(409, "PRINT_NOT_AVAILABLE",
                    "only a locally printed prescription can be produced as a printable document");
        }
        String localChannel = RxChannel.PRINTABLE_LOCAL.wire();
        if (!localChannel.equals(prescription.channel())) {
            throw new ApiException(422, "CHANNEL_MISMATCH",
                    "the recorded channel is not the locally approved print channel");
        }
        Optional<Event> attestation = view.events().stream()
                .filter(event -> event.eventType() == EventType.ATTESTED)
                .findFirst();
        if (attestation.isEmpty()) {
            throw new ApiException(409, "ATTESTATION_REQUIRED",
                    "a printable prescription requires the prescriber attestation");
        }
        Event attested = attestation.get();
        return new PrintablePrescription(prescription.id(), prescription.patientId(),
                prescription.medication(), prescription.strength(), prescription.dose(),
                prescription.route(), prescription.frequency(), prescription.quantity(),
                prescription.prescriberSubject(), prescription.destinationPharmacy(),
                attested.detail(), attested.occurredAt(), prescription.channel());
    }
}
