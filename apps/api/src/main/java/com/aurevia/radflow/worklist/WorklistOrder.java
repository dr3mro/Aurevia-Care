package com.aurevia.radflow.worklist;

import java.time.Instant;

import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;
import com.aurevia.radflow.worklist.WorklistTypes.WorklistStatus;

/**
 * One immutable performing-site worklist revision.
 *
 * <p>Every command appends a new revision sharing the tenant-local order
 * identifier; the prior snapshot is never edited. The performing site is carried
 * on every revision so a worklist cannot leak an order to another site. The
 * external study is represented only by its Study Instance UID and a partner
 * workstation reference; image pixels stay under partner access control.</p>
 *
 * @return this record
 */
public record WorklistOrder(String organizationId, String orderId, String performingSiteId,
                            String patientId, String orderCode, Urgency urgency, String clinicalQuestion,
                            String referrer, WorklistStatus status, int version, Instant scheduledAt,
                            Modality modality, String accession, String accessionIssuer,
                            String studyInstanceUid, String workstationRef, String verifiedPatientId,
                            String verifiedAccession, Instant performedAt, Instant reportedAt) {
}
