package com.aurevia.prescription.rx;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the controlled prescription transmission slice.
 *
 * <p>The types here carry no persistence or transport concerns. Each clinically
 * significant fact (prescriber attestation, submission, delivery confirmation,
 * partner-status verification, a retry that reuses the same submission id,
 * pharmacy acceptance or rejection, clarification, renewal, cancellation and
 * dispensing evidence) is a separate immutable event. Acceptance is never
 * dispensing, delivery is never acceptance, and a locally printable prescription
 * is never reported as electronically delivered.</p>
 */
public final class RxContract {

    private RxContract() {
    }

    /**
     * Transmission state of one prescription. A no-op is allowed so a fact that
     * does not move the workflow (for example renewal) can still advance the
     * version. Illegal transitions fail at the service and database boundaries.
     */
    public enum State {
        /** Draft created by the prescribing workflow; not yet attested. */
        DRAFT("draft"),
        /** The prescriber attested and the prescription may be routed. */
        ATTESTED("attested"),
        /** Electronically submitted; a stable submission id exists. */
        SUBMITTED("submitted"),
        /** Submission outcome unknown; retry is blocked until partner status is verified. */
        AMBIGUOUS("ambiguous"),
        /** The partner confirmed delivery of the exact submission id. */
        DELIVERY_CONFIRMED("delivery_confirmed"),
        /** The pharmacy accepted the prescription. */
        ACCEPTED("accepted"),
        /** The pharmacy rejected the prescription. */
        REJECTED("rejected"),
        /** The pharmacy requested clarification; a work item stays open. */
        CLARIFICATION("clarification"),
        /** The pharmacy supplied dispensing evidence, distinct from acceptance. */
        DISPENSED("dispensed"),
        /** A locally approved printable prescription was produced; no electronic delivery is implied. */
        PRINTED("printed"),
        /** Cancelled with a recorded reason. */
        CANCELLED("cancelled");

        private final String wire;

