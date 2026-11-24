package com.aurevia.labflow.release;

import com.aurevia.api.ApiException;

/**
 * Command that releases one immutable laboratory report revision (XCH-06, PAT-02).
 *
 * <p>The tenant, actor and issuing organization are derived from the guarded request
 * context; only the referenced immutable revision, the linked patient and the facility
 * policy travel on this command.</p>
 *
 * @param reportId        tenant-local report or specimen identifier
 * @param revisionId      immutable revision identifier released to the patient
 * @param revisionVersion version of that revision
 * @param patientId       linked patient whose chart receives the release
 * @param policy          facility release policy applied to the decision
 * @return this command
 */
public record ReleaseReportCommand(String reportId, String revisionId, int revisionVersion,
                                   String patientId, FacilityReleasePolicy policy) {

    /**
     * Validate the identifiers and policy at the trust boundary.
     *
     * @throws ApiException {@code 422} when any required field is missing or invalid
     */
    public ReleaseReportCommand {
        if (reportId == null || reportId.isBlank()) {
            throw new ApiException(422, "INVALID_REPORT_ID", "a report identifier is required");
        }
        if (revisionId == null || revisionId.isBlank()) {
            throw new ApiException(422, "INVALID_REVISION_ID", "a revision identifier is required");
        }
        if (revisionVersion < 1) {
            throw new ApiException(422, "INVALID_REVISION_VERSION", "revision version must be positive");
        }
        if (patientId == null || patientId.isBlank()) {
            throw new ApiException(422, "INVALID_PATIENT_ID", "a patient identifier is required");
        }
        if (policy == null) {
            throw new ApiException(422, "INVALID_POLICY", "a facility release policy is required");
        }
    }
}
