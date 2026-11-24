package com.aurevia.radflow.worklist;

import java.time.Instant;

import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;
import com.aurevia.radflow.worklist.WorklistTypes.WorklistStatus;

/**
 * One read-model row of the performing-site worklist (RAD-01).
 *
 * <p>The entry deliberately exposes only the fields a performing-site user needs
 * to pick up and progress a study. It never carries the full clinical narrative
 * or any image data.</p>
 *
 * @return this record
 */
public record WorklistEntry(String orderId, String performingSiteId, String patientId, String orderCode,
                            Urgency urgency, WorklistStatus status, int version, Instant scheduledAt,
                            Modality modality, String accession, String accessionIssuer,
                            String studyInstanceUid) {

    /**
     * Project the current revision of an order onto a worklist row.
     *
     * @param order current order revision
     * @return the bounded worklist entry
     */
    public static WorklistEntry from(WorklistOrder order) {
        return new WorklistEntry(order.orderId(), order.performingSiteId(), order.patientId(),
                order.orderCode(), order.urgency(), order.status(), order.version(),
                order.scheduledAt(), order.modality(), order.accession(), order.accessionIssuer(),
                order.studyInstanceUid());
    }
}
