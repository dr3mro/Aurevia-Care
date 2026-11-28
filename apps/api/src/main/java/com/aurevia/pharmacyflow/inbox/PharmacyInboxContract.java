package com.aurevia.pharmacyflow.inbox;

import java.time.Instant;
import java.util.List;

import com.aurevia.prescription.rx.RxContract.State;

/**
 * Read model for the pharmacy inbox.
 *
 * <p>The projection holds only the bounded prescription snapshot a pharmacy needs
 * to review and dispense an explicitly routed item. It deliberately contains no
 * chart entry, allergy, diagnosis or note field: sharing a routed prescription
 * grants no general chart access. The patient identifier is an opaque reference
 * to the dispensing context, never a chart payload.</p>
 */
public final class PharmacyInboxContract {

    private PharmacyInboxContract() {
    }

    /**
     * One prescription explicitly routed to the reading pharmacy.
     *
     * @return the immutable routed-prescription projection
     */
    public record InboxItem(String id, int version, String patientId, String medication, String strength,
                            String dose, String route, String frequency, int quantity, State state,
                            String submissionId, String prescriberSubject, String destinationPharmacy,
                            Instant createdAt) {
    }

    /**
     * One cursor-paginated page of routed prescriptions, newest first.
     *
     * <p>The {@code nextCursor} is the opaque tenant-bound cursor of the last
     * returned row, or {@code null} on the final page. The list is frozen so a
     * caller cannot mutate a projection produced inside a protected transaction.</p>
     *
     * @return the immutable page
     */
    public record InboxPage(List<InboxItem> items, String nextCursor) {

        /**
         * Freeze the page items against caller mutation.
         *
         * @throws NullPointerException when items is null
         */
        public InboxPage {
            items = List.copyOf(items);
        }

        /**
         * Return an independent snapshot of the immutable items.
         *
         * @return the routed prescriptions on this page
         */
        @Override
        public List<InboxItem> items() {
            return List.copyOf(items);
        }
    }
}
