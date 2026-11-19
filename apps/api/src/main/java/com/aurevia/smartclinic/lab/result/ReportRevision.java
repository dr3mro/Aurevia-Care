package com.aurevia.smartclinic.lab.result;

import com.aurevia.smartclinic.lab.result.LabTypes.VerificationState;

import java.time.Instant;
import java.util.List;

import com.aurevia.smartclinic.api.ApiException;

/**
 * One immutable laboratory report revision (LAB-02, CLN-05, ARC-01).
 *
 * <p>Revisions are appended, never edited. A correction or an entered-in-error
 * links back to the version it supersedes while keeping the prior content,
 * author, verifier and effective time intact. Ownership columns are part of the
 * record so a projection cannot be moved between tenants.</p>
 *
 * @param organizationId       owning tenant
 * @param reportId             tenant-local opaque report identifier
 * @param version              monotonic revision version
 * @param supersedesVersion    prior revision version, or {@code null} for the first revision
 * @param patientId            patient identifier in the owning tenant
 * @param specimenId           specimen identifier verified at issue
 * @param orderId              source order identifier matched at issue
 * @param state                verification state of this revision
 * @param observations         immutable observation list
 * @param actor                 authenticated subject who created this revision
 * @param verifier             authenticated verifier, or {@code null} before issue
 * @param effectiveAt          clinical effective time of this revision, or {@code null}
 * @param reason               required reason for a correction or entered-in-error
 * @param rangeVersion         approved reference-range version applied at issue
 * @param criticalPolicyVersion approved critical-policy version applied at issue
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
