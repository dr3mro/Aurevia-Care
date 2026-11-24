package com.aurevia.printing;

import java.util.Set;

import com.aurevia.api.ApiException;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.result.LabTypes.VerificationState;
import com.aurevia.prescription.rx.RxChannel;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.printing.HandoutContract.HandoutSource;
import com.aurevia.radiology.RadTypes.OrderState;

/**
 * Gate deciding whether a source revision may become a patient handout (CLN-07).
 *
 * <p>The allowed state sets are built from the owning domain vocabularies so a
 * rule is not copied as a free-text string. A prescription must be beyond draft
 * and ambiguous and carry an approved BUILD-10 print channel. An order must be
 * placed, and signed content must carry a signature. Every refusal is a
 * machine-readable conflict with a non-sensitive message.</p>
 */
public final class HandoutPolicy {

    private static final Set<String> PRESCRIPTION_STATES = Set.of(
            State.ATTESTED.wire(), State.SUBMITTED.wire(), State.DELIVERY_CONFIRMED.wire(),
            State.ACCEPTED.wire(), State.CLARIFICATION.wire(), State.DISPENSED.wire(),
            State.PRINTED.wire());
    private static final Set<String> LAB_ORDER_STATES = Set.of(
            AccessionState.ACCESSIONED.wire(), AccessionState.COLLECTED.wire(),
            AccessionState.RECEIVED.wire());
    private static final Set<String> IMAGING_ORDER_STATES = Set.of(
            OrderState.INTAKE.wire(), OrderState.SCHEDULED.wire(), OrderState.ACCESSIONED.wire(),
            OrderState.STUDY_ASSOCIATED.wire(), OrderState.SIGNED.wire(), OrderState.ADDENDED.wire());
    private static final Set<String> SIGNED_CONTENT_STATES = Set.of(
            NoteState.SIGNED.wire(), NoteState.ADDENDUM.wire());
    private static final Set<String> SIGNED_REPORT_STATES = Set.of(
            VerificationState.FINAL.wire(), VerificationState.CORRECTED.wire(),
            OrderState.SIGNED.wire(), OrderState.ADDENDED.wire());

    private HandoutPolicy() {
    }

    /**
     * Require that the source is in a printable state and carries the required
     * signature or approved channel.
     *
     * @param source exact source revision selected for printing
     * @throws ApiException with 409 when the source is not printable
     */
    public static void requirePrintable(HandoutSource source) {
        if (!allowedStates(source.kind()).contains(source.state())) {
            throw new ApiException(409, "SOURCE_NOT_PLACED",
                    "this " + source.kind().wire() + " state cannot be printed as a handout");
        }
        if (requiresAttestation(source.kind()) && !source.attested()) {
            throw new ApiException(409, "SOURCE_NOT_SIGNED",
                    "only a signed or attested " + source.kind().wire() + " can be printed");
        }
        if (source.kind() == HandoutKind.PRESCRIPTION && !approvedPrintChannel(source.printChannel())) {
            throw new ApiException(409, "PRINT_CHANNEL_NOT_APPROVED",
                    "the prescription has no approved print channel");
        }
    }

    private static Set<String> allowedStates(HandoutKind kind) {
        if (kind == HandoutKind.PRESCRIPTION) {
            return PRESCRIPTION_STATES;
        }
        if (kind == HandoutKind.LAB_ORDER) {
            return LAB_ORDER_STATES;
        }
        if (kind == HandoutKind.IMAGING_ORDER) {
            return IMAGING_ORDER_STATES;
        }
        if (kind == HandoutKind.CHART_SUMMARY) {
            return SIGNED_CONTENT_STATES;
        }
        return SIGNED_REPORT_STATES;
    }

    private static boolean requiresAttestation(HandoutKind kind) {
        return kind == HandoutKind.PRESCRIPTION
                || kind == HandoutKind.CHART_SUMMARY
                || kind == HandoutKind.SIGNED_REPORT;
    }

    private static boolean approvedPrintChannel(String channel) {
        if (channel == null) {
            return false;
        }
        for (RxChannel candidate : RxChannel.values()) {
            if (candidate.wire().equals(channel)) {
                return true;
            }
        }
        return false;
    }
}