        /**
         * Create a state with its stable wire key.
         *
         * @param wire stable lowercase wire key
         */
        State(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored state key.
         *
         * @param value stored key
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static State from(String value) {
            for (State candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown prescription state: " + value);
        }

        /**
         * Decide whether a transition is permitted. Only prescription-safety
         * forward moves, a no-op and cancellation from a live state are legal;
         * a dispensed, rejected or cancelled prescription cannot silently move
         * to a positive clinical state.
         *
         * @param next candidate state
         * @return {@code true} when the transition is permitted
         */
        public boolean canTransitionTo(State next) {
            if (this == next) {
                return true;
            }
            return switch (this) {
                case DRAFT -> next == ATTESTED || next == CANCELLED;
                case ATTESTED -> next == SUBMITTED || next == AMBIGUOUS || next == PRINTED || next == CANCELLED;
                case SUBMITTED -> next == AMBIGUOUS || next == DELIVERY_CONFIRMED || next == CANCELLED;
                case AMBIGUOUS -> next == SUBMITTED || next == DELIVERY_CONFIRMED || next == CANCELLED;
                case DELIVERY_CONFIRMED -> next == ACCEPTED || next == REJECTED
                        || next == CLARIFICATION || next == CANCELLED;
                case CLARIFICATION -> next == ACCEPTED || next == REJECTED || next == CANCELLED;
                case ACCEPTED -> next == DISPENSED || next == CANCELLED;
                case PRINTED, REJECTED, DISPENSED, CANCELLED -> false;
            };
        }
    }

    /**
     * Append-only fact vocabulary. Every entry is a distinct event and is never
     * merged into another fact; acceptance and dispensing are deliberately
     * separate types.
     */
    public enum EventType {
        /** The prescriber attested to the exact draft revision. */
        ATTESTED("attested"),
        /** The prescription was submitted with a stable submission id. */
        SUBMITTED("submitted"),
        /** The partner confirmed delivery of the exact submission id. */
        DELIVERY_CONFIRMED("delivery_confirmed"),
        /** The partner was queried about an ambiguous submission. */
        PARTNER_STATUS_VERIFIED("partner_status_verified"),
        /** A locally approved printable prescription was produced. */
        PRINTED("printed"),
        /** The pharmacy accepted the prescription. */
        PHARMACY_ACCEPTED("pharmacy_accepted"),
        /** The pharmacy rejected the prescription with a reason. */
        PHARMACY_REJECTED("pharmacy_rejected"),
        /** The pharmacy requested clarification. */
        CLARIFICATION("clarification"),
        /** A renewal linked to the original submission was recorded. */
        RENEWAL("renewal"),
        /** The prescription was cancelled with a reason. */
        CANCELLED("cancelled"),
        /** The pharmacy supplied evidence that the medicine was dispensed. */
        DISPENSING_EVIDENCE("dispensing_evidence");

        private final String wire;

        /**
         * Create an event type with its stable wire key.
         *
         * @param wire stable lowercase wire key
         */
        EventType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase event key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored event key.
         *
         * @param value stored key
         * @return the matching event type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static EventType from(String value) {
            for (EventType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown prescription event: " + value);
        }
    }

    /**
     * Current, mutable projection of one prescription.
     *
     * @return the immutable value
     */
    public record Prescription(String id, int version, String patientId, String medication, String strength,
                               String dose, String route, String frequency, int quantity,
                               String prescriberSubject, String destinationPharmacy, State state,
                               String channel, String submissionId, boolean retryAllowed, Instant createdAt) {
    }

    /**
     * One immutable fact in a prescription's transmission trail.
     *
     * @return the immutable value
     */
    public record Event(String id, String prescriptionId, int prescriptionVersion, EventType eventType,
                        String actorId, String channel, String submissionId, String outcome,
                        String detail, Instant occurredAt) {
    }

    /**
     * One prescription together with its complete event trail.
     *
     * @return the immutable value
     */
    public record View(Prescription prescription, List<Event> events) {
        /**
         * Defensively copy the event trail. Components are documented once on
         * the record declaration; repeating them here yields duplicate Doxygen
         * parameter sections and fails the documentation gate.
         */
        public View {
            events = List.copyOf(events);
        }

        /**
         * Return an independent snapshot of the immutable event trail.
         *
         * @return copied event list
         */
        @Override
        public List<Event> events() {
            return List.copyOf(events);
        }
    }

    /**
     * Register a transmission record for a prescription draft.
     *
     * <p>The draft id is supplied by the caller and is used as the local opaque
     * id; V44 deliberately holds no foreign key to the prescription table so the two
     * stories remain independently mergeable. The medication fields are a
     * bounded snapshot needed for attestation, not a new draft source.</p>
     *
     * @param prescriptionId identifier of the prescription draft being routed
     * @param patientId          owning patient
     * @param medication         medication name
     * @param strength           medication strength
     * @param dose               dose per administration
     * @param route              administration route
     * @param frequency          administration frequency
     * @param quantity           prescribed quantity
     * @param prescriberSubject  prescribing clinician subject
     * @param destinationPharmacy destination pharmacy key, or {@code null}
     * @return the registration command
     */
    public record CreateRequest(String prescriptionId, String patientId, String medication, String strength,
                                String dose, String route, String frequency, Integer quantity,
                                String prescriberSubject, String destinationPharmacy) {
    }

    /**
     * Record the prescriber's attestation.
     *
     * @param note non-sensitive attestation note
     * @return the attestation command
     */
    public record AttestRequest(String note) {
    }

    /**
     * Route an attested prescription through an approved channel.
     *
     * @param channel approved channel key
     * @param outcome transmission outcome for the channel
     * @return the transmission command
     */
    public record TransmitRequest(String channel, String outcome) {
    }

    /**
     * Confirm delivery of a stable submission id.
     *
     * @param submissionId stable submission id
     * @return the confirmation command
     */
    public record ConfirmRequest(String submissionId) {
    }

    /**
     * Verify partner status for an ambiguous submission before any retry.
     *
     * <p>The status is three-valued: only {@code absent} authorizes a retry;
     * {@code present} reconciles to SUBMITTED and {@code unknown} keeps the
     * prescription in the reconciliation state with retry disabled.</p>
     *
     * @param submissionId stable submission id
     * @param status       present, absent or unknown
     * @return the verification command
     */
    public record VerifyRequest(String submissionId, String status) {
    }

    /**
     * Retry a verified-absent submission, reusing the original submission id.
     *
     * <p>The outcome is supplied by the channel adapter (synthetic in this
     * slice): a retry may itself be ambiguous, which bounds the number of send
     * facts before the submission is dead-lettered for review.</p>
     *
     * @param submissionId stable submission id
     * @param outcome      submitted or ambiguous
     * @return the retry command
     */
    public record RetryRequest(String submissionId, String outcome) {
    }

    /**
     * Record a pharmacy response.
     *
     * @param submissionId stable submission id
     * @param outcome      accepted, rejected or clarification
     * @param note         non-sensitive reason or detail
     * @return the pharmacy response command
     */
    public record PharmacyResponseRequest(String submissionId, String outcome, String note) {
    }

    /**
     * Record partner-supplied dispensing evidence, distinct from acceptance.
     *
     * @param submissionId stable submission id
     * @param note         non-sensitive evidence detail
     * @return the dispensing command
     */
    public record DispensingRequest(String submissionId, String note) {
    }

    /**
     * Record a renewal linked to the original submission.
     *
     * @param note non-sensitive renewal note
     * @return the renewal command
     */
    public record RenewRequest(String note) {
    }

    /**
     * Cancel a prescription with a reason.
     *
     * @param reason cancellation reason
     * @return the cancellation command
     */
    public record CancelRequest(String reason) {
    }
}
