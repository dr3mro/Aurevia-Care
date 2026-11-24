package com.aurevia.messaging.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Persistence boundary for patient chart links, immutable report revisions and release facts
 * (XCH-06, PAT-02, ARC-02).
 *
 * <p>Every argument carries the active organization so two tenants with identical local
 * identifiers stay isolated. A release decision is appended to an immutable history and
 * atomically updates the current access state; withdrawal and correction never delete or
 * rewrite history. Production uses a tenant-guarded JDBC adapter; a synthetic adapter is
 * used by unit tests.</p>
 */
public interface ReportReleaseStore {

    /**
     * Register the current verified-link state for a patient subject in one organization.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated patient subject
     * @param link           link state, re-read on every protected action
     */
    void putLink(String organizationId, String subject, PatientChartLink link);

    /**
     * Read the current chart link for a subject in one organization.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated patient subject
     * @return the link when one exists in this tenant
     */
    Optional<PatientChartLink> link(String organizationId, String subject);

    /**
     * Store one immutable report revision. Repeating a key never rewrites the revision.
     *
     * @param report report revision to store
     */
    void putReport(ReleasedReport report);

    /**
     * Find one exact revision in one organization.
     *
     * @param organizationId active organization identifier
     * @param id             report identifier
     * @param revision       requested revision
     * @return the revision when present in this tenant
     */
    Optional<ReleasedReport> find(String organizationId, String id, int revision);

    /**
     * List every revision of a patient's reports in one organization, newest first.
     *
     * @param organizationId active organization identifier
     * @param patientId      linked patient identifier
     * @return immutable list of the patient's revisions
     */
    List<ReleasedReport> reportsFor(String organizationId, String patientId);

    /**
     * Return the highest currently released revision effective on a civil date.
     *
     * @param organizationId active organization identifier
     * @param id             report identifier
     * @param effectiveOn    civil date at which access is evaluated
     * @return the latest effective released revision, or empty when none qualifies
     */
    OptionalInt latestReleasedRevision(String organizationId, String id, LocalDate effectiveOn);

    /**
     * Whether a later effective correction supersedes this revision in the same tenant.
     *
     * @param organizationId active organization identifier
     * @param report         revision to test
     * @return {@code true} when a later correction supersedes this revision
     */
    boolean isSuperseded(String organizationId, ReleasedReport report);

    /**
     * Withdraw one exact revision, retaining its history (XCH-06). The appended
     * decision inherits the revision's current policy version because withdrawal
     * is a terminal state change, not a new release authorization.
     *
     * @param organizationId active organization identifier
     * @param id             report identifier
     * @param revision       revision to withdraw
     * @return {@code true} when the revision existed and was withdrawn
     */
    boolean withdraw(String organizationId, String id, int revision);

    /**
     * Append one release decision and make it the current access state in one transaction.
     *
     * <p>The immutable revision, release policy version, effective time and any hold reason
     * and expiry are recorded with the fact (XCH-06). A withdrawn revision is terminal:
     * a later released decision for the same key is rejected rather than restoring access.</p>
     *
     * @param release release fact to append
     * @throws IllegalStateException when the revision was already withdrawn
     */
    void recordRelease(ReportRelease release);

    /**
     * Read the append-only release history of one exact revision, oldest first.
     *
     * @param organizationId active organization identifier
     * @param reportId       report identifier
     * @param revision       revision whose decisions are requested
     * @return immutable list of release facts in insertion order
     */
    List<ReportRelease> releaseHistory(String organizationId, String reportId, int revision);
}
