package com.aurevia.messaging.reports;

/**
 * The locally verified association between a patient account subject and one chart (IAM-05, PAT-02).
 *
 * <p>Both flags are re-read on every protected action: a link that is no longer verified, or that has
 * been unlinked, denies the next request without relying on a cached token or session.</p>
 *
 * @param patientId opaque identifier of the linked chart
 * @param verified  whether the link completed site verification and re-verification
 * @param active    whether the link is still active (false after an unlink)
 * @return this record
 */
public record PatientChartLink(String patientId, boolean verified, boolean active) {
}
