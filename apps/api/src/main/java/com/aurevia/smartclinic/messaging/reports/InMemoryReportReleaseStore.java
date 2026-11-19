package com.aurevia.smartclinic.messaging.reports;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Synthetic, thread-safe store for patient links and report revisions (PAT-02, ARC-02).
 *
 * <p>This is the bounded persistence boundary for the slice. Every key includes the organization so
 * two tenants with identical local identifiers cannot collide. A production adapter can replace it
 * without changing the authorization rules in
 * {@link com.aurevia.smartclinic.messaging.reports.ReleasedReportService}.</p>
 */
public final class InMemoryReportReleaseStore {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{2,64}");

    private final ConcurrentMap<String, PatientChartLink> links = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReleasedReport> reports = new ConcurrentHashMap<>();

    /**
     * Register a verified patient link for a subject in one organization.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated patient subject
     * @param link           link state, re-read on every protected action
     * @throws IllegalArgumentException when any input is malformed or missing
     */
    public void putLink(String organizationId, String subject, PatientChartLink link) {
        if (organizationId == null || !UUID_PATTERN.matcher(organizationId).matches()) {
            throw new IllegalArgumentException("invalid organization");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("invalid subject");
        }
        if (link == null) {
            throw new IllegalArgumentException("missing link");
        }
        links.put(linkKey(organizationId, subject), link);
    }

    /**
     * Read the current link for a subject in one organization.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated patient subject
     * @return the link when one exists in this tenant
     */
    public Optional<PatientChartLink> link(String organizationId, String subject) {
        return Optional.ofNullable(links.get(linkKey(organizationId, subject)));
    }

    /**
     * Store one report revision. Inputs are validated because they cross the synthetic boundary.
     *
     * @param report report revision to store
     * @throws IllegalArgumentException when the revision is malformed
     */
    public void putReport(ReleasedReport report) {
        if (report == null) {
            throw new IllegalArgumentException("missing report");
        }
        if (!UUID_PATTERN.matcher(report.organizationId()).matches()) {
            throw new IllegalArgumentException("invalid organization");
        }
        if (!ID_PATTERN.matcher(report.id()).matches()) {
            throw new IllegalArgumentException("invalid report id");
        }
        if (report.revision() < 1) {
            throw new IllegalArgumentException("invalid revision");
        }
        try {
            LocalDate.parse(report.effectiveDate());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("invalid effective date");
        }
        if (report.status() == null) {
            throw new IllegalArgumentException("missing status");
        }
        reports.put(reportKey(report.organizationId(), report.id(), report.revision()), report);
    }

    /**
     * Find one exact revision in one organization.
     *
     * @param organizationId active organization identifier
     * @param id             report identifier
     * @param revision       requested revision
     * @return the revision when present in this tenant
     */
    public Optional<ReleasedReport> find(String organizationId, String id, int revision) {
        return Optional.ofNullable(reports.get(reportKey(organizationId, id, revision)));
    }

    /**
     * List every revision of a patient's reports in one organization, newest first.
     *
     * @param organizationId active organization identifier
     * @param patientId      linked patient identifier
     * @return immutable list of the patient's revisions
     */
    public List<ReleasedReport> reportsFor(String organizationId, String patientId) {
        return reports.values().stream()
                .filter(report -> organizationId.equals(report.organizationId()))
                .filter(report -> patientId.equals(report.patientId()))
                .sorted(Comparator.comparing(ReleasedReport::effectiveDate).reversed()
                        .thenComparing(ReleasedReport::id).thenComparingInt(ReleasedReport::revision))
                .toList();
    }

    /**
     * Return the highest currently released revision for a report in one organization.
     *
     * Only released revisions that are effective on the given civil date count as current, so a
     * future-dated release never makes today's accessible revision stale.
     *
     * @param organizationId active organization identifier
     * @param id             report identifier
     * @param effectiveOn    civil date at which access is evaluated
     * @return the latest effective released revision, or empty when none qualifies
     */
    public OptionalInt latestReleasedRevision(String organizationId, String id, LocalDate effectiveOn) {
        return reports.values().stream()
                .filter(report -> organizationId.equals(report.organizationId()))
                .filter(report -> id.equals(report.id()))
                .filter(report -> report.status() == ReleasedReport.Status.RELEASED)
                .filter(report -> !LocalDate.parse(report.effectiveDate()).isAfter(effectiveOn))
                .mapToInt(ReleasedReport::revision)
                .max();
    }

    /**
     * Whether a revision has been replaced by a later correction in the same tenant.
     *
     * <p>A correction that was released and later withdrawn still supersedes its predecessor: the
     * withdrawn content must not be replaced by silently re-serving the older revision. A held
     * correction has not taken effect yet, so it does not hide the current release.</p>
     *
     * @param organizationId active organization identifier
     * @param report         revision to test
     * @return {@code true} when a later effective correction supersedes this revision
     */
    public boolean isSuperseded(String organizationId, ReleasedReport report) {
        return reports.values().stream()
                .filter(candidate -> organizationId.equals(candidate.organizationId()))
                .filter(candidate -> report.id().equals(candidate.id()))
                .filter(candidate -> candidate.revision() > report.revision())
                .filter(candidate -> candidate.status() != ReleasedReport.Status.HELD)
                .map(ReleasedReport::correction)
                .filter(correction -> correction != null)
                .anyMatch(correction -> correction.supersedesRevision() == report.revision());
    }

    /**
     * Withdraw one exact revision, retaining its history (XCH-06).
     *
     * @param organizationId active organization identifier
     * @param id             report identifier
     * @param revision       revision to withdraw
     * @return {@code true} when the revision existed and was withdrawn
     */
    public boolean withdraw(String organizationId, String id, int revision) {
        String key = reportKey(organizationId, id, revision);
        ReleasedReport current = reports.get(key);
        if (current == null) {
            return false;
        }
        ReleasedReport withdrawn = new ReleasedReport(current.id(), current.revision(), current.patientId(),
                current.organizationId(), current.issuingOrganization(), current.effectiveDate(),
                current.language(), current.documentId(), current.checksum(),
                ReleasedReport.Status.WITHDRAWN, current.correction());
        reports.put(key, withdrawn);
        return true;
    }

    private static String linkKey(String organizationId, String subject) {
        return organizationId + "|" + subject;
    }

    private static String reportKey(String organizationId, String id, int revision) {
        return organizationId + "|" + id + "|" + revision;
    }
}
