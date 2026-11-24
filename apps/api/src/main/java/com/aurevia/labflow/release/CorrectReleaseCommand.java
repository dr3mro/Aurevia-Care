package com.aurevia.labflow.release;

import com.aurevia.api.ApiException;

/**
 * Command that supersedes a released report revision with a corrected one (LAB-02, XCH-06).
 *
 * @param releaseId               release being corrected
 * @param expectedVersion         current version precondition
 * @param correctedRevisionId     immutable identifier of the replacement revision
 * @param correctedRevisionVersion version of the replacement revision
 * @param reason                  non-sensitive description of what changed and why
 * @return this command
 */
public record CorrectReleaseCommand(String releaseId, int expectedVersion, String correctedRevisionId,
                                    int correctedRevisionVersion, String reason) {

    /**
     * Validate the identifiers, version and reason at the trust boundary.
     *
     * @throws ApiException {@code 422} when any required field is missing or invalid
     */
    public CorrectReleaseCommand {
        if (releaseId == null || releaseId.isBlank()) {
            throw new ApiException(422, "INVALID_RELEASE_ID", "a release identifier is required");
        }
        if (expectedVersion < 1) {
            throw new ApiException(422, "INVALID_VERSION", "version must be positive");
        }
        if (correctedRevisionId == null || correctedRevisionId.isBlank()) {
            throw new ApiException(422, "INVALID_REVISION_ID", "a corrected revision identifier is required");
        }
        if (correctedRevisionVersion < 1) {
            throw new ApiException(422, "INVALID_REVISION_VERSION", "corrected revision version must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(422, "REASON_REQUIRED", "a correction reason is required");
        }
    }
}
