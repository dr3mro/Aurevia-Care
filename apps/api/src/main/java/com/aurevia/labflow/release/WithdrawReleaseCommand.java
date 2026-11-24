package com.aurevia.labflow.release;

import com.aurevia.api.ApiException;

/**
 * Command that withdraws an issued report as entered in error (LAB-02, CLN-05, XCH-06).
 *
 * @param releaseId       release being withdrawn
 * @param expectedVersion current version precondition
 * @param reason          non-sensitive reason for the withdrawal
 * @return this command
 */
public record WithdrawReleaseCommand(String releaseId, int expectedVersion, String reason) {

    /**
     * Validate the identifiers, version and reason at the trust boundary.
     *
     * @throws ApiException {@code 422} when any required field is missing or invalid
     */
    public WithdrawReleaseCommand {
        if (releaseId == null || releaseId.isBlank()) {
            throw new ApiException(422, "INVALID_RELEASE_ID", "a release identifier is required");
        }
        if (expectedVersion < 1) {
            throw new ApiException(422, "INVALID_VERSION", "version must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(422, "REASON_REQUIRED", "a withdrawal reason is required");
        }
    }
}
