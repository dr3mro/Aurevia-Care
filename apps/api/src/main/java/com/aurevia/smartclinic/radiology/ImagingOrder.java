package com.aurevia.smartclinic.radiology;

import java.time.Instant;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.radiology.RadTypes.Modality;
import com.aurevia.smartclinic.radiology.RadTypes.OrderState;
import com.aurevia.smartclinic.radiology.RadTypes.Urgency;

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
 * @param organizationId       owning tenant
 * @param orderId              tenant-local opaque order identifier
 * @param patientId            patient identifier in the owning tenant
 * @param encounterId          source encounter identifier, or {@code null}
 * @param orderCode            local order code for the imaging study
 * @param urgency              recorded clinical urgency
 * @param clinicalQuestion     clinical question documented at intake
 * @param sourceActor          actor that authored the order
 * @param destination          receiving radiology destination
 * @param responsibleClinician named responsible clinician or work queue owner
 * @param workQueue            responsible work queue
 * @param referrer             clinician to notify about signed revisions
 * @param state                lifecycle state of this revision
 * @param version              monotonic revision version
 * @param scheduledAt          scheduled study time, or {@code null} before scheduling
 * @param modality             approved modality, or {@code null} before scheduling
 * @param accession            issuer-scoped accession, or {@code null} before accessioning
 * @param accessionIssuer      issuer of the accession, or {@code null}
 * @param studyInstanceUid     external DICOM Study Instance UID, or {@code null}
 * @param workstationRef       partner diagnostic workstation reference, or {@code null}
 * @param verifiedPatientId    patient identifier matched at study association, or {@code null}
 * @param verifiedAccession    accession matched at study association, or {@code null}
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
