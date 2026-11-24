package com.aurevia.radiology;

import java.time.Instant;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.Urgency;

/**
 * One immutable imaging order revision (RAD-01, ARC-01).
 *
 * <p>Every command appends a new revision sharing the tenant-local order
 * identifier; the prior snapshot is never edited. Ownership and patient
 * attribution live on every revision so a study cannot be moved between tenants
 * or patients by a projection. The external study is represented only by its
 * Study Instance UID and a partner workstation reference; image pixels stay
 * under partner access control.</p>
 *
 * @return this record
 */
public record ImagingOrder(String organizationId, String orderId, String patientId, String encounterId,
                           String orderCode, Urgency urgency, String clinicalQuestion, String sourceActor,
                           String destination, String responsibleClinician, String workQueue,
                           String referrer, OrderState state, int version, Instant scheduledAt,
                           Modality modality, String accession, String accessionIssuer,
                           String studyInstanceUid, String workstationRef, String verifiedPatientId,
                           String verifiedAccession) {

    /**
     * Validate the immutable ownership and version invariants of a revision.
     *
     * @throws ApiException when tenant, order identity or version is invalid
     */
    public ImagingOrder {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ApiException(422, "INVALID_IMAGING_ORDER", "active organization is required");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new ApiException(422, "INVALID_IMAGING_ORDER", "order identifier is required");
        }
        if (version < 1) {
            throw new ApiException(422, "INVALID_IMAGING_ORDER", "order version must be positive");
        }
    }
}
