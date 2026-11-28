package com.aurevia.pharmacyflow.status;

import java.time.Instant;
import java.util.List;

import com.aurevia.prescription.rx.RxContract.EventType;

/**
 * Read model for the pharmacy status fact trail.
 *
 * <p>The types here carry no persistence or transport concerns. Each clinically
 * significant pharmacy response is a separate immutable event that is never
 * merged into another fact: acceptance, rejection, clarification and dispensing
 * evidence stay distinct. Dispensing evidence records who dispensed, when and
 * what was handed over, and is never represented as an acceptance.</p>
 */
public final class PharmacyStatusContract {

    private PharmacyStatusContract() {
    }

    /**
     * One immutable pharmacy status fact in a prescription's trail.
     *
     * <p>{@code detail} carries the rejection reason or the clarification
     * question. The dispensing fields are populated only for a dispensing fact;
     * a non-dispensing fact leaves them null so the two can never be confused.</p>
     *
     * @return this immutable record
     */
    public record StatusEvent(String id, String organizationId, String prescriptionId, int version,
                              EventType type, String actorId, Instant occurredAt, String detail,
                              String dispensedBy, Instant dispensedAt, String handedOver) {
    }

    /**
     * Current pharmacy status projection and its complete fact trail.
     *
     * <p>{@code accepted} is derived only from the presence of a pharmacy
     * acceptance fact; a dispensing fact never sets it. {@code version} is the
     * next expected {@code If-Match} version. The list is frozen against caller
     * mutation so a projection produced inside a protected boundary cannot be
     * changed by the caller.</p>
     *
     * @return this immutable record
     */
    public record StatusView(String organizationId, String prescriptionId, int version,
                             boolean accepted, boolean dispensed, List<StatusEvent> events) {

        /**
         * Freeze the fact trail against caller mutation.
         */
        public StatusView {
            events = List.copyOf(events);
        }

        /**
         * Return an independent snapshot of the immutable fact trail.
         *
         * @return the pharmacy status facts in append order
         */
        @Override
        public List<StatusEvent> events() {
            return List.copyOf(events);
        }
    }
}
