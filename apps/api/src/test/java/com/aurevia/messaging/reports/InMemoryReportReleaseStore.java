package com.aurevia.messaging.reports;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Synthetic, thread-safe test adapter for the report-release port (PAT-02, ARC-02).
 *
 * <p>Every key includes the organization so two tenants with identical local identifiers
 * cannot collide. Release decisions are appended to an immutable list and the current
 * access state is updated, mirroring the JDBC adapter's transactional contract.</p>
 */
final class InMemoryReportReleaseStore implements ReportReleaseStore {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{2,64}");

    private final ConcurrentMap<String, PatientChartLink> links = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReleasedReport> reports = new ConcurrentHashMap<>();
    private final List<ReportRelease> history = new ArrayList<>();

    @Override
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

    @Override
    public Optional<PatientChartLink> link(String organizationId, String subject) {
        return Optional.ofNullable(links.get(linkKey(organizationId, subject)));
    }

    @Override
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

    @Override
    public Optional<ReleasedReport> find(String organizationId, String id, int revision) {
        return Optional.ofNullable(reports.get(reportKey(organizationId, id, revision)));
    }

    @Override
    public List<ReleasedReport> reportsFor(String organizationId, String patientId) {
        return reports.values().stream()
                .filter(report -> organizationId.equals(report.organizationId()))
                .filter(report -> patientId.equals(report.patientId()))
                .sorted(Comparator.comparing(ReleasedReport::effectiveDate).reversed()
                        .thenComparing(ReleasedReport::id).thenComparingInt(ReleasedReport::revision))
                .toList();
    }

    @Override
    public OptionalInt latestReleasedRevision(String organizationId, String id, LocalDate effectiveOn) {
        return reports.values().stream()
                .filter(report -> organizationId.equals(report.organizationId()))
                .filter(report -> id.equals(report.id()))
                .filter(report -> report.status() == ReleasedReport.Status.RELEASED)
                .filter(report -> !LocalDate.parse(report.effectiveDate()).isAfter(effectiveOn))
                .mapToInt(ReleasedReport::revision)
                .max();
    }

    @Override
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

    @Override
    public synchronized boolean withdraw(String organizationId, String id, int revision) {
        String key = reportKey(organizationId, id, revision);
        ReleasedReport current = reports.get(key);
        if (current == null || current.status() == ReleasedReport.Status.WITHDRAWN) {
            return false;
        }
        ReleasedReport withdrawn = new ReleasedReport(current.id(), current.revision(), current.patientId(),
                current.organizationId(), current.issuingOrganization(), current.effectiveDate(),
                current.language(), current.documentId(), current.checksum(),
                ReleasedReport.Status.WITHDRAWN, current.correction());
        reports.put(key, withdrawn);
        history.add(new ReportRelease(current, 1, java.time.Instant.parse(current.effectiveDate() + "T00:00:00Z"),
                null, null, java.time.Instant.parse(current.effectiveDate() + "T00:00:00Z")));
        return true;
    }

    @Override
    public synchronized void recordRelease(ReportRelease release) {
        ReleasedReport revision = release.revision();
        String key = reportKey(revision.organizationId(), revision.id(), revision.revision());
        ReleasedReport current = reports.get(key);
        if (current != null && current.status() == ReleasedReport.Status.WITHDRAWN
                && revision.status() != ReleasedReport.Status.WITHDRAWN) {
            throw new IllegalStateException("a withdrawn revision cannot be released again");
        }
        reports.put(key, revision);
        history.add(release);
    }

    @Override
    public synchronized List<ReportRelease> releaseHistory(String organizationId, String reportId, int revision) {
        return history.stream()
                .filter(fact -> organizationId.equals(fact.revision().organizationId()))
                .filter(fact -> reportId.equals(fact.revision().id()))
                .filter(fact -> revision == fact.revision().revision())
                .toList();
    }

    private static String linkKey(String organizationId, String subject) {
        return organizationId + "|" + subject;
    }

    private static String reportKey(String organizationId, String id, int revision) {
        return organizationId + "|" + id + "|" + revision;
    }
}
