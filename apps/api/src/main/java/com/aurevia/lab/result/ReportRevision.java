package com.aurevia.lab.result;

import com.aurevia.lab.result.LabTypes.VerificationState;

import java.time.Instant;
import java.util.List;

import com.aurevia.api.ApiException;

/**
 * One immutable laboratory report revision (LAB-02, CLN-05, ARC-01).
 *
 * <p>Revisions are appended, never edited. A correction or an entered-in-error
 * links back to the version it supersedes while keeping the prior content,
 * author, verifier and effective time intact. Ownership columns are part of the
 * record so a projection cannot be moved between tenants.</p>
 *
 * @return this record
 */
public record ReportRevision(String organizationId, String reportId, int version,
                             Integer supersedesVersion, String patientId, String specimenId,
                             String orderId, VerificationState state, List<LabObservation> observations,
                             String actor, String verifier, Instant effectiveAt, String reason,
                             Integer rangeVersion, Integer criticalPolicyVersion) {

    /**
     * Validate the ownership, version and observation invariants and copy the list.
     *
     * @throws ApiException when a required field is missing or the link points at a later version
     */
    public ReportRevision {
        if (organizationId == null || organizationId.isBlank() || reportId == null || reportId.isBlank()
                || version < 1 || (supersedesVersion != null && supersedesVersion >= version)
                || patientId == null || patientId.isBlank()
                || specimenId == null || specimenId.isBlank()
                || orderId == null || orderId.isBlank() || state == null || observations == null
                || actor == null || actor.isBlank()) {
            throw new ApiException(422, "INVALID_REVISION", "invalid report revision");
        }
        observations = List.copyOf(observations);
    }

    /**
     * Return the immutable observations of this revision.
     *
     * @return observations in capture order
     */
    @Override
    public List<LabObservation> observations() {
        return List.copyOf(observations);
    }
}
